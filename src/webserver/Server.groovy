/*
 * Server.groovy - Финальная версия с полем BS_NAME и раздельным поиском по истории
 */
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpExchange
import groovy.xml.XmlSlurper
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import java.net.InetSocketAddress
import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import groovy.json.JsonOutput
import groovy.xml.XmlParser
import groovy.xml.MarkupBuilder
import groovy.sql.Sql
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.PrintWriter

import com.github.javafaker.Faker
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Base64


class Server {

    static class MyJsonOutput {
        static String toJson(data) {
            return groovy.json.JsonOutput.toJson(data)
        }
        static String prettyPrint(String json) {
            try {
                return groovy.json.JsonOutput.prettyPrint(json)
            } catch (Exception e) {
                return json
            }
        }
    }

    static class DataConverter {
        def parse(String text, String format) { if (!text || text.trim().isEmpty()) throw new IllegalArgumentException("Входной текст не может быть пустым."); if (format == 'json') return new JsonSlurper().parseText(text); if (format == 'xml') return normalizeXmlNode(new XmlParser().parseText(text)); throw new IllegalArgumentException("Неподдерживаемый формат: ${format}") }
        private def normalizeXmlNode(node) { if (node instanceof String) return node; def children = node.children().findAll { !(it instanceof String && it.trim().isEmpty()) }; if (children.isEmpty() && !node.text().isEmpty()) return node.text(); def map = [:]; children.each { child -> if (child.respondsTo('name')) { def name = child.name(); def value = normalizeXmlNode(child); if (map.containsKey(name)) { if (!(map[name] instanceof List)) map[name] = [map[name]]; map[name].add(value) } else { map[name] = value } } }; if (node.attributes()) node.attributes().each { k, v -> map["@${k}"] = v }; if (map.isEmpty() && !node.text().isEmpty()) return node.text(); return map }
        def serialize(data, String format, boolean pretty) {
            if (format == 'json') {
                def jsonString = groovy.json.JsonOutput.toJson(data)
                return pretty ? groovy.json.JsonOutput.prettyPrint(jsonString) : jsonString
            } else if (format == 'xml') {
            def writer = new StringWriter(); def builder = new MarkupBuilder(writer); builder.root { buildXml(builder, data) };
            return writer.toString() }; return "Неподдерживаемый формат" }
        private void buildXml(MarkupBuilder builder, data) { if (data instanceof Map) { data.each { key, value -> if (key.startsWith('@')) return; if (value instanceof List) { value.each { item -> builder."$key" { buildXml(builder, item) } } } else { def attrs = (value instanceof Map) ? value.findAll { it.key.startsWith('@') }.collectEntries { k, v -> [k.substring(1), v] } : [:]; def children = (value instanceof Map) ? value.findAll { !it.key.startsWith('@') } : value; builder."$key"(attrs) { buildXml(builder, children) } } } } else if (data != null && !(data instanceof List)) { builder.mkp.yield(data.toString()) } }
        def generateJsonSchema(jsonData) { def rootSchemaContent = determineSchema(jsonData, "Root"); return ["\$schema": "http://json-schema.org/draft-07/schema#", title: "Generated Schema", description: "Automatically generated schema"] + rootSchemaContent }
        private def determineSchema(value, String propertyName = "") { def baseSchema = [:]; if (propertyName) baseSchema.title = propertyName.capitalize(); if (value == null) return baseSchema + [type: ["string", "number", "boolean", "array", "object", "null"]]; if (value instanceof String) return baseSchema + [type: "string"]; if (value instanceof Number) return baseSchema + [type: "number"]; if (value instanceof Boolean) return baseSchema + [type: "boolean"]; if (value instanceof List) { def firstNonNull = value ? value.find {it != null} : null; def itemsSchema = determineSchema(firstNonNull, propertyName ? "${propertyName} Item" : "Array Item"); return baseSchema + [type: "array", items: itemsSchema] }; if (value instanceof Map) { def propertiesMap = value.collectEntries { k, v -> [k, determineSchema(v, k)] }; return baseSchema + [type: "object", properties: propertiesMap, required: value.keySet() as List, additionalProperties: false] }; return baseSchema + [type: ["string", "number", "boolean", "array", "object", "null"]] }
    }

    static class ExcelExporter {
        private String getValueType(value) { if (value instanceof Map) return "object"; if (value instanceof List) return "array"; if (value instanceof String) { if (value.isNumber()) return value.contains('.') ? "decimal" : "integer"; return "string" }; if (value instanceof Number) return "integer"; if (value instanceof Boolean) return "boolean"; if (value == null) return "null"; return "unknown" }
        private void parseJsonStructure(String key, value, String level, List<Map> results) { String vT=getValueType(value); results.add(["Уровень":level,"Наименование":key,"Категория":"Element","Тип":vT,"Повторение":(vT=='array'?"0..n":"1..1"),"Описание":""]); if(vT=="object"){value.eachWithIndex{ck,cv,i->parseJsonStructure(ck,cv,"${level}.${i+1}",results)}}else if(vT=="array"&&value){def f=value.find{it!=null};if(f instanceof Map){f.eachWithIndex{ck,cv,i->parseJsonStructure(ck,cv,"${level}.${i+1}",results)}}} }
        byte[] createExcelFromStructure(Map data) { List<Map> rows=[]; data.eachWithIndex{k,v,i->parseJsonStructure(k,v,"${i+1}",rows)}; Workbook wb=new org.apache.poi.xssf.usermodel.XSSFWorkbook(); Sheet s=wb.createSheet("Structure"); Row hr=s.createRow(0); def hs=["Уровень", "Наименование", "Категория", "Тип", "Повторение", "Описание"]; hs.eachWithIndex{ h, i->hr.createCell(i).setCellValue(h)}; rows.eachWithIndex{rd,i-> Row r=s.createRow(i+1);rd.values().eachWithIndex{ v, j->r.createCell(j).setCellValue(v.toString())}}; hs.size().times{s.autoSizeColumn(it)}; def os=new ByteArrayOutputStream();wb.write(os);wb.close();return os.toByteArray()}
    }

