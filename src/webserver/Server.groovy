/*
 * Server.groovy - Final Version with SSL Bypass
 */
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpExchange
import groovy.xml.XmlSlurper
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.xml.XmlParser
import groovy.xml.MarkupBuilder
import groovy.sql.Sql
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.SecureRandom
import java.security.cert.X509Certificate

import com.github.javafaker.Faker

import java.util.concurrent.TimeUnit
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.text.SimpleDateFormat
import java.net.URI


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
                // Стандартный JsonOutput — но с отключением экранирования через кастомный writer
                def jsonString = groovy.json.JsonOutput.toJson(data)

                if (pretty) {
                    jsonString = groovy.json.JsonOutput.prettyPrint(jsonString)
                }

                jsonString = jsonString.replaceAll(~'\\\\u([0-9a-fA-F]{4})') { fullMatch, hexCode ->
                    char c = (char) Integer.parseInt(hexCode, 16)
                    return "${c}"
                }

                return jsonString
            }
            else if (format == 'xml') {
                def writer = new StringWriter()
                def builder = new MarkupBuilder(writer)
                builder.root { buildXml(builder, data) }
                return writer.toString()
            }

            return "Неподдерживаемый формат"
        }

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
            def trustAllCerts = [new X509TrustManager() {
                public X509Certificate[] getAcceptedIssuers() { null }
                public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                public void checkServerTrusted(X509Certificate[] certs, String authType) {}
            }] as TrustManager[]
            def sc = SSLContext.getInstance("SSL")
            sc.init(null, trustAllCerts, new SecureRandom())
            def sslParams = sc.getDefaultSSLParameters()
            sslParams.setEndpointIdentificationAlgorithm("")

            def client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2).connectTimeout(Duration.ofSeconds(10))
                    .sslContext(sc).sslParameters(sslParams).build()

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
                def logEntry = [:]; def historyEntry = [cs_name: config.csName, bs_name: config.bsName, interface_o: config.interfaceName, service_url: config.url, request: requestBody, request_headers: MyJsonOutput.toJson(finalHeaders)]
                try { historyEntry.domain_url = new URI(config.url).getHost() } catch (e) { historyEntry.domain_url = "invalid url" }
                try {
                    def requestBuilder = HttpRequest.newBuilder().uri(URI.create(config.url)).timeout(Duration.ofSeconds(20))
                    finalHeaders.each { key, value -> requestBuilder.header(key, value.toString()) }
                    switch (config.method?.toUpperCase() ?: 'POST') {
                        case 'POST': requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody)); break
                        case 'GET': requestBuilder.GET(); break
                        case 'PUT': requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(requestBody)); break
                        case 'PATCH': requestBuilder.method("PATCH", HttpRequest.BodyPublishers.ofString(requestBody)); break
                        case 'DELETE': requestBuilder.DELETE(); break
                        default: requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    }
                    def response = client.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString())
                    logEntry.responseStatus = response.statusCode(); logEntry.responseBody = response.body()
                    historyEntry.response = response.body(); historyEntry.response_headers = MyJsonOutput.toJson(response.headers().map())
                } catch (Exception e) {
                    def sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw)); def stackTrace = sw.toString()
                    logEntry.error = e.getMessage(); logEntry.stackTrace = stackTrace
                    historyEntry.stack_trace = stackTrace
                }
                historyManager.saveCall(historyEntry)
                mainLog.append("--- Запрос ${i}/${numRequests} ---\n").append(formatLogEntry(logEntry, requestBody) + "\n" + "=".repeat(50) + "\n\n")
            }
            mainLog.append("Работа завершена.")
            return mainLog.toString()
        }
        private String generateRequestBody(String template, Map rules, int iteration) { def body = template; rules?.each { key, rule -> def placeholder = "##${key}##"; def value; switch (rule.type) { case 'increment': value = (rule.start + (rule.step * (iteration - 1))); break; case 'random': value = rule.values[new Random().nextInt(rule.values.size())]; break; case 'uuid': value = UUID.randomUUID().toString(); break; case 'current_timestamp': value = new Date().format(rule.format ?: 'yyyy-MM-dd HH:mm:ss'); break; case 'random_number': def min = rule.min ?: 0; def max = rule.max ?: 100; value = new Random().nextInt((max - min) + 1) + min; break; case 'from_list': value = rule.values[(iteration - 1) % rule.values.size()]; break }; if (value != null) { body = body.replace(placeholder, value.toString()) } }; return body }
        private String formatLogEntry(Map entry, String requestBody) { def builder = new StringBuilder(); builder.append("Request Body:\n${requestBody}\n").append("---------------------------------\n").append("Response Status: ${entry.responseStatus ?: 'N/A'}\n").append("Response Body:\n${entry.responseBody ?: 'N/A'}\n"); if (entry.error) { builder.append("Error: ${entry.error}\n") }; if (entry.stackTrace) { builder.append("Stack Trace:\n${entry.stackTrace}\n") }; return builder.toString() }
    }

    static class XsdToWsdlConverter {
        String generate(String xsdContent, String xsdFileName, String serviceType) {
            println "DEBUG: Начало генерации WSDL. Файл: ${xsdFileName}"

            if (!xsdContent || xsdContent.trim().isEmpty()) throw new IllegalArgumentException("XSD пустой")
            if (!xsdFileName) throw new IllegalArgumentException("Нет имени файла")

            try {
                // 1. Очистка от заголовка
                String cleanXsdBody = xsdContent.replaceAll(/<\?xml.*?\?>/, "").trim()

                // 2. Парсинг для поиска корневого элемента
                // Используем XmlSlurper в режиме "без неймспейсов" - это работает лучше всего для игнора префиксов
                def slurper = new XmlSlurper(false, false)
                def xsdParsed = slurper.parseText(cleanXsdBody)

                // Логика поиска: ищем элемент с именем "element" (без учета неймспейса из-за настроек парсера)
                // и наличием атрибута "name" на верхнем уровне
                def rootElementNode

                // Попытка 1: Прямой потомок schema -> element
                // xsdParsed - это уже корневой узел (<schema>)
                if (xsdParsed.name().toLowerCase().contains("schema")) {
                    // Ищем среди детей
                    xsdParsed.childNodes().find { child ->
                        // Проверяем имя узла (GPathResult сложно проверить, используем name())
                        if (child.name() == 'element' && child['@name'] != '') {
                            rootElementNode = child
                            return true
                        }
                        return false
                    }
                }

                // Попытка 2: Глубокий поиск (если структура сложная)
                if (!rootElementNode) {
                    rootElementNode = xsdParsed.depthFirst().find {
                        it.name() == 'element' && it['@name'] != '' && it.parent().name().contains('schema')
                    }
                }

                // Попытка 3 (Фоллбек): Просто первый попавшийся элемент с именем
                if (!rootElementNode) {
                    rootElementNode = xsdParsed.depthFirst().find { it.name() == 'element' && it['@name'] != '' }
                }

                if (!rootElementNode) {
                    // Последний шанс: Regex поиск (если парсер не справился с кривым XML)
                    def matcher = (cleanXsdBody =~ /:element\s+name=["']([^"']+)["']/)
                    if (matcher.find()) {
                        // Нашли через регекс
                        println "DEBUG: Element found via REGEX"
                        // Фейковый объект, чтобы код дальше не падал, нам нужно только имя
                        rootElementNode = [name: { matcher[0][1] }]
                    } else {
                        throw new IllegalStateException("Не найден <element name='...'> в XSD. Проверьте структуру.")
                    }
                }

                // Получаем имя. Если нашли через парсер - берем атрибут, если через хак - вызываем замыкание
                String rootElementName = (rootElementNode instanceof Map) ? rootElementNode.name() : rootElementNode['@name'].text()

                println "DEBUG: Найден корневой элемент: ${rootElementName}"

                // 3. ГЕНЕРАЦИЯ WSDL (Без изменений)
                def baseName = xsdFileName.contains('.') ? xsdFileName.take(xsdFileName.lastIndexOf('.')) : xsdFileName
                def serviceName = "si_${serviceType == 'synchronous' ? 'so' : 'ao'}_${baseName}"
                def targetNamespace = "urn:example.com:${baseName}"
                def requestMsg = "mt_${rootElementName}_RQ"
                def responseMsg = "mt_${rootElementName}_RS"

                def writer = new StringWriter()
                writer.write('<?xml version="1.0" encoding="UTF-8"?>\n')

                def wsdl = new MarkupBuilder(writer)

                wsdl.'wsdl:definitions'(
                        'xmlns:wsdl': "http://schemas.xmlsoap.org/wsdl/",
                        'xmlns:soap': "http://schemas.xmlsoap.org/wsdl/soap/",
                        'xmlns:xsd': "http://www.w3.org/2001/XMLSchema",
                        'xmlns:tns': targetNamespace,
                        name: serviceName,
                        targetNamespace: targetNamespace
                ) {
                    'wsdl:types' {
                        'xsd:schema'(targetNamespace: targetNamespace) {
                            wsdl.mkp.yieldUnescaped(cleanXsdBody)
                        }
                    }

                    'wsdl:message'(name: requestMsg) {
                        'wsdl:part'(name: 'parameters', element: "tns:${rootElementName}")
                    }

                    if (serviceType == 'synchronous') {
                        'wsdl:message'(name: responseMsg) {
                            'wsdl:part'(name: 'parameters', element: "tns:${rootElementName}Response")
                        }
                    }

                    'wsdl:portType'(name: serviceName) {
                        'wsdl:operation'(name: serviceName) {
                            'wsdl:input'(message: "tns:${requestMsg}")
                            if (serviceType == 'synchronous') {
                                'wsdl:output'(message: "tns:${responseMsg}")
                            }
                        }
                    }

                    'wsdl:binding'(name: "${serviceName}Binding", type: "tns:${serviceName}") {
                        'soap:binding'(style: 'document', transport: 'http://schemas.xmlsoap.org/soap/http')
                        'wsdl:operation'(name: serviceName) {
                            'soap:operation'(soapAction: "http://sap.com/xi/WebService/soap1.1")
                            'wsdl:input' { 'soap:body'(use: 'literal') }
                            if (serviceType == 'synchronous') {
                                'wsdl:output' { 'soap:body'(use: 'literal') }
                            }
                        }
                    }

                    'wsdl:service'(name: "${serviceName}Service") {
                        'wsdl:port'(name: 'HTTP_Port', binding: "tns:${serviceName}Binding") {
                            'soap:address'(location: "http://localhost:8080/soap/${serviceName}Service")
                        }
                    }
                }

                String resultXml = writer.toString()

                if (serviceType == 'synchronous') {
                    String fakeResponseXsd = """<xsd:element name="${rootElementName}Response"><xsd:complexType><xsd:sequence><xsd:element name="Response" type="xsd:string"/></xsd:sequence></xsd:complexType></xsd:element>"""
                    // Пробуем разные варианты вставки
                    if (resultXml.contains("</xsd:schema>")) resultXml = resultXml.replace("</xsd:schema>", fakeResponseXsd + "</xsd:schema>")
                    else if (resultXml.contains("</schema>")) resultXml = resultXml.replace("</schema>", fakeResponseXsd + "</schema>")
                    else if (resultXml.contains("</xs:schema>")) resultXml = resultXml.replace("</xs:schema>", fakeResponseXsd + "</xs:schema>")
                }

                return resultXml

            } catch (Exception e) {
                println "ERROR XSD Convert: ${e.message}"
                e.printStackTrace()
                throw new RuntimeException("Ошибка: ${e.message}", e)
            }
        }
    }

    static class LabelsManager {
        private Sql sql
        LabelsManager(Sql sql) { this.sql = sql }
        def getAll() { def categories = sql.rows("SELECT * FROM labels_categories ORDER BY display_order ASC, name ASC"); def labels = sql.rows("SELECT * FROM labels ORDER BY usage_count DESC, date_created DESC"); return [categories: categories, labels: labels] }
        def search(String query, Integer categoryId) {
            def labelsFromDb = categoryId != null ? sql.rows("SELECT * FROM labels WHERE category_id = ?", [categoryId]) : sql.rows("SELECT * FROM labels")
            def filteredLabels = labelsFromDb
            if (query && !query.trim().isEmpty()) {
                def normalizedQuery = query.trim().toLowerCase()
                filteredLabels = labelsFromDb.findAll { label -> (label.title?.toLowerCase()?.contains(normalizedQuery)) || (label.content?.toLowerCase()?.contains(normalizedQuery)) }
            }
            return filteredLabels.sort { a, b -> (b.usage_count <=> a.usage_count) ?: (b.date_created <=> a.date_created) }
        }
        def saveCategory(Map data) { if (data.id) sql.execute("UPDATE labels_categories SET name=?, display_order=? WHERE id=?", [data.name, data.display_order ?: 99, data.id]) else sql.execute("INSERT INTO labels_categories (name, display_order) VALUES (?, ?)", [data.name, data.display_order ?: 99]); return [status: 'OK'] }
        def deleteCategory(int id) { sql.execute("DELETE FROM labels WHERE category_id=?", [id]); sql.execute("DELETE FROM labels_categories WHERE id=?", [id]); return [status: 'OK'] }
        def saveLabel(Map data) { if (data.id) sql.execute("UPDATE labels SET title=?, content=?, category_id=? WHERE id=?", [data.title, data.content, data.category_id, data.id]) else sql.execute("INSERT INTO labels (title, content, category_id) VALUES (?, ?, ?)", [data.title, data.content, data.category_id]); return [status: 'OK'] }
        def deleteLabel(int id) { sql.execute("DELETE FROM labels WHERE id=?", [id]); return [status: 'OK'] }
        def incrementUsage(int id) { sql.execute("UPDATE labels SET usage_count = usage_count + 1 WHERE id=?", [id]); return [status: 'OK'] }
    }

    static class CallHistoryManager {
        private Sql sql
        CallHistoryManager(Sql sql) { this.sql = sql }
        def saveCall(Map data) {
            def dbFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
            def formattedDate = dbFormatter.format(new Date())
            sql.execute("INSERT INTO call_history (cs_name, bs_name, interface_o, request, response, service_url, domain_url, request_headers, response_headers, stack_trace, call_date) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    [data.cs_name, data.bs_name, data.interface_o, data.request, data.response, data.service_url, data.domain_url, data.request_headers, data.response_headers, data.stack_trace, formattedDate]) }

        def getAll() { return sql.rows("SELECT * FROM call_history ORDER BY id DESC") }
        def getGroup(String csName) { return sql.rows("SELECT * FROM call_history WHERE cs_name = ? ORDER BY id ASC", [csName]) }
        def search(Map params) {
            def qb = new StringBuilder("SELECT * FROM call_history"); def wc = []; def qp = [];
            params.each { k,v -> if(v&&!v.trim().isEmpty()){ def nv = "%${v.trim().toLowerCase()}%";
                switch(k){
                    case 'csName': wc.add("LOWER(cs_name) LIKE ?"); qp.add(nv); break;
                    case 'bsName': wc.add("LOWER(bs_name) LIKE ?"); qp.add(nv); break;
                    case 'interfaceName': wc.add("LOWER(interface_o) LIKE ?"); qp.add(nv); break;
                    case 'url': wc.add("LOWER(service_url) LIKE ?"); qp.add(nv); break;
                    case 'content': wc.add("(LOWER(request) LIKE ? OR LOWER(response) LIKE ? OR LOWER(stack_trace) LIKE ?)");
                    case 'dateFrom': wc.add("datetime(call_date) >= datetime(?)"); qp.add("${v} 00:00:00"); break;
                    case 'dateTo':   wc.add("datetime(call_date) <= datetime(?)"); qp.add("${v} 23:59:59"); break;
                        3.times{qp.add(nv)}; break;}}}; if (wc) qb.append(" WHERE ").append(wc.join(" AND "));
            qb.append(" ORDER BY id DESC"); return sql.rows(qb.toString(), qp)}

        def getAllCredentials() { return sql.rows("SELECT id, name FROM credentials ORDER BY name ASC") }
        def getCredentialById(int id) { return sql.firstRow("SELECT * FROM credentials WHERE id = ?", [id]) }
        def saveCredential(Map data) { if (data.id) sql.execute("UPDATE credentials SET name=?, login=?, password=? WHERE id=?", [data.name, data.login, data.password, data.id]) else sql.execute("INSERT INTO credentials (name, login, password) VALUES (?,?,?)", [data.name, data.login, data.password]); return [status: 'OK'] }
        def deleteCredential(int id) { sql.execute("DELETE FROM credentials WHERE id=?", [id]); return [status: 'OK']}
    }

    static void main(String[] args){
        def dbFile = 'editor.db'; def sql = Sql.newInstance("jdbc:sqlite:${dbFile}", "org.sqlite.JDBC")
        sql.execute'''CREATE TABLE IF NOT EXISTS saved_documents (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, content TEXT, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)'''
        // 1. Tasks Table Update for 'planned_dev_date'
        sql.execute'''CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, user TEXT, task_url TEXT, task_number TEXT, stage TEXT, status TEXT, deployment_date TEXT, planned_dev_date TEXT, additional_comment TEXT, contact_person TEXT, spec_url TEXT, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)'''

        // Migration for existing tables without new columns
        try { sql.execute("ALTER TABLE tasks ADD COLUMN planned_dev_date TEXT") } catch(e){}
        try { sql.execute("ALTER TABLE tasks ADD COLUMN contact_person TEXT") } catch(e){}
        try { sql.execute("ALTER TABLE tasks ADD COLUMN spec_url TEXT") } catch(e){}

        sql.execute'''CREATE TABLE IF NOT EXISTS task_settings (key TEXT PRIMARY KEY, value TEXT)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS labels_categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, display_order INTEGER DEFAULT 99)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS labels (id INTEGER PRIMARY KEY AUTOINCREMENT, category_id INTEGER, content TEXT, usage_count INTEGER DEFAULT 0, date_created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, title TEXT, FOREIGN KEY(category_id) REFERENCES labels_categories(id))'''
        sql.execute'''CREATE TABLE IF NOT EXISTS credentials (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, login TEXT, password TEXT)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS call_history (id INTEGER PRIMARY KEY AUTOINCREMENT, cs_name TEXT, bs_name TEXT, interface_o TEXT, request TEXT, response TEXT, service_url TEXT, domain_url TEXT, request_headers TEXT, response_headers TEXT, stack_trace TEXT, call_date DATETIME)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS saved_xsd (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, content TEXT, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)'''

        // Интеграционные потоки ===
        sql.execute('''
            CREATE TABLE IF NOT EXISTS integration_flows (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                sender TEXT NOT NULL,
                receiver TEXT NOT NULL,
                order_num INTEGER NOT NULL,
                FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE
            )
        ''')

        // Индекс для ускорения выборки по задаче
        try { sql.execute("CREATE INDEX IF NOT EXISTS idx_flows_task_id ON integration_flows(task_id)") } catch(Exception e) {}
        // === Справочник систем ===
        sql.execute('''
            CREATE TABLE IF NOT EXISTS systems (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                sid TEXT NOT NULL UNIQUE,
                description TEXT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        ''')

        // Заполним стартовыми данными (если пусто)
        if (sql.rows("SELECT COUNT(*) as c FROM systems").first().c == 0) {
            sql.executeInsert('''
                INSERT INTO systems (sid, description) VALUES 
                ('BS_EFO', 'Единое Окно'),
                ('BS_PSB_ONLINE', 'Онлайн'),
                ('BS_ATHENA_E', 'Афина Восток'),
                ('BS_ATHENA_W', 'Афина Запад'),
                ('BS_ATHENA_M', 'Афина Москва'),
                ('FACTOR', 'Фактор'),
                ('BS_NAUMEN', 'Наумен'),
                ('BS_DBOCORP', 'ДБО Корп')
            ''')
        }
        // === Детали интеграционных потоков ===
        sql.execute('''
            CREATE TABLE IF NOT EXISTS flow_details (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                flow_order INTEGER NOT NULL,
                protocol TEXT,
                format TEXT,
                connection_type TEXT,
                file_path TEXT,
                description TEXT,
                operation_name TEXT,
                service_description TEXT,
                UNIQUE(task_id, flow_order),
                FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE
            )
        ''')

        try { sql.firstRow("SELECT usage_count FROM labels LIMIT 1") } catch (Exception e) { sql.execute("ALTER TABLE labels ADD COLUMN usage_count INTEGER DEFAULT 0") }
        try { sql.firstRow("SELECT bs_name FROM call_history LIMIT 1") } catch (Exception e) { sql.execute("ALTER TABLE call_history ADD COLUMN bs_name TEXT") }
        try { sql.firstRow("SELECT spec_url FROM tasks LIMIT 1") } catch (Exception e) { sql.execute("ALTER TABLE tasks ADD COLUMN spec_url TEXT") }
        try { sql.firstRow("SELECT contact_person FROM tasks LIMIT 1") } catch (Exception e) { sql.execute("ALTER TABLE tasks ADD COLUMN contact_person TEXT") }
        try { sql.firstRow("SELECT title FROM labels LIMIT 1") } catch (Exception e) { sql.execute("ALTER TABLE labels ADD COLUMN title TEXT") }
        try { sql.firstRow("SELECT planned_dev_date FROM tasks LIMIT 1") } catch (Exception e) { sql.execute("ALTER TABLE tasks ADD COLUMN planned_dev_date TEXT") }


        def server = HttpServer.create(new InetSocketAddress(8080), 0); println "Сервер запущен на http://localhost:8080"
        def historyManager = new CallHistoryManager(sql); def converter = new DataConverter(); def excelExporter = new ExcelExporter(); def exampleGenerator = new ExampleGenerator(); def multiRequestExecutor = new MultiRequestExecutor(historyManager); def xsdToWsdlConverter = new XsdToWsdlConverter(); def labelsManager = new LabelsManager(sql)
        server.createContext("/", { e -> try { def path = e.getRequestURI().getPath() == "/" ? "/index.html" : e.getRequestURI().getPath(); def file = new File(path.substring(1)); if (file.exists()) { def ct = "text/html; charset=utf-8"; if (path.endsWith(".css")) ct = "text/css; charset=utf-8"; else if (path.endsWith(".js")) ct = "application/javascript; charset=utf-8"; def bytes = file.readBytes(); e.responseHeaders.add("Content-Type",ct);e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.getResponseBody().close()} else {sendResponse(e,"Not Found", "text/plain", 404)}} catch(Exception ex){sendResponse(e, ex.message, "text/plain", 500)}})
        server.createContext("/api/parse"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,MyJsonOutput.toJson(converter.parse(d.text,d.format)),"application/json")}}
        server.createContext("/api/serialize"){e->handleRequest(e,"POST"){def s=new JsonSlurper().parse(e.requestBody);def p=e.requestURI.query?.split('&').collectEntries{[(it.split('=')[0]):URLDecoder.decode(it.split('=')[1],"UTF-8")]};sendResponse(e,converter.serialize(s,p.format?:'json',p.pretty.toBoolean()),"text/plain")}}
        server.createContext("/api/fetch-url"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,new URL(d.url).text,"text/plain")}}
        server.createContext("/api/convert"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,converter.serialize(converter.parse(d.text,d.from),d.to,true),"text/plain")}}
        server.createContext("/api/generate-schema"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,MyJsonOutput.prettyPrint(MyJsonOutput.toJson(converter.generateJsonSchema(d))),"application/json")}}
        server.createContext("/api/export-to-excel"){e->handleRequest(e,"POST"){String b=e.requestBody.text;def jT=b.startsWith("json=")?URLDecoder.decode(b.substring(5),"UTF-8"):b;byte[]x=excelExporter.createExcelFromStructure(new JsonSlurper().parseText(jT));e.responseHeaders.add("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");e.responseHeaders.add("Content-Disposition","attachment; filename=\"json_structure.xlsx\"");e.sendResponseHeaders(200,x.length);e.responseBody.withStream{it.write(x)}}}
        server.createContext("/api/xml-to-excel"){e->handleRequest(e,"POST"){String b=e.requestBody.text;def xT=b.startsWith("xml=")?URLDecoder.decode(b.substring(4),"UTF-8"):b;byte[]x=excelExporter.createExcelFromStructure(converter.parse(xT,"xml"));e.responseHeaders.add("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");e.responseHeaders.add("Content-Disposition","attachment; filename=\"xml_structure.xlsx\"");e.sendResponseHeaders(200,x.length);e.responseBody.withStream{it.write(x)}}}
        server.createContext("/api/generate-example"){e->handleRequest(e,"POST"){def schemaText=e.requestBody.text;sendResponse(e,exampleGenerator.generateExampleFromJsonSchema(schemaText),"application/json")}}
        server.createContext("/api/documents"){e->if(e.requestMethod=="GET")handleRequest(e,"GET"){sendResponse(e,MyJsonOutput.toJson(sql.rows("SELECT id, name FROM saved_documents ORDER BY name ASC")),"application/json")}else if(e.requestMethod=="POST")handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);def doc=sql.firstRow("SELECT id FROM saved_documents WHERE name=?",[d.name]);if(doc){sql.execute("UPDATE saved_documents SET content=?,last_updated=CURRENT_TIMESTAMP WHERE id=?",[d.content,doc.id])}else{sql.execute("INSERT INTO saved_documents (name,content) VALUES (?,?)",[d.name,d.content])};sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}
        server.createContext("/api/documents/"){e->def id=e.requestURI.path.split('/').last();if(e.requestMethod=="GET")handleRequest(e,"GET"){def d=sql.firstRow("SELECT content FROM saved_documents WHERE id=?",[id]);if(d)sendResponse(e,d.content,"text/plain")else sendResponse(e,"", "text/plain", 404)}else if(e.requestMethod=="DELETE")handleRequest(e,"DELETE"){sql.execute("DELETE FROM saved_documents WHERE id=?",[id]);sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}
        server.createContext("/api/xsd-to-wsdl"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);sendResponse(e,xsdToWsdlConverter.generate(d.xsdContent,d.xsdFileName,d.serviceType),"application/xml")}}
        server.createContext("/api/tasks/settings"){e->if(e.requestMethod=="GET")handleRequest(e,"GET"){def s=[:];sql.eachRow("SELECT key,value FROM task_settings"){r->s[r.key]=r.value};sendResponse(e,MyJsonOutput.toJson(s),"application/json")}else if(e.requestMethod=="POST")handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);d.each{k,v->sql.execute("INSERT OR REPLACE INTO task_settings (key, value) VALUES (?, ?)",[k, v])};sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}
        server.createContext("/api/tasks"){e->if(e.requestMethod=="GET")handleRequest(e,"GET"){def t=sql.rows("SELECT * FROM tasks ORDER BY last_updated DESC");sendResponse(e,MyJsonOutput.toJson(t),"application/json")}else if(e.requestMethod=="POST")handleRequest(e,"POST"){def t=new JsonSlurper().parse(e.requestBody);if(t.id)sql.execute("UPDATE tasks SET user=?,task_url=?,task_number=?,stage=?,status=?,deployment_date=?,planned_dev_date=?,additional_comment=?,spec_url=?,contact_person=?,last_updated=CURRENT_TIMESTAMP WHERE id=?",[t.user,t.task_url,t.task_number,t.stage,t.status,t.deployment_date,t.planned_dev_date,t.additional_comment,t.spec_url,t.contact_person,t.id])else sql.execute("INSERT INTO tasks (user,task_url,task_number,stage,status,deployment_date,planned_dev_date,additional_comment,spec_url,contact_person) VALUES (?,?,?,?,?,?,?,?,?,?)",[t.user,t.task_url,t.task_number,t.stage,t.status,t.deployment_date,t.planned_dev_date,t.additional_comment,t.spec_url,t.contact_person]);sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}else if(e.requestMethod=="DELETE")handleRequest(e,"DELETE"){def d=new JsonSlurper().parse(e.requestBody);sql.execute("DELETE FROM tasks WHERE id = ?",[d.id]);sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}
        server.createContext("/api/tasks/send"){e->handleRequest(e,"POST"){def s=[:];sql.eachRow("SELECT key,value FROM task_settings"){r->s[r.key]=r.value};def t=sql.rows("SELECT * FROM tasks");String auth="${s.target_user}:${s.target_password?:''}";String enc=Base64.encoder.encodeToString(auth.bytes);def c=HttpClient.newHttpClient();def req=HttpRequest.newBuilder().uri(URI.create(s.target_url)).header("Content-Type","application/json").header("Authorization","Basic "+enc).POST(HttpRequest.BodyPublishers.ofString(MyJsonOutput.toJson(t))).build();def res=c.send(req,HttpResponse.BodyHandlers.ofString());sendResponse(e,MyJsonOutput.toJson([statusCode:res.statusCode(),responseBody:res.body()]),"application/json")}}
        server.createContext("/api/tasks/export"){e->handleRequest(e,"GET"){Workbook wb=new XSSFWorkbook();Sheet s=wb.createSheet("Задачи");Row hr=s.createRow(0);def h=["ID","Пользователь","Номер задачи","URL задачи","Этап","Статус","Дата установки(PROD)","Плановая дата DEV","Комментарий","Последнее обновление","Спецификация","Контакт"];h.eachWithIndex{hd,i->hr.createCell(i).setCellValue(hd)};def t=sql.rows("SELECT * FROM tasks ORDER BY id DESC");t.eachWithIndex{tk,i->Row r=s.createRow(i+1);r.createCell(0).setCellValue(tk.id.toString());r.createCell(1).setCellValue(tk.user);r.createCell(2).setCellValue(tk.task_number);r.createCell(3).setCellValue(tk.task_url);r.createCell(4).setCellValue(tk.stage);r.createCell(5).setCellValue(tk.status);r.createCell(6).setCellValue(tk.deployment_date);r.createCell(7).setCellValue(tk.planned_dev_date);r.createCell(8).setCellValue(tk.additional_comment);r.createCell(9).setCellValue(tk.last_updated.toString());r.createCell(10).setCellValue(tk.spec_url);r.createCell(11).setCellValue(tk.contact_person)};h.size().times{s.autoSizeColumn(it)};def os=new ByteArrayOutputStream();wb.write(os);wb.close();byte[] b=os.toByteArray();e.responseHeaders.add("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");e.responseHeaders.add("Content-Disposition","attachment; filename=\"tasks_export.xlsx\"");e.sendResponseHeaders(200,b.length);e.responseBody.withStream{it.write(b)}}}
        server.createContext("/api/labels/all"){e->handleRequest(e,"GET"){sendResponse(e,MyJsonOutput.toJson(labelsManager.getAll()),"application/json")}}

        server.createContext("/api/labels/search"){ e ->
            handleRequest(e, "GET") {
                // Парсинг параметров безопасный к отсутствующим значениям
                def p = [:]
                if (e.requestURI.query) {
                    e.requestURI.query.split('&').each { pa ->
                        def parts = pa.split('=', 2)
                        def key = URLDecoder.decode(parts[0], "UTF-8")
                        def val = (parts.length > 1) ? URLDecoder.decode(parts[1], "UTF-8") : ""
                        p[key] = val
                    }
                }

                // Безопасное преобразование categoryId: если строка пустая или null -> отправляем null
                Integer catId = (p.categoryId && p.categoryId.isInteger()) ? p.categoryId.toInteger() : null

                sendResponse(e, MyJsonOutput.toJson(labelsManager.search(p.q, catId)), "application/json")
            }
        }

        server.createContext("/api/labels/category"){e->if(e.requestMethod=="POST")handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);sendResponse(e,MyJsonOutput.toJson(labelsManager.saveCategory(d)),"application/json")}else if(e.requestMethod=="DELETE")handleRequest(e,"DELETE"){def d=new JsonSlurper().parse(e.requestBody);sendResponse(e,MyJsonOutput.toJson(labelsManager.deleteCategory(d.id as int)),"application/json")}}
        server.createContext("/api/labels/label"){e->if(e.requestMethod=="POST")handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);sendResponse(e,MyJsonOutput.toJson(labelsManager.saveLabel(d)),"application/json")}else if(e.requestMethod=="DELETE")handleRequest(e,"DELETE"){def d=new JsonSlurper().parse(e.requestBody);sendResponse(e,MyJsonOutput.toJson(labelsManager.deleteLabel(d.id as int)),"application/json")}}
        server.createContext("/api/labels/increment_usage"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);sendResponse(e,MyJsonOutput.toJson(labelsManager.incrementUsage(d.id as int)),"application/json")}}
        server.createContext("/api/execute-calls"){e->handleRequest(e,"POST"){def c=new JsonSlurper().parse(e.requestBody);sendResponse(e,multiRequestExecutor.executeAndGetLogs(c),"text/plain")}}
        server.createContext("/api/credentials/all"){e->handleRequest(e,"GET"){sendResponse(e,MyJsonOutput.toJson(historyManager.getAllCredentials()),"application/json")}}
        server.createContext("/api/credentials/get"){e->handleRequest(e,"GET"){def id=e.getRequestURI().getQuery().split("=")[1];sendResponse(e,MyJsonOutput.toJson(historyManager.getCredentialById(id as int)),"application/json")}}
        server.createContext("/api/credentials/save"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);sendResponse(e,MyJsonOutput.toJson(historyManager.saveCredential(d)),"application/json")}}
        server.createContext("/api/credentials/delete"){e->handleRequest(e,"DELETE"){def d=new JsonSlurper().parse(e.requestBody);sendResponse(e,MyJsonOutput.toJson(historyManager.deleteCredential(d.id as int)),"application/json")}}
        server.createContext("/api/history/all"){e->handleRequest(e,"GET"){sendResponse(e,MyJsonOutput.toJson(historyManager.getAll()),"application/json")}}
        server.createContext("/api/history/search"){e->handleRequest(e,"GET"){def p=e.requestURI.query.split('&').collectEntries{pa->def parts=pa.split('=',2);[(URLDecoder.decode(parts[0],"UTF-8")):(parts.length>1)?URLDecoder.decode(parts[1],"UTF-8"):""]};sendResponse(e,MyJsonOutput.toJson(historyManager.search(p)),"application/json")}}
        server.createContext("/api/history/group"){e->if(e.requestMethod=="GET")handleRequest(e,"GET"){def cs=URLDecoder.decode(e.getRequestURI().getQuery().split("=")[1],"UTF-8");sendResponse(e,MyJsonOutput.toJson(historyManager.getGroup(cs)),"application/json")}else if(e.requestMethod=="DELETE")handleRequest(e,"DELETE"){def d=new JsonSlurper().parse(e.requestBody);sql.execute("DELETE FROM call_history WHERE cs_name=?",[d.csName]);sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}
        server.createContext("/api/history/item/"){e->handleRequest(e,"DELETE"){def id=e.requestURI.path.split('/').last();sql.execute("DELETE FROM call_history WHERE id=?",[id as int]);sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}

        // --- НОВЫЙ БЛОК API ДЛЯ XSD-КОНСТРУКТОРА ---
        server.createContext("/api/xsd") { e -> if (e.requestMethod == "GET") handleRequest(e,"GET"){def docs=sql.rows("SELECT id, name FROM saved_xsd ORDER BY name ASC");sendResponse(e, MyJsonOutput.toJson(docs),"application/json")} else if (e.requestMethod=="POST")handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);def doc=sql.firstRow("SELECT id FROM saved_xsd WHERE name=?",[d.name]);if(doc)sql.execute("UPDATE saved_xsd SET content=?, last_updated=CURRENT_TIMESTAMP WHERE id=?",[d.content,doc.id])else sql.execute("INSERT INTO saved_xsd (name,content) VALUES (?,?)",[d.name,d.content]);sendResponse(e, MyJsonOutput.toJson([status:"OK"]),"application/json")}}
        server.createContext("/api/xsd/"){e->def id=e.requestURI.path.split('/').last();if(e.requestMethod=="GET")handleRequest(e,"GET"){def d=sql.firstRow("SELECT content FROM saved_xsd WHERE id=?",[id]);if(d)sendResponse(e,d.content,"application/json")else sendResponse(e,"{}","application/json",404)}else if(e.requestMethod=="DELETE")handleRequest(e,"DELETE"){sql.execute("DELETE FROM saved_xsd WHERE id=?",[id]);sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}

        // === API: Получить все потоки по задаче ===
        server.createContext("/api/flows") { e ->
            if (e.requestMethod == "GET") {
                handleRequest(e, "GET") {
                    def query = e.requestURI.query
                    if (!query || !query.contains("task_id=")) {
                        sendResponse(e, MyJsonOutput.toJson([error: "Параметр task_id обязателен"]), "application/json", 400)
                        return
                    }
                    def taskId = query.split("task_id=")[1].split("&")[0]
                    def flows = sql.rows("SELECT id, sender, receiver, order_num FROM integration_flows WHERE task_id = ? ORDER BY order_num ASC", [taskId as Integer])
                    sendResponse(e, MyJsonOutput.toJson(flows), "application/json")
                }
            }
            else if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    def data = new JsonSlurper().parse(e.requestBody)
                    def taskId = data.task_id
                    def newFlows = data.flows as List<Map>

                    if (!taskId || !(newFlows instanceof List)) {
                        sendResponse(e, MyJsonOutput.toJson([error: "Неверный формат: нужен task_id и flows[]"]), "application/json", 400)
                        return
                    }

                    // Удаляем старые потоки
                    sql.execute("DELETE FROM integration_flows WHERE task_id = ?", [taskId as Integer])

                    // Вставляем новые с правильной нумерацией
                    newFlows.eachWithIndex { flow, idx ->
                        def sender = flow.sender?.toString() ?: ""
                        def receiver = flow.receiver?.toString() ?: ""
                        if (sender && receiver) {
                            sql.execute("""
                                INSERT INTO integration_flows (task_id, sender, receiver, order_num)
                                VALUES (?, ?, ?, ?)
                            """, [taskId as Integer, sender, receiver, idx + 1])
                        }
                    }

                    sendResponse(e, MyJsonOutput.toJson([status: "OK", saved: newFlows.size()]), "application/json")
                }
            }
        }

        // === API: Справочник систем ===
        server.createContext("/api/systems") { e ->
            if (e.requestMethod == "GET") {
                handleRequest(e, "GET") {
                    def systems = sql.rows("SELECT id, sid, description FROM systems ORDER BY sid ASC")
                    sendResponse(e, MyJsonOutput.toJson(systems), "application/json")
                }
            }
            else if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    def data = new JsonSlurper().parse(e.requestBody)
                    def sid = data.sid?.trim()
                    def desc = data.description?.trim() ?: ""
                    if (!sid) {
                        sendResponse(e, MyJsonOutput.toJson([error: "SID обязателен"]), "application/json", 400)
                        return
                    }
                    try {
                        if (data.id) {
                            sql.execute("UPDATE systems SET sid=?, description=? WHERE id=?", [sid, desc, data.id as Integer])
                        } else {
                            sql.execute("INSERT INTO systems (sid, description) VALUES (?, ?)", [sid, desc])
                        }
                        sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                    } catch (Exception ex) {
                        sendResponse(e, MyJsonOutput.toJson([error: "Такая система уже существует"]), "application/json", 400)
                    }
                }
            }
            else if (e.requestMethod == "DELETE") {
                handleRequest(e, "DELETE") {
                    def data = new JsonSlurper().parse(e.requestBody)
                    sql.execute("DELETE FROM systems WHERE id = ?", [data.id as Integer])
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
            }
        }
        // === API: Детали потока ===
        server.createContext("/api/flow-details") { e ->
            if (e.requestMethod == "GET") {
                handleRequest(e, "GET") {
                    def taskId = e.requestURI.query?.split('&')?.find { it.startsWith('task_id=') }?.split('=')?.getAt(1)
                    def flowOrder = e.requestURI.query?.split('&')?.find { it.startsWith('flow_order=') }?.split('=')?.getAt(1)
                    if (!taskId || !flowOrder) {
                        sendResponse(e, MyJsonOutput.toJson([error: "Нужны task_id и flow_order"]), "application/json", 400)
                        return
                    }
                    def detail = sql.firstRow("SELECT * FROM flow_details WHERE task_id = ? AND flow_order = ?", [taskId as Integer, flowOrder as Integer])
                    sendResponse(e, MyJsonOutput.toJson(detail ?: [:]), "application/json")
                }
            }
            else if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    def data = new JsonSlurper().parse(e.requestBody)
                    def taskId = data.task_id as Integer
                    def flowOrder = data.flow_order as Integer

                    def existing = sql.firstRow("SELECT id FROM flow_details WHERE task_id = ? AND flow_order = ?", [taskId, flowOrder])
                    if (existing) {
                        sql.executeUpdate("""UPDATE flow_details SET 
                            protocol=?, format=?, connection_type=?, file_path=?, description=?,
                            operation_name=?, service_description=?
                            WHERE task_id=? AND flow_order=?""",
                                [data.protocol, data.format, data.connection_type, data.file_path, data.description,
                                 data.operation_name, data.service_description, taskId, flowOrder])
                    } else {
                        sql.executeInsert("""INSERT INTO flow_details 
                            (task_id, flow_order, protocol, format, connection_type, file_path, description, operation_name, service_description)
                            VALUES (?,?,?,?,?,?,?,?,?)""",
                                [taskId, flowOrder, data.protocol, data.format, data.connection_type, data.file_path, data.description,
                                 data.operation_name, data.service_description])
                    }
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
            }
        }

        // --- КОНЕЦ НОВОГО БЛОКА ---

        server.start()
    }

    static void handleRequest(HttpExchange e, String m, Closure b){ try { if (e.requestMethod != m) { e.sendResponseHeaders(405, -1); return }; b.call() } catch (Exception x) { println "!!!!---- ОШИБКА НА СЕРВЕРЕ ----!!!!"; println "Ошибка при обработке запроса: ${e.getRequestURI()}"; println "Сообщение: ${x.toString()}"; x.printStackTrace(); println "!!!!----------------------------!!!!"; sendResponse(e, MyJsonOutput.toJson([error:"Ошибка на сервере", message:x.toString()]), "application/json", 500) } }
    static void sendResponse(HttpExchange e, String b, String c, int s = 200){ def bytes = b.getBytes(StandardCharsets.UTF_8); e.responseHeaders.add("Content-Type", "$c; charset=utf-8"); e.sendResponseHeaders(s, bytes.length); e.responseBody.withStream { it.write(bytes) } }
}