    static class ExampleGenerator {
        private Faker faker; private Map rootSchema
        ExampleGenerator() { this.faker = new Faker(new Locale("ru")) }
        String generateExampleFromJsonSchema(String schemaText) { if (!schemaText || schemaText.trim().isEmpty()) { throw new IllegalArgumentException("JSON Schema не может быть пустой.") }; try { this.rootSchema = new JsonSlurper().parseText(schemaText); def generatedData = generateFromSchema(this.rootSchema, this.rootSchema, "#"); return JsonOutput.prettyPrint(JsonOutput.toJson(generatedData)) } catch (Exception e) { e.printStackTrace(); throw e } }
        private def findDefinition(Map rootSchema, String ref) { def parts = ref.split('/'); if (parts.length == 3 && parts[0] == '#' && parts[1] == 'definitions') { def defName = parts[2]; def definition = rootSchema.definitions[defName]; if (definition == null) { throw new IllegalStateException("Definition '${defName}' не найдена в схеме!") }; return definition }; throw new UnsupportedOperationException("Поддерживаются только простые \$ref вида '#/definitions/...'") }
        private def generateFromSchema(Map rootSchema, Map subSchema, String currentPath)  {
            if (subSchema == null) return null; if (subSchema.'$ref') { def resolvedSchema = findDefinition(rootSchema, subSchema.'$ref'); def mergedSchema = new HashMap(resolvedSchema); subSchema.findAll { it.key != '$ref' }.each { mergedSchema[it.key] = it.value }; return generateFromSchema(rootSchema, mergedSchema, currentPath + " (resolved from ${subSchema.'$ref'})") }; if (subSchema.enum instanceof List) { def enumList = subSchema.enum; if (enumList.isEmpty()) return null; return enumList[faker.number().numberBetween(0, enumList.size() - 1)] }; def type = subSchema.type; if (type instanceof List) { type = type.find { it != 'null' } ?: 'string' }; if (type == null && subSchema.properties instanceof Map) { type = "object" }; switch (type) {
                case "object": def obj = [:]; if (subSchema.properties instanceof Map) { for (Map.Entry entry in subSchema.properties.entrySet()) { def key = entry.getKey(); def value = entry.getValue(); if (value instanceof Map) { obj[key] = generateFromSchema(rootSchema, value, currentPath + "/" + key) } } }; return obj
                case "array": def arr = []; if (subSchema.items instanceof Map) { faker.number().numberBetween(1, 2).times { arr.add(generateFromSchema(rootSchema, subSchema.items, currentPath + "/items")) } }; return arr
                case "string": if (subSchema.pattern) { try { return faker.regexify(subSchema.pattern) } catch (Exception e) { return faker.number().digits(10) } }; switch (subSchema.format) { case 'date-time': return faker.date().past(365, TimeUnit.DAYS).toInstant().toString(); case 'date': return new java.text.SimpleDateFormat("yyyy-MM-dd").format(faker.date().birthday()); case 'email': return faker.internet().emailAddress(); case 'uuid': return UUID.randomUUID().toString(); case 'uri': return faker.internet().url(); default: return faker.lorem().sentence(faker.number().numberBetween(2, 5)) }
                case "integer": Number min = (subSchema.minimum instanceof Number) ? subSchema.minimum : 1; Number max = (subSchema.maximum instanceof Number) ? subSchema.maximum : 10000; return faker.number().numberBetween(min.longValue(), max.longValue())
                case "number": Number minNum = (subSchema.minimum instanceof Number) ? subSchema.minimum : 1.0; Number maxNum = (subSchema.maximum instanceof Number) ? subSchema.maximum : 10000.0; return faker.number().randomDouble(2, minNum.longValue(), maxNum.longValue())
                case "boolean": return faker.bool().bool(); case "null": return null; default: return "Неподдерживаемый тип: ${type}"
            }
        }
    }

    static class MultiRequestExecutor {
        private CallHistoryManager historyManager
        MultiRequestExecutor(CallHistoryManager historyManager) { this.historyManager = historyManager }

        def executeAndGetLogs(config) {
            def client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10)).build()
            def mainLog = new StringBuilder()
            def numRequests = config.numberOfRequests ?: 1
            mainLog.append("Начинаю выполнение ${numRequests} запросов к ${config.url}...\n\n")

            def finalHeaders = new HashMap(config.headers)
            if (config.login && !config.login.isEmpty()) {
                String auth = config.login + ":" + (config.password ?: "")
                finalHeaders['Authorization'] = 'Basic ' + Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8))
            }

            (1..numRequests).each { i ->
                def requestBody = generateRequestBody(config.template, config.dataRules, i)
                def logEntry = [:]
                def historyEntry = [
                        cs_name: config.csName,
                        bs_name: config.bsName,
                        interface_o: config.interfaceName,
                        service_url: config.url,
                        request: requestBody,
                        request_headers: MyJsonOutput.toJson(finalHeaders)
                ]

                try {
                    historyEntry.domain_url = new URI(config.url).getHost()
                } catch (e) {
                    historyEntry.domain_url = "invalid url"
                }

                try {
                    def requestBuilder = HttpRequest.newBuilder().uri(URI.create(config.url)).timeout(Duration.ofSeconds(20))
                    finalHeaders.each { key, value -> requestBuilder.header(key, value.toString()) }

                    def method = config.method?.toUpperCase() ?: 'POST'
                    switch (method) {
                        case 'POST': requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody)); break
                        case 'GET': requestBuilder.GET(); break
                        case 'PUT': requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(requestBody)); break
                        case 'PATCH': requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(requestBody)); break
                        case 'DELETE': requestBuilder.DELETE(); break
                        default: requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    }

                    def response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                    logEntry.responseStatus = response.statusCode()
                    logEntry.responseBody = response.body()
                    historyEntry.response = response.body()
                    historyEntry.response_headers = MyJsonOutput.toJson(response.headers().map())

                } catch (Exception e) {
                    def sw = new StringWriter()
                    e.printStackTrace(new PrintWriter(sw))
                    def stackTrace = sw.toString()
                    logEntry.error = e.getMessage()
                    logEntry.stackTrace = stackTrace
                    historyEntry.stack_trace = stackTrace
                }

                historyManager.saveCall(historyEntry)
                def logString = formatLogEntry(logEntry, requestBody)
                mainLog.append("--- Запрос ${i}/${numRequests} ---\n")
                mainLog.append(logString + "\n" + "=".repeat(50) + "\n\n")
            }
            mainLog.append("Работа завершена.")
            return mainLog.toString()
        }
        private String generateRequestBody(String template, Map rules, int iteration) { def body = template; rules?.each { key, rule -> def placeholder = "##${key}##"; def value; switch (rule.type) { case 'increment': value = (rule.start + (rule.step * (iteration - 1))); break; case 'random': value = rule.values[new Random().nextInt(rule.values.size())]; break; case 'uuid': value = UUID.randomUUID().toString(); break; case 'current_timestamp': value = new Date().format(rule.format ?: 'yyyy-MM-dd HH:mm:ss'); break; case 'random_number': def min = rule.min ?: 0; def max = rule.max ?: 100; value = new Random().nextInt((max - min) + 1) + min; break; case 'from_list': value = rule.values[(iteration - 1) % rule.values.size()]; break }; if (value != null) { body = body.replace(placeholder, value.toString()) } }; return body }
        private String formatLogEntry(Map entry, String requestBody) { def builder = new StringBuilder(); builder.append("Request Body:\n${requestBody}\n"); builder.append("---------------------------------\n"); builder.append("Response Status: ${entry.responseStatus ?: 'N/A'}\n"); builder.append("Response Body:\n${entry.responseBody ?: 'N/A'}\n"); if (entry.error) { builder.append("Error: ${entry.error}\n") }; if (entry.stackTrace) { builder.append("Stack Trace:\n${entry.stackTrace}\n") }; return builder.toString() }
    }

    static class XsdToWsdlConverter {
        String generate(String xsdContent, String xsdFileName, String serviceType) { if (!xsdContent || xsdContent.trim().isEmpty()) throw new IllegalArgumentException("Содержимое XSD не может быть пустым."); if (!xsdFileName || xsdFileName.trim().isEmpty()) throw new IllegalArgumentException("Имя XSD файла не может быть пустым."); if (serviceType != 'synchronous' && serviceType != 'asynchronous') throw new IllegalArgumentException("Неверный тип сервиса."); def baseName = xsdFileName.take(xsdFileName.lastIndexOf('.')); def serviceName = "si_${serviceType == 'synchronous' ? 'so' : 'ao'}_${baseName}"; def portTypeName = serviceName; def bindingName = "${serviceName}Binding"; def serviceInstanceName = "${serviceName}Service"; def targetNamespace = "urn:example.com:${baseName}"; def xsd = new XmlSlurper().parseText(xsdContent); def rootElementName = xsd.element[0].'@name'.text(); if (!rootElementName) throw new IllegalStateException("Не удалось найти корневой элемент (<xsd:element name=...>) в XSD."); def requestMessageName = "mt_${rootElementName}_RQ"; def responseMessageName = "mt_${rootElementName}_RS"; def writer = new StringWriter(); def wsdl = new MarkupBuilder(writer); wsdl.mkp.xmlDeclaration(version: "1.0", encoding: "UTF-8"); wsdl.'wsdl:definitions'( 'xmlns:wsdl': "http://schemas.xmlsoap.org/wsdl/", 'xmlns:soap': "http://schemas.xmlsoap.org/wsdl/soap/", 'xmlns:xsd': "http://www.w3.org/2001/XMLSchema", 'xmlns:tns': targetNamespace, name: serviceName, targetNamespace: targetNamespace) { 'wsdl:types' { 'xsd:schema'(targetNamespace: targetNamespace) { wsdl.mkp.yieldUnescaped(xsdContent) } }; 'wsdl:message'(name: requestMessageName) { 'wsdl:part'(name: 'parameters', element: "tns:${rootElementName}") }; if (serviceType == 'synchronous') { 'wsdl:message'(name: responseMessageName) { 'wsdl:part'(name: 'parameters', element: "tns:${rootElementName}Response") } }; 'wsdl:portType'(name: portTypeName) { 'wsdl:operation'(name: serviceName) { 'wsdl:input'(message: "tns:${requestMessageName}"); if (serviceType == 'synchronous') { 'wsdl:output'(message: "tns:${responseMessageName}") } } }; 'wsdl:binding'(name: bindingName, type: "tns:${portTypeName}") { 'soap:binding'(style: 'document', transport: 'http://schemas.xmlsoap.org/soap/http'); 'wsdl:operation'(name: serviceName) { 'soap:operation'(soapAction: "http://sap.com/xi/WebService/soap1.1"); 'wsdl:input' { 'soap:body'(use: 'literal') }; if (serviceType == 'synchronous') { 'wsdl:output' { 'soap:body'(use: 'literal') } } } }; 'wsdl:service'(name: serviceInstanceName) { 'wsdl:port'(name: 'HTTP_Port', binding: "tns:${bindingName}") { 'soap:address'(location: "http://0.0.0.0:8080/soap/${serviceInstanceName}") }; 'wsdl:port'(name: 'HTTPS_Port', binding: "tns:${bindingName}") { 'soap:address'(location: "https://0.0.0.0:8443/soap/${serviceInstanceName}") } } }; def wsdlOutput = writer.toString(); if (serviceType == 'synchronous') { def responseElement = """\n    <xsd:element name="${rootElementName}Response"><xsd:complexType><xsd:sequence><xsd:element name="Result" type="xsd:string"/></xsd:sequence></xsd:complexType></xsd:element>"""; int lastSchemaTagIndex = wsdlOutput.lastIndexOf("</xsd:schema>"); if(lastSchemaTagIndex != -1) { wsdlOutput = new StringBuilder(wsdlOutput).insert(lastSchemaTagIndex, responseElement).toString() } }; return wsdlOutput }
    }

    static class LabelsManager {
        private Sql sql
        LabelsManager(Sql sql) { this.sql = sql }
        def getAll() { def categories = sql.rows("SELECT * FROM labels_categories ORDER BY display_order ASC, name ASC"); def labels = sql.rows("SELECT * FROM labels ORDER BY usage_count DESC, date_created DESC"); return [categories: categories, labels: labels] }
        def search(String query, Integer categoryId) { def labelsFromDb; if (categoryId != null) { labelsFromDb = sql.rows("SELECT * FROM labels WHERE category_id = ?", [categoryId]) } else { labelsFromDb = sql.rows("SELECT * FROM labels") }; def filteredLabels = labelsFromDb; if (query && !query.trim().isEmpty()) { def normalizedQuery = query.trim().toLowerCase(); filteredLabels = labelsFromDb.findAll { label -> return label.content && label.content.toLowerCase().contains(normalizedQuery) } }; def sortedLabels = filteredLabels.sort { a, b -> (b.usage_count <=> a.usage_count) ?: (b.date_created <=> a.date_created) }; return sortedLabels }
        def saveCategory(Map data) { if (data.id) { sql.execute("UPDATE labels_categories SET name=?, display_order=? WHERE id=?", [data.name, data.display_order ?: 99, data.id]) } else { sql.execute("INSERT INTO labels_categories (name, display_order) VALUES (?, ?)", [data.name, data.display_order ?: 99]) }; return [status: 'OK'] }
        def deleteCategory(int id) { sql.execute("DELETE FROM labels WHERE category_id=?", [id]); sql.execute("DELETE FROM labels_categories WHERE id=?", [id]); return [status: 'OK'] }
        def saveLabel(Map data) { if (data.id) { sql.execute("UPDATE labels SET content=?, category_id=? WHERE id=?", [data.content, data.category_id, data.id]) } else { sql.execute("INSERT INTO labels (content, category_id) VALUES (?, ?)", [data.content, data.category_id]) }; return [status: 'OK'] }
        def deleteLabel(int id) { sql.execute("DELETE FROM labels WHERE id=?", [id]); return [status: 'OK'] }
        def incrementUsage(int id) { sql.execute("UPDATE labels SET usage_count = usage_count + 1 WHERE id=?", [id]); return [status: 'OK'] }
    }

    static class CallHistoryManager {
        private Sql sql
        CallHistoryManager(Sql sql) { this.sql = sql }

        def saveCall(Map data) { sql.execute("INSERT INTO call_history (cs_name, bs_name, interface_o, request, response, service_url, domain_url, request_headers, response_headers, stack_trace, call_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", [data.cs_name, data.bs_name, data.interface_o, data.request, data.response, data.service_url, data.domain_url, data.request_headers, data.response_headers, data.stack_trace, new Date()]) }
        def getAll() { return sql.rows("SELECT * FROM call_history ORDER BY id DESC") }
        def getGroup(String csName) { return sql.rows("SELECT * FROM call_history WHERE cs_name = ? ORDER BY id ASC", [csName]) }

        def search(Map params) {
            def queryBuilder = new StringBuilder("SELECT * FROM call_history")
            def whereClauses = []
            def queryParams = []
            params.each { key, value ->
                if (value && !value.trim().isEmpty()) {
                    def normalizedValue = "%${value.trim().toLowerCase()}%"
                    switch (key) {
                        case 'csName': whereClauses.add("LOWER(cs_name) LIKE ?"); queryParams.add(normalizedValue); break
                        case 'bsName': whereClauses.add("LOWER(bs_name) LIKE ?"); queryParams.add(normalizedValue); break
                        case 'interfaceName': whereClauses.add("LOWER(interface_o) LIKE ?"); queryParams.add(normalizedValue); break
                        case 'url': whereClauses.add("LOWER(service_url) LIKE ?"); queryParams.add(normalizedValue); break
                        case 'content': whereClauses.add("(LOWER(request) LIKE ? OR LOWER(response) LIKE ? OR LOWER(stack_trace) LIKE ?)"); 3.times { queryParams.add(normalizedValue) }; break
                    }
                }
            }
            if (whereClauses) { queryBuilder.append(" WHERE ").append(whereClauses.join(" AND ")) }
            queryBuilder.append(" ORDER BY id DESC")
            return sql.rows(queryBuilder.toString(), queryParams)
        }

        def getAllCredentials() { return sql.rows("SELECT id, name FROM credentials ORDER BY name ASC") }
        def getCredentialById(int id) { return sql.firstRow("SELECT * FROM credentials WHERE id = ?", [id]) }
        def saveCredential(Map data) { if (data.id) { sql.execute("UPDATE credentials SET name=?, login=?, password=? WHERE id=?", [data.name, data.login, data.password, data.id]) } else { sql.execute("INSERT INTO credentials (name, login, password) VALUES (?,?,?)", [data.name, data.login, data.password]) }; return [status: 'OK'] }
        def deleteCredential(int id) { sql.execute("DELETE FROM credentials WHERE id=?", [id]); return [status: 'OK']}
    }

    static void main(String[] args){
        def dbFile = 'editor.db'
        def sql = Sql.newInstance("jdbc:sqlite:${dbFile}", "org.sqlite.JDBC")

        // --- ИНИЦИАЛИЗАЦИЯ ВСЕХ ТАБЛИЦ БД ---
        sql.execute'''CREATE TABLE IF NOT EXISTS saved_documents (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, content TEXT, date_created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, user TEXT, task_url TEXT, task_number TEXT, stage TEXT, status TEXT, deployment_date TEXT, additional_comment TEXT, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS task_settings (key TEXT PRIMARY KEY, value TEXT)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS labels_categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, display_order INTEGER DEFAULT 99)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS labels (id INTEGER PRIMARY KEY AUTOINCREMENT, category_id INTEGER, content TEXT NOT NULL, usage_count INTEGER DEFAULT 0, date_created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, FOREIGN KEY(category_id) REFERENCES labels_categories(id))'''
        sql.execute'''CREATE TABLE IF NOT EXISTS credentials (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, login TEXT, password TEXT)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS call_history (id INTEGER PRIMARY KEY AUTOINCREMENT, cs_name TEXT, bs_name TEXT, interface_o TEXT, request TEXT, response TEXT, service_url TEXT, domain_url TEXT, request_headers TEXT, response_headers TEXT, stack_trace TEXT, call_date DATETIME)'''

        // --- МИГРАЦИЯ СХЕМЫ (добавление новых колонок в существующие таблицы) ---
        try { sql.firstRow("SELECT usage_count FROM labels LIMIT 1") } catch (Exception e) { sql.execute("ALTER TABLE labels ADD COLUMN usage_count INTEGER DEFAULT 0") }
        try { sql.firstRow("SELECT bs_name FROM call_history LIMIT 1") } catch (Exception e) { sql.execute("ALTER TABLE call_history ADD COLUMN bs_name TEXT") }

        def check = sql.firstRow("SELECT COUNT(*) as c FROM task_settings WHERE key IN ('target_url', 'target_user', 'target_password')")
        if (check.c == 0) {
            sql.execute("INSERT INTO task_settings (key, value) VALUES ('target_url', '')")
            sql.execute("INSERT INTO task_settings (key, value) VALUES ('target_user', '')")
            sql.execute("INSERT INTO task_settings (key, value) VALUES ('target_password', '')")
        }

        def server = HttpServer.create(new InetSocketAddress(8080), 0)
        println "Сервер запущен на http://localhost:8080"

        def historyManager = new CallHistoryManager(sql)
        def converter = new DataConverter(); def excelExporter = new ExcelExporter(); def exampleGenerator = new ExampleGenerator(); def multiRequestExecutor = new MultiRequestExecutor(historyManager); def xsdToWsdlConverter = new XsdToWsdlConverter(); def labelsManager = new LabelsManager(sql)

        def staticContentHandler = { HttpExchange e ->
            try {
                String path = e.getRequestURI().getPath(); if (path == "/") path = "/index.html"
                String filePath = path.substring(1); def file = new File(filePath)
                if (file.exists() && !file.isDirectory()) {
                    String contentType = "text/html; charset=utf-8"; if (path.endsWith(".css")) contentType = "text/css; charset=utf-8"; else if (path.endsWith(".js")) contentType = "application/javascript; charset=utf-8"
                    byte[] fileBytes = new FileInputStream(file).readAllBytes(); e.responseHeaders.add("Content-Type", contentType); e.sendResponseHeaders(200, fileBytes.length); e.getResponseBody().write(fileBytes); e.getResponseBody().close()
                } else { sendResponse(e, "Файл не найден: ${filePath}", "text/plain", 404) }
            } catch (Exception ex) { sendResponse(e, "Внутренняя ошибка сервера: " + ex.message, "text/plain", 500); ex.printStackTrace() }
        }
        server.createContext("/", staticContentHandler)

        // --- ВСЕ API ENDPOINTS ---
        server.createContext("/api/parse"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,groovy.json.JsonOutput.toJson(converter.parse(d.text,d.format)),"application/json")}}
        server.createContext("/api/serialize"){e->handleRequest(e,"POST"){def s=new JsonSlurper().parse(e.requestBody);def p=e.requestURI.query?.split('&').collectEntries{[(it.split('=')[0]):URLDecoder.decode(it.split('=')[1],"UTF-8")]};sendResponse(e,converter.serialize(s,p.format?:'json',p.pretty?p.pretty=='true':true),"text/plain")}}
        server.createContext("/api/fetch-url"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,new URL(d.url).text,"text/plain")}}
        server.createContext("/api/convert"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);if(!d.text||!d.from||!d.to)throw new IllegalArgumentException("Args missing");sendResponse(e,converter.serialize(converter.parse(d.text,d.from),d.to,true),"text/plain")}}
        server.createContext("/api/generate-schema"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,MyJsonOutput.prettyPrint(MyJsonOutput.toJson(converter.generateJsonSchema(new JsonSlurper().parseText(d.json)))),"application/json")}}
        server.createContext("/api/export-to-excel"){e->handleRequest(e,"POST"){String b=e.requestBody.text;def jT=b.trim().startsWith("{")||b.trim().startsWith("[")?b:(b.startsWith("json=")?URLDecoder.decode(b.substring(5),"UTF-8"):null);if(!jT)throw new IllegalArgumentException("No JSON found");byte[]x=excelExporter.createExcelFromStructure(new JsonSlurper().parseText(jT));e.responseHeaders.add("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");e.responseHeaders.add("Content-Disposition","attachment; filename=\"json_structure.xlsx\"");e.sendResponseHeaders(200,x.length);e.responseBody.withStream{it.write(x)}}}
        server.createContext("/api/xml-to-excel"){e->handleRequest(e,"POST"){String b=e.requestBody.text;def xT=b.startsWith("xml=")?URLDecoder.decode(b.substring(4),"UTF-8"):b;byte[]x=excelExporter.createExcelFromStructure(converter.parse(xT,"xml"));e.responseHeaders.add("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");e.responseHeaders.add("Content-Disposition","attachment; filename=\"xml_structure.xlsx\"");e.sendResponseHeaders(200,x.length);e.responseBody.withStream{it.write(x)}}}
        server.createContext("/api/generate-example"){e->handleRequest(e,"POST"){def schemaText=e.requestBody.text;if(!schemaText||schemaText.trim().isEmpty()){throw new IllegalArgumentException("Тело запроса со схемой не может быть пустым.")};def exampleJsonStr=exampleGenerator.generateExampleFromJsonSchema(schemaText);sendResponse(e,exampleJsonStr,"application/json")}}
        server.createContext("/api/documents"){e->if(e.requestMethod=="GET")handleRequest(e,"GET"){sendResponse(e,MyJsonOutput.toJson(sql.rows("SELECT id, name FROM saved_documents ORDER BY name ASC")),"application/json")}else if(e.requestMethod=="POST")handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);def doc=sql.firstRow("SELECT id FROM saved_documents WHERE name=?",[d.name]);if(doc){sql.execute("UPDATE saved_documents SET content=?,last_updated=CURRENT_TIMESTAMP WHERE id=?",[d.content,doc.id]);sendResponse(e,MyJsonOutput.toJson([status:"OK",id:doc.id,name:d.name]),"application/json")}else{def id=sql.executeInsert("INSERT INTO saved_documents (name,content) VALUES (?,?)",[d.name,d.content])[0][0];sendResponse(e,MyJsonOutput.toJson([status:"OK",id:id,name:d.name]),"application/json")}}}
        server.createContext("/api/documents/"){e->def id=e.requestURI.path.split('/').last();if(e.requestMethod=="GET")handleRequest(e,"GET"){def d=sql.firstRow("SELECT content FROM saved_documents WHERE id=?",[id]);if(d)sendResponse(e,d.content,"text/plain")else sendResponse(e,"Документ не найден","text/plain",404)}else if(e.requestMethod=="DELETE")handleRequest(e,"DELETE"){sql.execute("DELETE FROM saved_documents WHERE id=?",[id]);sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}
        server.createContext("/api/xsd-to-wsdl") { e -> handleRequest(e, "POST") { def data = new JsonSlurper().parse(e.requestBody); def wsdlString = xsdToWsdlConverter.generate(data.xsdContent, data.xsdFileName, data.serviceType); sendResponse(e, wsdlString, "application/xml") } }
        server.createContext("/api/tasks/settings") { e -> if (e.requestMethod == "GET") { handleRequest(e, "GET") { def settings = [:]; sql.eachRow("SELECT key, value FROM task_settings") { row -> settings[row.key] = row.value }; sendResponse(e, MyJsonOutput.toJson(settings), "application/json") } } else if (e.requestMethod == "POST") { handleRequest(e, "POST") { def data = new JsonSlurper().parse(e.requestBody); data.each { key, value -> sql.execute("UPDATE task_settings SET value=? WHERE key=?", [value, key]) }; sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json") } } }
        server.createContext("/api/tasks") { e -> if (e.requestMethod == "GET") { handleRequest(e, "GET") { def tasks = sql.rows("SELECT * FROM tasks ORDER BY last_updated DESC"); sendResponse(e, MyJsonOutput.toJson(tasks), "application/json") } } else if (e.requestMethod == "POST") { handleRequest(e, "POST") { def task = new JsonSlurper().parse(e.requestBody); if (task.id) { sql.execute("UPDATE tasks SET user=?, task_url=?, task_number=?, stage=?, status=?, deployment_date=?, additional_comment=?, last_updated=CURRENT_TIMESTAMP WHERE id=?", [task.user, task.task_url, task.task_number, task.stage, task.status, task.deployment_date, task.additional_comment, task.id]) } else { sql.execute("INSERT INTO tasks (user, task_url, task_number, stage, status, deployment_date, additional_comment) VALUES (?, ?, ?, ?, ?, ?, ?)", [task.user, task.task_url, task.task_number, task.stage, task.status, task.deployment_date, task.additional_comment]) }; sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json") } } else if (e.requestMethod == "DELETE") { handleRequest(e, "DELETE") { def data = new JsonSlurper().parse(e.requestBody); sql.execute("DELETE FROM tasks WHERE id = ?", [data.id]); sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json") } } }
        server.createContext("/api/tasks/send") { e -> handleRequest(e, "POST") { def settings = [:]; sql.eachRow("SELECT key, value FROM task_settings") { row -> settings[row.key] = row.value }; if (!settings.target_url) { throw new Exception("URL для отправки не настроен.") }; def tasks = sql.rows("SELECT * FROM tasks"); String auth = settings.target_user + ":" + (settings.target_password ?: ""); String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8)); def client = HttpClient.newHttpClient(); def request = HttpRequest.newBuilder().uri(URI.create(settings.target_url)).header("Content-Type", "application/json").header("Authorization", "Basic " + encodedAuth).POST(HttpRequest.BodyPublishers.ofString(MyJsonOutput.toJson(tasks))).build(); def response = client.send(request, HttpResponse.BodyHandlers.ofString()); def result = [statusCode: response.statusCode(), responseBody: response.body()]; sendResponse(e, MyJsonOutput.toJson(result), "application/json") } }
        server.createContext("/api/tasks/export") { e -> handleRequest(e, "GET") { Workbook wb = new XSSFWorkbook(); Sheet sheet = wb.createSheet("Задачи"); Row headerRow = sheet.createRow(0); def headers = ["ID", "Пользователь", "Номер задачи", "URL задачи", "Этап", "Статус", "Дата установки (PROD)", "Комментарий", "Последнее обновление"]; headers.eachWithIndex { header, i -> headerRow.createCell(i).setCellValue(header) }; def tasks = sql.rows("SELECT * FROM tasks ORDER BY id DESC"); tasks.eachWithIndex { task, i -> Row row = sheet.createRow(i + 1); row.createCell(0).setCellValue(task.id.toString()); row.createCell(1).setCellValue(task.user); row.createCell(2).setCellValue(task.task_number); row.createCell(3).setCellValue(task.task_url); row.createCell(4).setCellValue(task.stage); row.createCell(5).setCellValue(task.status); row.createCell(6).setCellValue(task.deployment_date); row.createCell(7).setCellValue(task.additional_comment); row.createCell(8).setCellValue(task.last_updated) }; headers.size().times{ sheet.autoSizeColumn(it) }; def os = new ByteArrayOutputStream(); wb.write(os); wb.close(); byte[] excelBytes = os.toByteArray(); e.responseHeaders.add("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"); e.responseHeaders.add("Content-Disposition", "attachment; filename=\"tasks_export.xlsx\""); e.sendResponseHeaders(200, excelBytes.length); e.responseBody.withStream { it.write(excelBytes) } } }
        server.createContext("/api/labels/all") { e -> handleRequest(e, "GET") { def data = labelsManager.getAll(); sendResponse(e, MyJsonOutput.toJson(data), "application/json") } }
        server.createContext("/api/labels/search") { e -> handleRequest(e, "GET") { def params = [:]; if (e.requestURI.query) { params = e.requestURI.query.split('&').collectEntries { param -> def parts = param.split('=', 2); [(URLDecoder.decode(parts[0], "UTF-8")): (parts.length > 1) ? URLDecoder.decode(parts[1], "UTF-8") : ""] } }; def query = params.q; def categoryId = (params.categoryId && params.categoryId.isInteger()) ? params.categoryId as Integer : null; def labels = labelsManager.search(query, categoryId); sendResponse(e, MyJsonOutput.toJson(labels), "application/json") } }
        server.createContext("/api/labels/category") { e -> if (e.requestMethod == "POST") { handleRequest(e, "POST") { def data = new JsonSlurper().parse(e.requestBody); def result = labelsManager.saveCategory(data); sendResponse(e, MyJsonOutput.toJson(result), "application/json") } } else if (e.requestMethod == "DELETE") { handleRequest(e, "DELETE") { def data = new JsonSlurper().parse(e.requestBody); def result = labelsManager.deleteCategory(data.id as int); sendResponse(e, MyJsonOutput.toJson(result), "application/json") } } }
        server.createContext("/api/labels/label") { e -> if (e.requestMethod == "POST") { handleRequest(e, "POST") { def data = new JsonSlurper().parse(e.requestBody); def result = labelsManager.saveLabel(data); sendResponse(e, MyJsonOutput.toJson(result), "application/json") } } else if (e.requestMethod == "DELETE") { handleRequest(e, "DELETE") { def data = new JsonSlurper().parse(e.requestBody); def result = labelsManager.deleteLabel(data.id as int); sendResponse(e, MyJsonOutput.toJson(result), "application/json") } } }
        server.createContext("/api/labels/increment_usage") { e -> handleRequest(e, "POST") { def data = new JsonSlurper().parse(e.requestBody); def result = labelsManager.incrementUsage(data.id as int); sendResponse(e, MyJsonOutput.toJson(result), "application/json") } }
        server.createContext("/api/execute-calls") { e -> handleRequest(e, "POST") { def config = new JsonSlurper().parse(e.requestBody); def logs = multiRequestExecutor.executeAndGetLogs(config); sendResponse(e, logs, "text/plain") } }
        server.createContext("/api/credentials/all") { e -> handleRequest(e, "GET") { sendResponse(e, MyJsonOutput.toJson(historyManager.getAllCredentials()), "application/json") } }
        server.createContext("/api/credentials/get") { e -> handleRequest(e, "GET") { def id = e.getRequestURI().getQuery().split("=")[1]; sendResponse(e, MyJsonOutput.toJson(historyManager.getCredentialById(id as int)), "application/json") } }
        server.createContext("/api/credentials/save") { e -> handleRequest(e, "POST") { def data = new JsonSlurper().parse(e.requestBody); sendResponse(e, MyJsonOutput.toJson(historyManager.saveCredential(data)), "application/json") } }
        server.createContext("/api/credentials/delete") { e -> handleRequest(e, "DELETE") { def data = new JsonSlurper().parse(e.requestBody); sendResponse(e, MyJsonOutput.toJson(historyManager.deleteCredential(data.id as int)), "application/json") } }
        server.createContext("/api/history/all") { e -> handleRequest(e, "GET") { sendResponse(e, MyJsonOutput.toJson(historyManager.getAll()), "application/json") } }
        server.createContext("/api/history/search") { e -> handleRequest(e, "GET") { def params = [:]; if (e.requestURI.query) { params = e.requestURI.query.split('&').collectEntries { param -> def parts = param.split('=', 2); [(URLDecoder.decode(parts[0], "UTF-8")): (parts.length > 1) ? URLDecoder.decode(parts[1], "UTF-8") : ""] } }; sendResponse(e, MyJsonOutput.toJson(historyManager.search(params)), "application/json") } }
        server.createContext("/api/history/group") { e -> handleRequest(e, "GET") { def csName = URLDecoder.decode(e.getRequestURI().getQuery().split("=")[1], "UTF-8"); sendResponse(e, MyJsonOutput.toJson(historyManager.getGroup(csName)), "application/json") } }

        server.start()
    }

    static void handleRequest(HttpExchange e, String m, Closure b){ try { if (e.requestMethod != m) { e.sendResponseHeaders(405, -1); return }; b.call() } catch (Exception x) { println "!!!!---- ОШИБКА НА СЕРВЕРЕ ----!!!!"; println "Ошибка при обработке запроса: ${e.getRequestURI()}"; println "Сообщение: ${x.toString()}"; x.printStackTrace(); println "!!!!----------------------------!!!!"; sendResponse(e, MyJsonOutput.toJson([error:"Ошибка на сервере", message:x.toString()]), "application/json", 500) } }
    static void sendResponse(HttpExchange e, String b, String c, int s = 200){ def bytes = b.getBytes(StandardCharsets.UTF_8); e.responseHeaders.add("Content-Type", "$c; charset=utf-8"); e.sendResponseHeaders(s, bytes.length); e.responseBody.withStream { it.write(bytes) } }
}