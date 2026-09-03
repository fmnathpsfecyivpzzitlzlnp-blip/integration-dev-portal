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

import java.util.concurrent.TimeUnit
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.text.SimpleDateFormat
import java.net.URI

class Server {
    static class CryptoUtil {
        private static final String ALGO = "AES"
        private static final byte[] KEY = "DevToolsSecret12".getBytes("UTF-8")

        static String encrypt(String plainText) {
            if (!plainText) return plainText
            def cipher = javax.crypto.Cipher.getInstance(ALGO)
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(KEY, ALGO))
            return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes("UTF-8")))
        }

        static String decrypt(String encryptedText) {
            if (!encryptedText) return encryptedText
            try {
                def cipher = javax.crypto.Cipher.getInstance(ALGO)
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(KEY, ALGO))
                return new String(cipher.doFinal(java.util.Base64.getDecoder().decode(encryptedText)), "UTF-8")
            } catch (Exception e) {
                return encryptedText
            }
        }
    }

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
                if (pretty) { jsonString = groovy.json.JsonOutput.prettyPrint(jsonString) }
                jsonString = jsonString.replaceAll(~'\\\\u([0-9a-fA-F]{4})') { fullMatch, hexCode ->
                    char c = (char) Integer.parseInt(hexCode, 16)
                    return "${c}"
                }
                return jsonString
            }
            else if (format == 'xml') {
                def writer = new StringWriter()
                def builder = new MarkupBuilder(writer)
                builder.root {
                    if (data instanceof List) {
                        data.each { item ->
                            builder.item { buildXml(builder, item) }
                        }
                    } else {
                        buildXml(builder, data)
                    }
                }
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

    static class WsdlExcelExporter {
        private String getDoc(node) {
            def ann = node.children().find { it.name() == 'annotation' || it.name().endsWith(':annotation') }
            if (ann) {
                def doc = ann.children().find { it.name() == 'documentation' || it.name().endsWith(':documentation') }
                if (doc) return doc.text()
            }
            return ""
        }

        byte[] createExcel(String wsdlText) {
            def xml = new XmlSlurper(false, false).parseText(wsdlText.replaceAll(/<\?xml.*?\?>/, "").trim())
            List<Map> rows = []

            def complexTypes = xml.depthFirst().findAll { it.name() == 'complexType' || it.name().endsWith(':complexType') }

            complexTypes.each { ct ->
                String ctName = ct['@name'].text()
                if (!ctName) ctName = ct.parent()['@name'].text() ?: "InlineType"

                rows.add(["Уровень": "1", "Наименование": ctName, "Категория": "Structure", "Тип": "", "Повторение": "", "Описание": getDoc(ct)])

                int index = 1
                ct.depthFirst().findAll { it.name() == 'element' || it.name().endsWith(':element') }.each { el ->
                    String elName = el['@name'].text()
                    if (elName) {
                        String elType = el['@type'].text()
                        String min = el['@minOccurs'].text() ?: "1"
                        String max = el['@maxOccurs'].text() ?: "1"
                        if (max == "unbounded") max = "n"

                        rows.add([
                                "Уровень": "1.${index}",
                                "Наименование": elName,
                                "Категория": "Element",
                                "Тип": elType,
                                "Повторение": "${min}..${max}",
                                "Описание": getDoc(el)
                        ])
                        index++
                    }
                }
            }

            Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()
            Sheet s = wb.createSheet("WSDL Structure")
            Row hr = s.createRow(0)
            def hs = ["Уровень", "Наименование", "Категория", "Тип", "Повторение", "Описание"]
            hs.eachWithIndex{ h, i -> hr.createCell(i).setCellValue(h) }

            rows.eachWithIndex { rd, i ->
                Row r = s.createRow(i+1)
                r.createCell(0).setCellValue(rd["Уровень"])
                r.createCell(1).setCellValue(rd["Наименование"])
                r.createCell(2).setCellValue(rd["Категория"])
                r.createCell(3).setCellValue(rd["Тип"])
                r.createCell(4).setCellValue(rd["Повторение"])
                r.createCell(5).setCellValue(rd["Описание"])
            }
            hs.size().times { s.autoSizeColumn(it) }

            def os = new ByteArrayOutputStream()
            wb.write(os)
            wb.close()
            return os.toByteArray()
        }
    }

    static class JsonSchemaExcelExporter {
        byte[] createExcel(String schemaText) {
            def schema = new JsonSlurper().parseText(schemaText)
            List<Map> rows = []

            parseNode(schema, "Root", "", true, rows)

            Workbook wb = new org.apache.poi.xssf.usermodel.XSSFWorkbook()
            Sheet s = wb.createSheet("JSON Schema")
            Row hr = s.createRow(0)
            def hs = ["Уровень", "Наименование", "Категория", "Тип", "Повторение", "Описание"]
            hs.eachWithIndex{ h, i -> hr.createCell(i).setCellValue(h) }

            rows.eachWithIndex { rd, i ->
                Row r = s.createRow(i+1)
                r.createCell(0).setCellValue(rd["Уровень"]?.toString() ?: "")
                r.createCell(1).setCellValue(rd["Наименование"]?.toString() ?: "")
                r.createCell(2).setCellValue(rd["Категория"]?.toString() ?: "")
                r.createCell(3).setCellValue(rd["Тип"]?.toString() ?: "")
                r.createCell(4).setCellValue(rd["Повторение"]?.toString() ?: "")
                r.createCell(5).setCellValue(rd["Описание"]?.toString() ?: "")
            }
            hs.size().times { s.autoSizeColumn(it) }

            def os = new ByteArrayOutputStream()
            wb.write(os)
            wb.close()
            return os.toByteArray()
        }

        private void parseNode(def node, String name, String level, boolean isRequired, List<Map> rows) {
            if (!(node instanceof Map)) return

            String type = node.get("type")?.toString()
            if (!type) {
                if (node.get("properties") instanceof Map) type = "object"
                else if (node.get("items")) type = "array"
                else type = "string"
            }

            String desc = node.get("description")?.toString() ?: ""
            String card = (type == "array") ? (isRequired ? "1..n" : "0..n") : (isRequired ? "1..1" : "0..1")

            boolean isRoot = (name == "Root")

            if (!isRoot) {
                rows.add([
                        "Уровень": level,
                        "Наименование": name,
                        "Категория": "Element",
                        "Тип": type,
                        "Повторение": card,
                        "Описание": desc
                ])
            }

            if (type == "object" && node.get("properties") instanceof Map) {
                def reqList = node.get("required") instanceof List ? node.get("required") : []
                int index = 1
                node.get("properties").each { k, v ->
                    boolean childReq = reqList.contains(k)
                    String childLevel = isRoot ? "${index}" : "${level}.${index}"
                    parseNode(v, k.toString(), childLevel, childReq, rows)
                    index++
                }
            }
            else if (type == "array" && node.get("items") instanceof Map) {
                def itemsNode = node.get("items")
                if (itemsNode.get("type") == "object" || itemsNode.get("properties") instanceof Map) {
                    def reqList = itemsNode.get("required") instanceof List ? itemsNode.get("required") : []
                    int index = 1
                    itemsNode.get("properties")?.each { k, v ->
                        boolean childReq = reqList.contains(k)
                        String childLevel = isRoot ? "${index}" : "${level}.${index}"
                        parseNode(v, k.toString(), childLevel, childReq, rows)
                        index++
                    }
                } else {
                    String childLevel = isRoot ? "1" : "${level}.1"
                    parseNode(itemsNode, "item", childLevel, true, rows)
                }
            }
        }
    }


    static class ExampleGenerator {
        private Map rootSchema

        ExampleGenerator() {}

        String generateExampleFromJsonSchema(String schemaText, boolean forceFull) {
            if (!schemaText || schemaText.trim().isEmpty()) {
                throw new IllegalArgumentException("JSON Schema не может быть пустой.")
            }
            try {
                this.rootSchema = new JsonSlurper().parseText(schemaText) as Map
                def counters = [s: 1, n: 1, b: 1]
                def stats = [totalOpt: 0, genOpt: 0]

                def generatedData = generateFromSchema(this.rootSchema, this.rootSchema, "#", 0, counters, stats, forceFull)
                String mockJson = JsonOutput.prettyPrint(JsonOutput.toJson(generatedData))

                String mode = "PARTIAL"
                if (stats.totalOpt == 0 || stats.genOpt == stats.totalOpt) mode = "FULL"
                else if (stats.genOpt == 0) mode = "MINIMAL"

                def result = [
                        mock: mockJson,
                        mode: mode,
                        genOpt: stats.genOpt,
                        totOpt: stats.totalOpt
                ]
                return MyJsonOutput.toJson(result)
            } catch (Throwable e) {
                e.printStackTrace()
                throw new RuntimeException("Сбой при генерации: " + e.getMessage())
            }
        }

        private def findDefinition(Map rootSchema, String ref) {
            def parts = ref.split('/')
            if (parts.length == 3 && parts[0] == '#' && parts[1] == 'definitions') {
                def defName = parts[2]
                def defs = rootSchema.get('definitions')
                def definition = defs ? defs.get(defName) : null
                if (definition == null) {
                    throw new IllegalStateException("Definition '${defName}' не найдена в схеме!")
                }
                return definition
            }
            throw new UnsupportedOperationException("Поддерживаются только простые \$ref вида '#/definitions/...'")
        }

        private def generateFromSchema(Map rootSchema, Map subSchema, String currentPath, int depth, Map counters, Map stats, boolean forceFull) {
            if (subSchema == null || depth > 20) return null

            if (subSchema.get('$ref')) {
                def resolvedSchema = findDefinition(rootSchema, subSchema.get('$ref').toString())
                def mergedSchema = new HashMap(resolvedSchema)
                subSchema.findAll { it.key != '$ref' }.each { mergedSchema[it.key] = it.value }
                return generateFromSchema(rootSchema, mergedSchema, currentPath + " (resolved)", depth + 1, counters, stats, forceFull)
            }

            def enumVal = subSchema.get('enum')
            if (enumVal instanceof List && !enumVal.isEmpty()) {
                return enumVal[0]
            }

            def type = subSchema.get('type')
            if (type instanceof List) {
                type = type.find { it != 'null' } ?: 'string'
            }
            if (type == null && subSchema.get('properties') instanceof Map) {
                type = "object"
            }

            def rand = new Random()

            switch (type) {
                case "object":
                    def obj = [:]
                    def props = subSchema.get('properties')
                    def requiredList = subSchema.get('required') instanceof List ? subSchema.get('required') : []

                    if (props instanceof Map) {
                        for (Map.Entry entry in props.entrySet()) {
                            def key = entry.getKey()
                            boolean isRequired = requiredList.contains(key)

                            if (!isRequired) {
                                stats.totalOpt++
                                if (!forceFull && rand.nextBoolean()) {
                                    continue
                                }
                                stats.genOpt++
                            }

                            def value = entry.getValue()
                            if (value instanceof Map) {
                                obj[key] = generateFromSchema(rootSchema, value as Map, currentPath + "/" + key, depth + 1, counters, stats, forceFull)
                            }
                        }
                    }
                    return obj

                case "array":
                    def arr = []
                    def items = subSchema.get('items')
                    if (items instanceof Map) {
                        def randSize = forceFull ? 2 : rand.nextInt(3)
                        randSize.times {
                            arr.add(generateFromSchema(rootSchema, items as Map, currentPath + "/items", depth + 1, counters, stats, forceFull))
                        }
                    }
                    return arr

                case "string": return "s" + (counters.s++)
                case "integer": return counters.n++
                case "number": return (counters.n++) + 0.5
                case "boolean": return (counters.b++) % 2 != 0
                case "null": return null
                default: return "val" + (counters.s++)
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

            int iter = config.iteration ? config.iteration as Integer : 0
            int startIdx = iter > 0 ? iter : 1
            int endIdx = iter > 0 ? iter : (config.numberOfRequests ?: 1)

            def finalHeaders = new HashMap(config.headers)
            String authLogin = config.login
            String authPass = config.password

            if (config.credentialId) {
                def cred = historyManager.getRawCredential(config.credentialId as Integer)
                if (cred) {
                    authLogin = cred.login
                    authPass = CryptoUtil.decrypt(cred.password)
                }
            }
            if (authLogin && !authLogin.isEmpty()) {
                String auth = authLogin + ":" + (authPass ?: "")
                finalHeaders['Authorization'] = 'Basic ' + java.util.Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8))
            }

            (startIdx..endIdx).each { i ->
                def requestBody = generateRequestBody(config.template, config.dataRules, i)
                def logEntry = [:];

                logEntry.requestHeaders = finalHeaders

                def historyEntry = [cs_name: config.csName, bs_name: config.bsName, interface_o: config.interfaceName, http_method: config.method ?: 'POST', service_url: config.url, request: requestBody, request_headers: MyJsonOutput.toJson(finalHeaders), http_status: 0]
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

                    logEntry.responseStatus = response.statusCode()
                    logEntry.responseBody = response.body()

                    logEntry.responseHeaders = response.headers().map()

                    historyEntry.response = response.body()
                    historyEntry.http_status = response.statusCode()
                    historyEntry.response_headers = MyJsonOutput.toJson(response.headers().map())

                } catch (Exception e) {
                    def sw = new StringWriter(); e.printStackTrace(new PrintWriter(sw)); def stackTrace = sw.toString()
                    logEntry.error = e.getMessage(); logEntry.stackTrace = stackTrace
                    historyEntry.stack_trace = stackTrace
                }
                historyManager.saveCall(historyEntry)
                mainLog.append("--- Запрос ${i} ---\n").append(formatLogEntry(logEntry, requestBody) + "\n" + "=".repeat(50) + "\n\n")
            }
            return mainLog.toString()
        }
        private String generateRequestBody(String template, Map rules, int iteration) { def body = template; rules?.each { key, rule -> def placeholder = "##${key}##"; def value; switch (rule.type) { case 'increment': value = (rule.start + (rule.step * (iteration - 1))); break; case 'random': value = rule.values[new Random().nextInt(rule.values.size())]; break; case 'uuid': value = UUID.randomUUID().toString(); break; case 'current_timestamp': value = new Date().format(rule.format ?: 'yyyy-MM-dd HH:mm:ss'); break; case 'random_number': def min = rule.min ?: 0; def max = rule.max ?: 100; value = new Random().nextInt((max - min) + 1) + min; break; case 'from_list': value = rule.values[(iteration - 1) % rule.values.size()]; break }; if (value != null) { body = body.replace(placeholder, value.toString()) } }; return body }

        private String formatLogEntry(Map entry, String requestBody) {
            def builder = new StringBuilder();

            if (entry.requestHeaders) {
                builder.append("Request Headers:\n${MyJsonOutput.prettyPrint(MyJsonOutput.toJson(entry.requestHeaders))}\n\n")
            }

            builder.append("Request Body:\n${requestBody}\n").append("---------------------------------\n").append("Response Status: ${entry.responseStatus ?: 'N/A'}\n\n");

            if (entry.responseHeaders) {
                builder.append("Response Headers:\n${MyJsonOutput.prettyPrint(MyJsonOutput.toJson(entry.responseHeaders))}\n\n")
            }

            builder.append("Response Body:\n${entry.responseBody ?: 'N/A'}\n");

            if (entry.error) { builder.append("Error: ${entry.error}\n") };
            if (entry.stackTrace) { builder.append("Stack Trace:\n${entry.stackTrace}\n") };
            return builder.toString()
        }
    }

    static class XsdToWsdlConverter {
        String generate(String xsdContent, String xsdFileName, String serviceType) {
            println "DEBUG: Начало генерации WSDL. Файл: ${xsdFileName}"
            if (!xsdContent || xsdContent.trim().isEmpty()) throw new IllegalArgumentException("XSD пустой")
            if (!xsdFileName) throw new IllegalArgumentException("Нет имени файла")
            try {
                String cleanXsdBody = xsdContent.replaceAll(/<\?xml.*?\?>/, "").trim()
                def slurper = new XmlSlurper(false, false)
                def xsdParsed = slurper.parseText(cleanXsdBody)
                def rootElementNode
                if (xsdParsed.name().toLowerCase().contains("schema")) {
                    xsdParsed.childNodes().find { child ->
                        if (child.name() == 'element' && child['@name'] != '') {
                            rootElementNode = child; return true
                        }
                        return false
                    }
                }
                if (!rootElementNode) { rootElementNode = xsdParsed.depthFirst().find { it.name() == 'element' && it['@name'] != '' && it.parent().name().contains('schema') } }
                if (!rootElementNode) { rootElementNode = xsdParsed.depthFirst().find { it.name() == 'element' && it['@name'] != '' } }
                if (!rootElementNode) {
                    def matcher = (cleanXsdBody =~ /:element\s+name=["']([^"']+)["']/)
                    if (matcher.find()) {
                        println "DEBUG: Element found via REGEX"
                        rootElementNode = [name: { matcher[0][1] }]
                    } else { throw new IllegalStateException("Не найден <element name='...'> в XSD.") }
                }
                String rootElementName = (rootElementNode instanceof Map) ? rootElementNode.name() : rootElementNode['@name'].text()
                println "DEBUG: Найден корневой элемент: ${rootElementName}"

                def baseName = xsdFileName.contains('.') ? xsdFileName.take(xsdFileName.lastIndexOf('.')) : xsdFileName
                def serviceName = "si_${serviceType == 'synchronous' ? 'so' : 'ao'}_${baseName}"
                def targetNamespace = "urn:example.com:${baseName}"
                def requestMsg = "mt_${rootElementName}_RQ"
                def responseMsg = "mt_${rootElementName}_RS"
                def writer = new StringWriter()
                writer.write('<?xml version="1.0" encoding="UTF-8"?>\n')
                def wsdl = new MarkupBuilder(writer)
                wsdl.'wsdl:definitions'( 'xmlns:wsdl': "http://schemas.xmlsoap.org/wsdl/", 'xmlns:soap': "http://schemas.xmlsoap.org/wsdl/soap/", 'xmlns:xsd': "http://www.w3.org/2001/XMLSchema", 'xmlns:tns': targetNamespace, name: serviceName, targetNamespace: targetNamespace ) {
                    'wsdl:types' { 'xsd:schema'(targetNamespace: targetNamespace) { wsdl.mkp.yieldUnescaped(cleanXsdBody) } }
                    'wsdl:message'(name: requestMsg) { 'wsdl:part'(name: 'parameters', element: "tns:${rootElementName}") }
                    if (serviceType == 'synchronous') { 'wsdl:message'(name: responseMsg) { 'wsdl:part'(name: 'parameters', element: "tns:${rootElementName}Response") } }
                    'wsdl:portType'(name: serviceName) { 'wsdl:operation'(name: serviceName) { 'wsdl:input'(message: "tns:${requestMsg}"); if (serviceType == 'synchronous') { 'wsdl:output'(message: "tns:${responseMsg}") } } }
                    'wsdl:binding'(name: "${serviceName}Binding", type: "tns:${serviceName}") {
                        'soap:binding'(style: 'document', transport: 'http://schemas.xmlsoap.org/soap/http')
                        'wsdl:operation'(name: serviceName) {
                            'soap:operation'(soapAction: "http://sap.com/xi/WebService/soap1.1")
                            'wsdl:input' { 'soap:body'(use: 'literal') }
                            if (serviceType == 'synchronous') { 'wsdl:output' { 'soap:body'(use: 'literal') } }
                        }
                    }
                    'wsdl:service'(name: "${serviceName}Service") { 'wsdl:port'(name: 'HTTP_Port', binding: "tns:${serviceName}Binding") { 'soap:address'(location: "http://localhost:8080/soap/${serviceName}Service") } }
                }
                String resultXml = writer.toString()
                if (serviceType == 'synchronous') {
                    String fakeResponseXsd = """<xsd:element name="${rootElementName}Response"><xsd:complexType><xsd:sequence><xsd:element name="Response" type="xsd:string"/></xsd:sequence></xsd:complexType></xsd:element>"""
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
            sql.execute("INSERT INTO call_history (cs_name, bs_name, interface_o, http_method, request, response, service_url, domain_url, request_headers, response_headers, stack_trace, http_status, call_date) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    [data.cs_name, data.bs_name, data.interface_o, data.http_method, data.request, data.response, data.service_url, data.domain_url, data.request_headers, data.response_headers, data.stack_trace, data.http_status, formattedDate])
        }
        def togglePin(int id, String comment, int isPinned) {
            sql.execute("UPDATE call_history SET is_pinned=?, pin_comment=? WHERE id=?", [isPinned, comment, id])
            return [status: 'OK']
        }
        def getAll() { return sql.rows("SELECT * FROM call_history ORDER BY id DESC") }
        def getGroup(String csName) { return sql.rows("SELECT * FROM call_history WHERE cs_name = ? ORDER BY id ASC", [csName]) }
        def search(Map params) {
            def wc = []
            def qp = []
            boolean doGroup = false

            params.each { k, v ->
                if (v && !v.toString().trim().isEmpty()) {
                    def nv = "%${v.toString().trim().toLowerCase()}%"
                    switch(k) {
                        case 'csName': wc.add("LOWER(cs_name) LIKE ?"); qp.add(nv); break;
                        case 'bsName': wc.add("LOWER(bs_name) LIKE ?"); qp.add(nv); break;
                        case 'interfaceName': wc.add("LOWER(interface_o) LIKE ?"); qp.add(nv); break;
                        case 'url': wc.add("LOWER(service_url) LIKE ?"); qp.add(nv); break;
                        case 'content':
                            wc.add("""(
                                LOWER(request) LIKE ? OR 
                                LOWER(response) LIKE ? OR 
                                LOWER(stack_trace) LIKE ? OR
                                LOWER(request_headers) LIKE ? OR
                                LOWER(response_headers) LIKE ?
                            )""")
                            5.times { qp.add(nv) }
                            break;
                        case 'dateFrom': wc.add("datetime(call_date) >= datetime(?)"); qp.add("${v} 00:00:00"); break;
                        case 'dateTo':   wc.add("datetime(call_date) <= datetime(?)"); qp.add("${v} 23:59:59"); break;
                        case 'groupByInterface': if (v == 'true' || v == 'on') doGroup = true; break;
                    }
                }
            }

            def whereClause = wc ? " WHERE " + wc.join(" AND ") : ""

            if (doGroup) {
                def qb = new StringBuilder("""
                    WITH RankedCalls AS (
                        SELECT *, 
                               ROW_NUMBER() OVER (PARTITION BY bs_name, interface_o ORDER BY call_date DESC) as rn
                        FROM call_history
                        ${whereClause}
                    )
                    SELECT * FROM RankedCalls WHERE rn <= 2 ORDER BY id DESC
                """)
                return sql.rows(qb.toString(), qp)
            } else {
                def qb = new StringBuilder("SELECT * FROM call_history ${whereClause} ORDER BY id DESC")
                return sql.rows(qb.toString(), qp)
            }
        }

        def getAllCredentials() { return sql.rows("SELECT id, name FROM credentials ORDER BY name ASC") }
        def getCredentialById(int id) {
            def row = sql.firstRow("SELECT * FROM credentials WHERE id = ?", [id])
            if (!row) return null
            def cred = new HashMap(row)
            if (cred.password) cred.password = "********"
            return cred
        }
        def getRawCredential(int id) { return sql.firstRow("SELECT * FROM credentials WHERE id = ?", [id]) }
        def saveCredential(Map data) {
            if (data.id) {
                if (data.password && data.password != "********") {
                    sql.execute("UPDATE credentials SET name=?, login=?, password=? WHERE id=?",
                            [data.name, data.login, CryptoUtil.encrypt(data.password), data.id])
                } else {
                    sql.execute("UPDATE credentials SET name=?, login=? WHERE id=?",
                            [data.name, data.login, data.id])
                }
            } else {
                sql.execute("INSERT INTO credentials (name, login, password) VALUES (?,?,?)",
                        [data.name, data.login, CryptoUtil.encrypt(data.password)])
            }
            return [status: 'OK']
        }
        def deleteCredential(int id) { sql.execute("DELETE FROM credentials WHERE id=?", [id]); return [status: 'OK']}
    }

    static String fetchTaskFromALM(String taskId) {
        String almBaseUrl = "https://alm.yourcompany.com/rest/api/2/issue"
        String authHeader = "Basic " + "ТВОЙ_ЛОГИН:ТВОЙ_ПАРОЛЬ".bytes.encodeBase64().toString()

        try {
            URL url = new URL("${almBaseUrl}/${taskId}")
            HttpURLConnection conn = (HttpURLConnection) url.openConnection()

            conn.setRequestMethod("GET")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", authHeader)
            conn.setConnectTimeout(5000)

            if (conn.responseCode in 200..299) {
                def responseText = conn.inputStream.text
                def almJson = new groovy.json.JsonSlurper().parseText(responseText)

                def portalData = [
                        task_number: taskId,
                        task_url   : "https://alm.yourcompany.com/browse/${taskId}",
                        title      : almJson.fields?.summary ?: almJson.summary ?: almJson.title ?: "Без названия",
                        description: almJson.fields?.description ?: almJson.description ?: "",
                        status     : almJson.fields?.status?.name ?: almJson.status?.name ?: "Новая"
                ]

                return new groovy.json.JsonBuilder(portalData).toString()
            } else {
                def errorText = conn.errorStream?.text ?: "HTTP ${conn.responseCode}"
                throw new Exception("ALM Error: ${conn.responseCode} - ${errorText}")
            }
        } catch (Exception e) {
            throw new RuntimeException("ALM Connection failed: " + e.getMessage())
        }
    }

    static void main(String[] args) {
        // === ИЗОЛЯЦИЯ БД В ПАПКУ db/ ===
        def dbDir = new File("db")
        if (!dbDir.exists()) {
            dbDir.mkdirs()
        }
        def dbFile = '../db/portal.db'
        def sql = Sql.newInstance("jdbc:sqlite:${dbFile}", "org.sqlite.JDBC")

        // 1. Создание всех таблиц
        sql.execute'''CREATE TABLE IF NOT EXISTS saved_documents (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, content TEXT, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS tasks (id INTEGER PRIMARY KEY AUTOINCREMENT, user TEXT, task_url TEXT, task_number TEXT, stage TEXT, status TEXT, deployment_date TEXT, planned_dev_date TEXT, additional_comment TEXT, contact_person TEXT, spec_url TEXT, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)'''
        sql.execute('''CREATE TABLE IF NOT EXISTS mermaid_schemas (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, data TEXT, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)''')

        // ТАБЛИЦА ПОЛЬЗОВАТЕЛЕЙ (ОБНОВЛЕННАЯ С ГРУППАМИ)
        sql.execute('''
            CREATE TABLE IF NOT EXISTS users (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                login TEXT UNIQUE,
                username TEXT UNIQUE,
                name TEXT,
                role TEXT,
                groups TEXT,
                password TEXT,
                is_root INTEGER DEFAULT 0
            )
        ''')

        sql.execute'''CREATE TABLE IF NOT EXISTS task_settings (key TEXT PRIMARY KEY, value TEXT)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS labels_categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, display_order INTEGER DEFAULT 99)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS labels (id INTEGER PRIMARY KEY AUTOINCREMENT, category_id INTEGER, content TEXT, usage_count INTEGER DEFAULT 0, date_created TIMESTAMP DEFAULT CURRENT_TIMESTAMP, title TEXT, FOREIGN KEY(category_id) REFERENCES labels_categories(id))'''
        sql.execute'''CREATE TABLE IF NOT EXISTS credentials (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, login TEXT, password TEXT)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS call_history (id INTEGER PRIMARY KEY AUTOINCREMENT, cs_name TEXT, bs_name TEXT, interface_o TEXT, request TEXT, response TEXT, service_url TEXT, domain_url TEXT, request_headers TEXT, response_headers TEXT, stack_trace TEXT, call_date DATETIME)'''
        sql.execute'''CREATE TABLE IF NOT EXISTS saved_xsd (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL UNIQUE, content TEXT, last_updated TIMESTAMP DEFAULT CURRENT_TIMESTAMP)'''

        sql.execute('''CREATE TABLE IF NOT EXISTS xsd_schemas (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, content TEXT)''')

        sql.execute('''CREATE TABLE IF NOT EXISTS integration_flows (id INTEGER PRIMARY KEY AUTOINCREMENT, task_id INTEGER NOT NULL, sender TEXT NOT NULL, receiver TEXT NOT NULL, order_num INTEGER NOT NULL, FOREIGN KEY(task_id) REFERENCES tasks(id) ON DELETE CASCADE)''')
        try { sql.execute("CREATE INDEX IF NOT EXISTS idx_flows_task_id ON integration_flows(task_id)") } catch(Exception e) {}

        sql.execute('''CREATE TABLE IF NOT EXISTS systems (id INTEGER PRIMARY KEY AUTOINCREMENT, sid TEXT NOT NULL UNIQUE, description TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)''')

        sql.execute('''CREATE TABLE IF NOT EXISTS environments (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, base_url TEXT)''')
        sql.execute('''CREATE TABLE IF NOT EXISTS api_tabs (id INTEGER PRIMARY KEY AUTOINCREMENT, env TEXT, name TEXT, method TEXT, url TEXT, headers TEXT, body TEXT, data_rules TEXT, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)''')

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
        sql.execute('''
            CREATE TABLE IF NOT EXISTS flow_mappings (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                flow_id INTEGER NOT NULL,
                source_path TEXT,
                target_path TEXT,
                mapping_type TEXT,
                transformation_rule TEXT,
                FOREIGN KEY(flow_id) REFERENCES flow_details(id) ON DELETE CASCADE
            )
        ''')

        // 2. ИСПРАВЛЕННАЯ МИГРАЦИЯ КОЛОНОК (ЧЕРЕЗ PRAGMA)
        def checkAndAddCol = { String table, String col, String type ->
            boolean exists = false
            sql.eachRow("PRAGMA table_info(" + table + ")") { row ->
                if (row.name.equalsIgnoreCase(col)) exists = true
            }
            if (!exists) {
                try {
                    sql.execute("ALTER TABLE " + table + " ADD COLUMN \"" + col + "\" " + type)
                } catch (Exception e) {
                    println "Warning: Could not add column ${col} to ${table}: ${e.message}"
                }
            }
        }

        checkAndAddCol('users', 'username', 'TEXT')
        checkAndAddCol('users', 'groups', 'TEXT')
        checkAndAddCol('users', 'is_root', 'INTEGER DEFAULT 0')

        // Миграция старых логинов в новые username
        sql.execute("UPDATE users SET username = login WHERE username IS NULL AND login IS NOT NULL")

        // Дефолтный root пользователь с полными правами, если база пустая
        if (sql.rows("SELECT COUNT(*) as c FROM users").first().c == 0) {
            sql.executeInsert("INSERT INTO users (login, username, name, role, groups, password, is_root) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    ["root", "root", "Главный Администратор", "admin", "[]", "root", 1])
        }

        checkAndAddCol('api_tabs', 'data_rules', 'TEXT')

        // --- 100% БЕЗОПАСНАЯ ПРОВЕРКА КОЛОНОК ДЛЯ TASKS ---
        checkAndAddCol('tasks', 'user', 'TEXT')
        checkAndAddCol('tasks', 'task_url', 'TEXT')
        checkAndAddCol('tasks', 'task_number', 'TEXT')
        checkAndAddCol('tasks', 'stage', 'TEXT')
        checkAndAddCol('tasks', 'status', 'TEXT')
        checkAndAddCol('tasks', 'deployment_date', 'TEXT')
        checkAndAddCol('tasks', 'planned_dev_date', 'TEXT')
        checkAndAddCol('tasks', 'additional_comment', 'TEXT')
        checkAndAddCol('tasks', 'contact_person', 'TEXT')
        checkAndAddCol('tasks', 'spec_url', 'TEXT')
        checkAndAddCol('tasks', 'last_updated', 'TEXT')

        checkAndAddCol('tasks', 'version', 'INTEGER DEFAULT 1')
        checkAndAddCol('tasks', 'sender_sys', 'TEXT')
        checkAndAddCol('tasks', 'receiver_sys', 'TEXT')
        checkAndAddCol('tasks', 'interface_name', 'TEXT')
        checkAndAddCol('tasks', 'connection_type', 'TEXT')
        checkAndAddCol('tasks', 'input_format', 'TEXT')
        checkAndAddCol('tasks', 'output_format', 'TEXT')
        checkAndAddCol('tasks', 'search_tags', 'TEXT')
        checkAndAddCol('tasks', 'owner_id', 'INTEGER')

        checkAndAddCol('tasks', 'start_dev_date', 'TEXT')
        checkAndAddCol('tasks', 'test_deployment_date', 'TEXT')
        checkAndAddCol('tasks', 'release_date', 'TEXT')
        checkAndAddCol('tasks', 'user_notes', 'TEXT')

        checkAndAddCol('flow_details', 'target_description', 'TEXT')
        checkAndAddCol('flow_details', 'protocol_out', 'TEXT')
        checkAndAddCol('flow_details', 'format_out', 'TEXT')
        checkAndAddCol('call_history', 'bs_name', 'TEXT')
        checkAndAddCol('call_history', 'http_method', "TEXT DEFAULT 'POST'")
        checkAndAddCol('call_history', 'http_status', 'INTEGER')
        checkAndAddCol('call_history', 'is_pinned', 'INTEGER DEFAULT 0')
        checkAndAddCol('call_history', 'pin_comment', 'TEXT')
        checkAndAddCol('labels', 'usage_count', 'INTEGER DEFAULT 0')
        checkAndAddCol('labels', 'title', 'TEXT')

        // Сохраняем все старые данные
        sql.execute("UPDATE tasks SET last_updated = CURRENT_TIMESTAMP WHERE last_updated IS NULL")
        sql.execute("UPDATE tasks SET owner_id = 1 WHERE owner_id IS NULL")
        sql.execute("UPDATE tasks SET version = 1 WHERE version IS NULL")

        def stagesExist = sql.firstRow("SELECT 1 FROM task_settings WHERE key='task_stages_dictionary'")
        if (!stagesExist) {
            def defaultStages = [
                    "Этап 1: Очередь (Backlog)": ["Новая", "Взято в работу", "Оценка", "В очереди"],
                    "Этап 2: Аналитика (Analysis)": ["Анализ", "Проектирование / ТЗ", "Согласование ТЗ"],
                    "Этап 3: Разработка (Dev)": ["В разработке", "Code Review", "Исправление замечаний", "Dev Тест пройден"],
                    "Этап 4: Тестирование (Test/UAT)": ["Готово к установке (TEST)", "Установлено (TEST)", "Тестирование", "Приемка (UAT)"],
                    "Этап 5: Внедрение (Prod)": ["Готово к внедрению (PROD)", "Установка (PROD)", "Внедрение завершено"],
                    "Специальные": ["Приостановлено (On Hold)", "Заблокировано (Blocked)", "Отменено"]
            ]
            sql.execute("INSERT INTO task_settings (key, value) VALUES ('task_stages_dictionary', ?)", [MyJsonOutput.toJson(defaultStages)])
        }

        int port = args.length > 0 && args[0].isInteger() ? args[0].toInteger() : 47183
        def server = HttpServer.create(new InetSocketAddress(port), 0);
        println "Сервер запущен на http://localhost:${port}"

        def historyManager = new CallHistoryManager(sql); def converter = new DataConverter(); def excelExporter = new ExcelExporter(); def exampleGenerator = new ExampleGenerator(); def multiRequestExecutor = new MultiRequestExecutor(historyManager); def xsdToWsdlConverter = new XsdToWsdlConverter(); def labelsManager = new LabelsManager(sql)

        // API АВТОРИЗАЦИИ
        server.createContext("/api/login") { e ->
            if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    def json = new JsonSlurper().parseText(e.requestBody.text)
                    def loginStr = json.login ?: json.username
                    def user = sql.firstRow("SELECT id, username as login, username, name, role, groups, is_root FROM users WHERE (username = ? OR login = ?) AND password = ?", [loginStr, loginStr, json.password])
                    if (user) {
                        if (user.groups) {
                            try { user.groups = new JsonSlurper().parseText(user.groups) } catch(ex){ user.groups = [] }
                        } else { user.groups = [] }
                        sendResponse(e, MyJsonOutput.toJson(user), "application/json")
                    } else {
                        sendResponse(e, MyJsonOutput.toJson([error: "Неверный логин или пароль"]), "application/json", 401)
                    }
                }
            } else {
                sendResponse(e, "Method Not Allowed", "text/plain", 405)
            }
        }

        // API УПРАВЛЕНИЯ ПОЛЬЗОВАТЕЛЯМИ С ГРУППАМИ
        server.createContext("/api/users") { e ->
            def role = e.requestHeaders.getFirst("X-User-Role")
            if (role != 'admin') {
                sendResponse(e, MyJsonOutput.toJson([error: "Доступ запрещен. Требуются права администратора."]), "application/json", 403)
                return
            }

            if (e.requestMethod == "GET") {
                handleRequest(e, "GET") {
                    def usersList = sql.rows("SELECT id, username, login, name, role, groups, is_root FROM users ORDER BY id ASC")
                    usersList.each { u ->
                        u.username = u.username ?: u.login
                        if (u.groups) {
                            try { u.groups = new JsonSlurper().parseText(u.groups) } catch(ex) { u.groups = [] }
                        } else { u.groups = [] }
                    }
                    sendResponse(e, MyJsonOutput.toJson(usersList), "application/json")
                }
            }
            else if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    def d = new JsonSlurper().parseText(e.requestBody.text)
                    def groupsJson = MyJsonOutput.toJson(d.groups ?: [])

                    if (d.id) {
                        def existing = sql.firstRow("SELECT is_root FROM users WHERE id = ?", [d.id as Integer])
                        if (existing?.is_root == 1) {
                            d.role = 'admin'
                            d.username = 'root'
                        }

                        if (d.password && d.password.trim() != "") {
                            sql.execute("UPDATE users SET username=?, login=?, name=?, role=?, groups=?, password=? WHERE id=?",
                                    [d.username, d.username, d.name ?: '', d.role, groupsJson, d.password, d.id as Integer])
                        } else {
                            sql.execute("UPDATE users SET username=?, login=?, name=?, role=?, groups=? WHERE id=?",
                                    [d.username, d.username, d.name ?: '', d.role, groupsJson, d.id as Integer])
                        }
                    } else {
                        sql.executeInsert("INSERT INTO users (username, login, name, role, groups, password) VALUES (?, ?, ?, ?, ?, ?)",
                                [d.username, d.username, d.name ?: '', d.role, groupsJson, d.password ?: '12345'])
                    }
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
            }
            else if (e.requestMethod == "DELETE") {
                handleRequest(e, "DELETE") {
                    def d = new JsonSlurper().parseText(e.requestBody.text)
                    def existing = sql.firstRow("SELECT is_root FROM users WHERE id = ?", [d.id as Integer])
                    if (existing?.is_root == 1) {
                        sendResponse(e, MyJsonOutput.toJson([error: "Нельзя удалить root пользователя!"]), "application/json", 403)
                        return
                    }
                    sql.execute("DELETE FROM users WHERE id=?", [d.id as Integer])
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
            }
        }

        // API ОКРУЖЕНИЙ (ХОСТОВ)
        server.createContext("/api/environments") { e ->
            if (e.requestMethod == "GET") {
                handleRequest(e, "GET") {
                    def envs = sql.rows("SELECT * FROM environments ORDER BY id ASC")
                    sendResponse(e, MyJsonOutput.toJson(envs), "application/json")
                }
            } else if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    def d = new JsonSlurper().parseText(e.requestBody.text)
                    if (d.id) {
                        sql.execute("UPDATE environments SET name=?, base_url=? WHERE id=?", [d.name, d.base_url, d.id as Integer])
                    } else {
                        sql.executeInsert("INSERT INTO environments (name, base_url) VALUES (?, ?)", [d.name, d.base_url])
                    }
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
            } else if (e.requestMethod == "DELETE") {
                handleRequest(e, "DELETE") {
                    def d = new JsonSlurper().parseText(e.requestBody.text)
                    sql.execute("DELETE FROM environments WHERE id=?", [d.id as Integer])
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
            }
        }

        // API ВКЛАДОК
        server.createContext("/api/tabs") { e ->
            if (e.requestMethod == "GET") {
                handleRequest(e, "GET") {
                    def tabs = sql.rows("SELECT * FROM api_tabs ORDER BY created_at DESC")
                    sendResponse(e, MyJsonOutput.toJson(tabs), "application/json")
                }
            } else if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    def d = new JsonSlurper().parseText(e.requestBody.text)
                    if (d.id) {
                        sql.execute("UPDATE api_tabs SET env=?, name=?, method=?, url=?, headers=?, body=?, data_rules=? WHERE id=?",
                                [d.env, d.name, d.method, d.url, d.headers, d.body, d.data_rules, d.id as Integer])
                        sendResponse(e, MyJsonOutput.toJson([status: "OK", id: d.id]), "application/json")
                    } else {
                        sql.executeInsert("INSERT INTO api_tabs (env, name, method, url, headers, body, data_rules) VALUES (?, ?, ?, ?, ?, ?, ?)",
                                [d.env, d.name, d.method, d.url, d.headers, d.body, d.data_rules])
                        def newId = sql.firstRow("SELECT last_insert_rowid() as id").id
                        sendResponse(e, MyJsonOutput.toJson([status: "OK", id: newId]), "application/json")
                    }
                }
            } else if (e.requestMethod == "DELETE") {
                handleRequest(e, "DELETE") {
                    def d = new JsonSlurper().parseText(e.requestBody.text)
                    sql.execute("DELETE FROM api_tabs WHERE id=?", [d.id as Integer])
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
            }
        }

        // ОЧИСТКА ЛОГОВ
        server.createContext("/api/history/clear") { e ->
            if (e.requestHeaders.getFirst("X-User-Role") != 'admin') return sendResponse(e, MyJsonOutput.toJson([error: "Только администратор может очищать логи"]), "application/json", 403)
            handleRequest(e, "POST") {
                def d = new JsonSlurper().parseText(e.requestBody.text)
                def params = []
                def conditions = []

                if (d.sysName && d.sysName != "ALL") { conditions << "cs_name = ?"; params << d.sysName }
                if (d.olderThan) { conditions << "call_date < ?"; params << d.olderThan + " 00:00:00" }

                def whereClause = conditions ? "WHERE " + conditions.join(" AND ") : ""
                if (!whereClause && !d.clearAll) { sendResponse(e, MyJsonOutput.toJson([error: "Не выбраны условия очистки!"]), "application/json", 400); return }

                sql.execute("DELETE FROM call_history " + whereClause, params)
                sendResponse(e, MyJsonOutput.toJson([status: "OK", message: "Логи успешно очищены"]), "application/json")
            }
        }

        server.createContext("/api/jsonschema-to-excel"){ e ->
            handleRequest(e, "POST") {
                String b = e.requestBody.text
                def jsonTxt = b.startsWith("json=") ? URLDecoder.decode(b.substring(5), "UTF-8") : b
                def exporter = new JsonSchemaExcelExporter()
                byte[] x = exporter.createExcel(jsonTxt)
                e.responseHeaders.add("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                e.responseHeaders.add("Content-Disposition", "attachment; filename=\"jsonschema_structure.xlsx\"")
                e.sendResponseHeaders(200, x.length)
                e.responseBody.withStream { it.write(x) }
            }
        }

        server.createContext("/api/xsd") { HttpExchange e ->
            try {
                def method = e.requestMethod
                def query = e.requestURI.query

                def params = [:]
                if (query) {
                    query.split('&').each {
                        def parts = it.split('=')
                        if(parts.length == 2) params[parts[0]] = parts[1]
                    }
                }

                if (method == "GET") {
                    if (params.id) {
                        def id = params.id as int
                        def row = sql.firstRow("SELECT content FROM xsd_schemas WHERE id = ?", [id])
                        sendResponse(e, row ? row.content : "{}", "application/json")
                    } else {
                        def list = sql.rows("SELECT id, name FROM xsd_schemas")
                        sendResponse(e, MyJsonOutput.toJson(list), "application/json")
                    }
                }
                else if (method == "POST") {
                    def json = new JsonSlurper().parseText(e.requestBody.text)
                    def existing = sql.firstRow("SELECT id FROM xsd_schemas WHERE name=?", [json.name])

                    if (existing) {
                        sql.execute("UPDATE xsd_schemas SET content=? WHERE name=?", [json.content, json.name])
                    } else {
                        sql.execute("INSERT INTO xsd_schemas (name, content) VALUES (?, ?)", [json.name, json.content])
                    }
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
                else if (method == "DELETE") {
                    def json = new JsonSlurper().parseText(e.requestBody.text)
                    sql.execute("DELETE FROM xsd_schemas WHERE id = ?", [json.id as int])
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
                else {
                    sendResponse(e, "Method Not Allowed", "text/plain", 405)
                }
            } catch (Exception ex) {
                ex.printStackTrace()
                sendResponse(e, MyJsonOutput.toJson([error: ex.message]), "application/json", 500)
            }
        }

        server.createContext("/api/mermaid") { HttpExchange e ->
            try {
                if (e.requestMethod == "GET") {
                    handleRequest(e, "GET") {
                        def list = sql.rows("SELECT id, name, data, last_updated FROM mermaid_schemas ORDER BY last_updated DESC")
                        sendResponse(e, MyJsonOutput.toJson(list), "application/json")
                    }
                }
                else if (e.requestMethod == "POST") {
                    handleRequest(e, "POST") {
                        def json = new JsonSlurper().parseText(e.requestBody.text)
                        if (json.id) {
                            sql.execute("UPDATE mermaid_schemas SET name=?, data=?, last_updated=CURRENT_TIMESTAMP WHERE id=?", [json.name, json.data, json.id])
                            sendResponse(e, MyJsonOutput.toJson([status: "OK", id: json.id]), "application/json")
                        } else {
                            sql.executeInsert("INSERT INTO mermaid_schemas (name, data) VALUES (?, ?)", [json.name, json.data])
                            def newId = sql.firstRow("SELECT last_insert_rowid() as id").id
                            sendResponse(e, MyJsonOutput.toJson([status: "OK", id: newId]), "application/json")
                        }
                    }
                }
                else if (e.requestMethod == "DELETE") {
                    handleRequest(e, "DELETE") {
                        def json = new JsonSlurper().parseText(e.requestBody.text)
                        sql.execute("DELETE FROM mermaid_schemas WHERE id = ?", [json.id as int])
                        sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                    }
                } else {
                    sendResponse(e, "Method Not Allowed", "text/plain", 405)
                }
            } catch (Exception ex) {
                ex.printStackTrace()
                sendResponse(e, MyJsonOutput.toJson([error: ex.message]), "application/json", 500)
            }
        }

        server.createContext("/api/wsdl-to-excel"){ e ->
            handleRequest(e, "POST") {
                String b = e.requestBody.text
                def xml = b.startsWith("xml=") ? URLDecoder.decode(b.substring(4), "UTF-8") : b
                def exporter = new WsdlExcelExporter()
                byte[] x = exporter.createExcel(xml)
                e.responseHeaders.add("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                e.responseHeaders.add("Content-Disposition", "attachment; filename=\"wsdl_structure.xlsx\"")
                e.sendResponseHeaders(200, x.length)
                e.responseBody.withStream { it.write(x) }
            }
        }

        server.createContext("/", { e -> try { def path = e.getRequestURI().getPath() == "/" ? "/index.html" : e.getRequestURI().getPath(); def file = new File(path.substring(1)); if (file.exists()) { def ct = "text/html; charset=utf-8"; if (path.endsWith(".css")) ct = "text/css; charset=utf-8"; else if (path.endsWith(".js")) ct = "application/javascript; charset=utf-8"; def bytes = file.readBytes(); e.responseHeaders.add("Content-Type",ct);e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.getResponseBody().close()} else {sendResponse(e,"Not Found", "text/plain", 404)}} catch(Exception ex){sendResponse(e, ex.message, "text/plain", 500)}})
        server.createContext("/api/parse"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,MyJsonOutput.toJson(converter.parse(d.text,d.format)),"application/json")}}
        server.createContext("/api/serialize"){e->handleRequest(e,"POST"){def s=new JsonSlurper().parse(e.requestBody);def p=e.requestURI.query?.split('&').collectEntries{[(it.split('=')[0]):URLDecoder.decode(it.split('=')[1],"UTF-8")]};sendResponse(e,converter.serialize(s,p.format?:'json',p.pretty.toBoolean()),"text/plain")}}
        server.createContext("/api/fetch-url"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,new URL(d.url).text,"text/plain")}}
        server.createContext("/api/convert"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,converter.serialize(converter.parse(d.text,d.from),d.to,true),"text/plain")}}
        server.createContext("/api/generate-schema"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parseText(e.requestBody.text);sendResponse(e,MyJsonOutput.prettyPrint(MyJsonOutput.toJson(converter.generateJsonSchema(d))),"application/json")}}

        server.createContext("/api/groovy/run") { e ->
            if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    try {
                        String scriptCode = e.requestBody.text
                        if (!scriptCode || scriptCode.trim().isEmpty()) {
                            sendResponse(e, MyJsonOutput.toJson([error: true, message: "Скрипт пустой"]), "application/json", 400)
                            return
                        }

                        def baos = new ByteArrayOutputStream()
                        def printStream = new PrintStream(baos, true, "UTF-8")
                        def binding = new groovy.lang.Binding()
                        binding.setProperty("out", printStream)

                        def shell = new groovy.lang.GroovyShell(binding)
                        def result = shell.evaluate(scriptCode)

                        String finalOutput = ""
                        String consoleOut = baos.toString("UTF-8").trim()

                        if (result != null && result instanceof String) {
                            finalOutput = result
                        }
                        else if (consoleOut) {
                            finalOutput = consoleOut
                        }
                        else if (result != null) {
                            finalOutput = MyJsonOutput.prettyPrint(MyJsonOutput.toJson(result))
                        } else {
                            finalOutput = "Скрипт успешно выполнен, но не вернул никаких данных."
                        }

                        sendResponse(e, MyJsonOutput.toJson([success: true, output: finalOutput]), "application/json")
                    } catch (Exception ex) {
                        sendResponse(e, MyJsonOutput.toJson([error: true, message: ex.getMessage()]), "application/json", 500)
                    }
                }
            }
        }

        server.createContext("/api/db/schema") { e ->
            if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    def payload = new groovy.json.JsonSlurper().parseText(e.requestBody.text)
                    def dbUrl = payload.url
                    def dbUser = payload.user
                    def dbPass = payload.pass

                    def result = [:]
                    java.sql.Connection conn = null

                    try {
                        conn = java.sql.DriverManager.getConnection(dbUrl, dbUser, dbPass)
                        java.sql.DatabaseMetaData meta = conn.getMetaData()

                        def tablesRs = meta.getTables(null, null, "%", ["TABLE"] as String[])
                        while (tablesRs.next()) {
                            String tableName = tablesRs.getString("TABLE_NAME")
                            def tableData = [name: tableName, columns: []]

                            def pkSet = []
                            def pkRs = meta.getPrimaryKeys(null, null, tableName)
                            while(pkRs.next()) {
                                pkSet << pkRs.getString("COLUMN_NAME")
                            }
                            pkRs.close()

                            def colsRs = meta.getColumns(null, null, tableName, "%")
                            while(colsRs.next()) {
                                String colName = colsRs.getString("COLUMN_NAME")
                                String typeName = colsRs.getString("TYPE_NAME").toLowerCase()
                                int size = colsRs.getInt("COLUMN_SIZE")
                                boolean isNullable = colsRs.getInt("NULLABLE") == 1
                                boolean isPk = pkSet.contains(colName)

                                String mappedType = "varchar"
                                if (typeName.contains("int")) mappedType = "integer"
                                else if (typeName.contains("bool")) mappedType = "boolean"
                                else if (typeName.contains("time") || typeName.contains("date")) mappedType = "timestamp"
                                else if (typeName.contains("uuid")) mappedType = "uuid"
                                else if (typeName.contains("numeric") || typeName.contains("decimal") || typeName.contains("float") || typeName.contains("double")) mappedType = "decimal"

                                tableData.columns << [
                                        name: colName,
                                        type: mappedType,
                                        length: size,
                                        nullable: isNullable,
                                        pk: isPk
                                ]
                            }
                            colsRs.close()

                            result[tableName] = tableData
                        }
                        tablesRs.close()

                        sendResponse(e, MyJsonOutput.toJson(result), "application/json")
                    } catch (Exception ex) {
                        sendResponse(e, MyJsonOutput.toJson([error: true, message: ex.getMessage()]), "application/json", 500)
                    } finally {
                        if (conn != null) conn.close()
                    }
                }
            }
        }

        server.createContext("/api/export-to-excel"){e->handleRequest(e,"POST"){String b=e.requestBody.text;def jT=b.startsWith("json=")?URLDecoder.decode(b.substring(5),"UTF-8"):b;byte[]x=excelExporter.createExcelFromStructure(new JsonSlurper().parseText(jT));e.responseHeaders.add("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");e.responseHeaders.add("Content-Disposition","attachment; filename=\"json_structure.xlsx\"");e.sendResponseHeaders(200,x.length);e.responseBody.withStream{it.write(x)}}}
        server.createContext("/api/xml-to-excel"){e->handleRequest(e,"POST"){String b=e.requestBody.text;def xT=b.startsWith("xml=")?URLDecoder.decode(b.substring(4),"UTF-8"):b;byte[]x=excelExporter.createExcelFromStructure(converter.parse(xT,"xml"));e.responseHeaders.add("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");e.responseHeaders.add("Content-Disposition","attachment; filename=\"xml_structure.xlsx\"");e.sendResponseHeaders(200,x.length);e.responseBody.withStream{it.write(x)}}}

        server.createContext("/api/generate-example"){e->
            handleRequest(e,"POST"){
                boolean isFull = e.requestURI.query?.contains("full=true") ?: false
                def schemaText = e.requestBody.text
                sendResponse(e, exampleGenerator.generateExampleFromJsonSchema(schemaText, isFull), "application/json")
            }
        }

        server.createContext("/api/documents"){e->if(e.requestMethod=="GET")handleRequest(e,"GET"){sendResponse(e,MyJsonOutput.toJson(sql.rows("SELECT id, name FROM saved_documents ORDER BY name ASC")),"application/json")}else if(e.requestMethod=="POST")handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);def doc=sql.firstRow("SELECT id FROM saved_documents WHERE name=?",[d.name]);if(doc){sql.execute("UPDATE saved_documents SET content=?,last_updated=CURRENT_TIMESTAMP WHERE id=?",[d.content,doc.id])}else{sql.execute("INSERT INTO saved_documents (name,content) VALUES (?,?)",[d.name,d.content])};sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}
        server.createContext("/api/documents/"){e->def id=e.requestURI.path.split('/').last();if(e.requestMethod=="GET")handleRequest(e,"GET"){def d=sql.firstRow("SELECT content FROM saved_documents WHERE id=?",[id]);if(d)sendResponse(e,d.content,"text/plain")else sendResponse(e,"", "text/plain", 404)}else if(e.requestMethod=="DELETE")handleRequest(e,"DELETE"){sql.execute("DELETE FROM saved_documents WHERE id=?",[id]);sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}
        server.createContext("/api/xsd-to-wsdl"){e->handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);sendResponse(e,xsdToWsdlConverter.generate(d.xsdContent,d.xsdFileName,d.serviceType),"application/xml")}}
        server.createContext("/api/tasks/settings"){e->if(e.requestMethod=="GET")handleRequest(e,"GET"){def s=[:];sql.eachRow("SELECT key,value FROM task_settings"){r->s[r.key]=r.value};sendResponse(e,MyJsonOutput.toJson(s),"application/json")}else if(e.requestMethod=="POST")handleRequest(e,"POST"){def d=new JsonSlurper().parse(e.requestBody);d.each{k,v->sql.execute("INSERT OR REPLACE INTO task_settings (key, value) VALUES (?, ?)",[k, v])};sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}

        server.createContext("/api/tasks") { e ->
            def userId = e.requestHeaders.getFirst("X-User-Id")
            def role = e.requestHeaders.getFirst("X-User-Role")

            if (e.requestMethod == "GET") {
                handleRequest(e, "GET") {
                    def t = []

                    if (!userId) {
                        sendResponse(e, MyJsonOutput.toJson([error: "Необходима авторизация"]), "application/json", 401)
                        return
                    }

                    if (role == 'admin') {
                        t = sql.rows("""
                            SELECT t.*, u.name as owner_name 
                            FROM tasks t 
                            LEFT JOIN users u ON t.owner_id = u.id 
                            ORDER BY t.last_updated DESC
                        """)
                    } else {
                        t = sql.rows("""
                            SELECT t.*, u.name as owner_name 
                            FROM tasks t 
                            LEFT JOIN users u ON t.owner_id = u.id 
                            WHERE t.owner_id = ? 
                            ORDER BY t.last_updated DESC
                        """, [userId ? userId as Integer : -1])
                    }
                    sendResponse(e, MyJsonOutput.toJson(t), "application/json")
                }
            }
            else if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    def pathStr = e.requestURI.path

                    if (pathStr.contains("/copy-flows/")) {
                        def parts = pathStr.split("/")
                        int targetId = parts[parts.length - 3] as Integer
                        int sourceId = parts[parts.length - 1] as Integer

                        sql.withTransaction {
                            sql.execute("DELETE FROM integration_flows WHERE task_id = ?", [targetId])
                            sql.execute("DELETE FROM flow_details WHERE task_id = ?", [targetId])

                            def sourceFlows = sql.rows("SELECT * FROM integration_flows WHERE task_id = ?", [sourceId])
                            sourceFlows.each { sf ->
                                sql.executeInsert("INSERT INTO integration_flows (task_id, sender, receiver, order_num) VALUES (?, ?, ?, ?)",
                                        [targetId, sf.sender, sf.receiver, sf.order_num])
                            }

                            def sourceDetails = sql.rows("SELECT * FROM flow_details WHERE task_id = ?", [sourceId])
                            sourceDetails.each { sd ->
                                sql.executeInsert("""
                                    INSERT INTO flow_details (task_id, flow_order, protocol, format, connection_type, description, operation_name, service_description, target_description, protocol_out, format_out)
                                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                                """, [targetId, sd.flow_order, sd.protocol, sd.format, sd.connection_type, sd.description, sd.operation_name, sd.service_description, sd.target_description, sd.protocol_out, sd.format_out])

                                def newDetailId = sql.firstRow("SELECT last_insert_rowid() as id").id

                                try {
                                    def sourceMappings = sql.rows("SELECT * FROM flow_mappings WHERE flow_id = ?", [sd.id])
                                    sourceMappings.each { sm ->
                                        sql.executeInsert("INSERT INTO flow_mappings (flow_id, source_path, target_path, mapping_type, transformation_rule) VALUES (?, ?, ?, ?, ?)",
                                                [newDetailId, sm.source_path, sm.target_path, sm.mapping_type, sm.transformation_rule])
                                    }
                                } catch (Exception ex) {}
                            }
                        }

                        sendResponse(e, MyJsonOutput.toJson([status: "OK", message: "Потоки скопированы"]), "application/json")
                        return
                    }

                    if (pathStr.contains("/copy-spec/")) {
                        def parts = pathStr.split("/")
                        int targetId = parts[parts.length - 3] as Integer
                        int sourceId = parts[parts.length - 1] as Integer

                        def sourceDocName = "SPEC_DOC_TASK_${sourceId}"
                        def targetDocName = "SPEC_DOC_TASK_${targetId}"

                        def sourceDoc = sql.firstRow("SELECT content FROM saved_documents WHERE name = ?", [sourceDocName])

                        if (sourceDoc) {
                            def targetDoc = sql.firstRow("SELECT id FROM saved_documents WHERE name = ?", [targetDocName])
                            if (targetDoc) {
                                sql.execute("UPDATE saved_documents SET content = ?, last_updated=CURRENT_TIMESTAMP WHERE id = ?", [sourceDoc.content, targetDoc.id])
                            } else {
                                sql.execute("INSERT INTO saved_documents (name, content) VALUES (?, ?)", [targetDocName, sourceDoc.content])
                            }
                            sendResponse(e, MyJsonOutput.toJson([status: "OK", message: "ТЗ скопировано"]), "application/json")
                        } else {
                            sendResponse(e, MyJsonOutput.toJson([error: "У задачи-донора еще нет сохраненного ТЗ!"]), "application/json", 400)
                        }
                        return
                    }

                    def bodyText = e.requestBody.text
                    if (!bodyText || bodyText.trim().isEmpty()) {
                        sendResponse(e, MyJsonOutput.toJson([error: "Пустое тело запроса!"]), "application/json", 400)
                        return
                    }

                    def payload = new JsonSlurper().parseText(bodyText)

                    if (payload.id) {
                        def currentVersion = payload.version ?: 1

                        def updatedRows = sql.executeUpdate("""
                            UPDATE tasks 
                            SET "user"=?, task_url=?, task_number=?, stage=?, status=?, 
                                deployment_date=?, planned_dev_date=?, start_dev_date=?, test_deployment_date=?, release_date=?, user_notes=?, additional_comment=?, 
                                spec_url=?, contact_person=?, 
                                sender_sys=?, receiver_sys=?, interface_name=?, 
                                connection_type=?, input_format=?, output_format=?, search_tags=?,
                                last_updated=CURRENT_TIMESTAMP, version = version + 1 
                            WHERE id=? AND (version=? OR version IS NULL)
                        """, [
                                payload.user, payload.task_url, payload.task_number, payload.stage, payload.status,
                                payload.deployment_date, payload.planned_dev_date, payload.start_dev_date, payload.test_deployment_date, payload.release_date, payload.user_notes, payload.additional_comment,
                                payload.spec_url, payload.contact_person,
                                payload.sender_sys, payload.receiver_sys, payload.interface_name,
                                payload.connection_type, payload.input_format, payload.output_format, payload.search_tags,
                                payload.id, currentVersion
                        ])

                        if (updatedRows == 0) {
                            sendResponse(e, MyJsonOutput.toJson([error: "Конфликт версий! Кто-то другой уже изменил эту задачу."]), "application/json", 409)
                            return
                        }
                    } else {
                        sql.executeInsert("""
                            INSERT INTO tasks (
                                "user", task_url, task_number, stage, status, deployment_date, planned_dev_date, start_dev_date, test_deployment_date, release_date, user_notes,
                                additional_comment, spec_url, contact_person, 
                                sender_sys, receiver_sys, interface_name, connection_type, input_format, output_format, search_tags, 
                                version, owner_id
                            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 1, ?)
                        """, [
                                payload.user, payload.task_url, payload.task_number, payload.stage, payload.status,
                                payload.deployment_date, payload.planned_dev_date, payload.start_dev_date, payload.test_deployment_date, payload.release_date, payload.user_notes,
                                payload.additional_comment,
                                payload.spec_url, payload.contact_person,
                                payload.sender_sys, payload.receiver_sys, payload.interface_name,
                                payload.connection_type, payload.input_format, payload.output_format, payload.search_tags,
                                userId ? userId as Integer : 1
                        ])
                    }
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
            }
            else if (e.requestMethod == "DELETE") {
                handleRequest(e, "DELETE") {
                    def d = new JsonSlurper().parse(e.requestBody)
                    sql.execute("DELETE FROM tasks WHERE id = ?", [d.id])
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
            }
        }

        server.createContext("/api/tasks/send"){
            e->handleRequest(e,"POST"){
                def s=[:];sql.eachRow("SELECT key,value FROM task_settings"){
                    r->s[r.key]=r.value};
                def t=sql.rows("SELECT * FROM tasks");
                String auth="${s.target_user}:${s.target_password?:''}";
                String enc=Base64.encoder.encodeToString(auth.bytes);
                def c=HttpClient.newHttpClient();
                def req=HttpRequest.newBuilder().uri(URI.create(s.target_url)).header("Content-Type","application/json").header("Authorization","Basic "+enc).POST(HttpRequest.BodyPublishers.ofString(MyJsonOutput.toJson(t))).build();
                def res=c.send(req,HttpResponse.BodyHandlers.ofString());
                sendResponse(e,MyJsonOutput.toJson([statusCode:res.statusCode(),responseBody:res.body()]),"application/json")}}

        server.createContext("/api/tasks/export"){e->handleRequest(e,"GET"){Workbook wb=new XSSFWorkbook();Sheet s=wb.createSheet("Задачи");Row hr=s.createRow(0);def h=["ID","Пользователь","Номер задачи","URL задачи","Этап","Статус","Дата установки(PROD)","Дата установки(TEST)","План ОКОНЧАНИЕ","План НАЧАЛО","Дата релиза","Заметки","Комментарий","Последнее обновление","Спецификация","Контакт"];h.eachWithIndex{hd,i->hr.createCell(i).setCellValue(hd)};def t=sql.rows("SELECT * FROM tasks ORDER BY id DESC");t.eachWithIndex{tk,i->Row r=s.createRow(i+1);r.createCell(0).setCellValue(tk.id.toString());r.createCell(1).setCellValue(tk.user);r.createCell(2).setCellValue(tk.task_number);r.createCell(3).setCellValue(tk.task_url);r.createCell(4).setCellValue(tk.stage);r.createCell(5).setCellValue(tk.status);r.createCell(6).setCellValue(tk.deployment_date);r.createCell(7).setCellValue(tk.test_deployment_date);r.createCell(8).setCellValue(tk.planned_dev_date);r.createCell(9).setCellValue(tk.start_dev_date);r.createCell(10).setCellValue(tk.release_date);r.createCell(11).setCellValue(tk.user_notes);r.createCell(12).setCellValue(tk.additional_comment);r.createCell(13).setCellValue(tk.last_updated.toString());r.createCell(14).setCellValue(tk.spec_url);r.createCell(15).setCellValue(tk.contact_person)};h.size().times{s.autoSizeColumn(it)};def os=new ByteArrayOutputStream();wb.write(os);wb.close();byte[] b=os.toByteArray();e.responseHeaders.add("Content-Type","application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");e.responseHeaders.add("Content-Disposition","attachment; filename=\"tasks_export.xlsx\"");e.sendResponseHeaders(200,b.length);e.responseBody.withStream{it.write(b)}}}

        server.createContext("/api/labels/all"){e->handleRequest(e,"GET"){sendResponse(e,MyJsonOutput.toJson(labelsManager.getAll()),"application/json")}}

        server.createContext("/api/labels/search"){ e ->
            handleRequest(e, "GET") {
                def p = [:]
                if (e.requestURI.query) {
                    e.requestURI.query.split('&').each { pa ->
                        def parts = pa.split('=', 2)
                        def key = URLDecoder.decode(parts[0], "UTF-8")
                        def val = (parts.length > 1) ? URLDecoder.decode(parts[1], "UTF-8") : ""
                        p[key] = val
                    }
                }
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

        server.createContext("/api/history/item/"){e->handleRequest(e,"DELETE"){def id=e.requestURI.path.split('/').last();sql.execute("DELETE FROM call_history WHERE id=?",[id as int]);sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}

        server.createContext("/api/alm/fetch") { e ->
            handleRequest(e, "GET") {
                def query = e.requestURI.query
                if (!query || !query.contains("task_id=")) {
                    sendResponse(e, MyJsonOutput.toJson([error: true, message: "Не указан task_id"]), "application/json", 400)
                    return
                }

                def taskId = URLDecoder.decode(query.split("task_id=")[1].split("&")[0], "UTF-8")

                try {
                    String almDataJson = fetchTaskFromALM(taskId)
                    sendResponse(e, almDataJson, "application/json")
                } catch (Exception ex) {
                    sendResponse(e, MyJsonOutput.toJson([error: true, message: ex.getMessage()]), "application/json", 500)
                }
            }
        }

        server.createContext("/api/history/pin") { e ->
            handleRequest(e, "POST") {
                def d = new JsonSlurper().parseText(e.requestBody.text)
                sendResponse(e, MyJsonOutput.toJson(historyManager.togglePin(d.id as int, d.comment, d.is_pinned as int)), "application/json")
            }
        }

        server.createContext("/api/history/search") { e ->
            handleRequest(e, "GET") {
                def query = e.requestURI.query
                def p = [:]
                if (query) {
                    p = query.split('&').collectEntries { pa ->
                        def parts = pa.split('=', 2)
                        [(URLDecoder.decode(parts[0], "UTF-8")): (parts.length > 1) ? URLDecoder.decode(parts[1], "UTF-8") : ""]
                    }
                }
                sendResponse(e, MyJsonOutput.toJson(historyManager.search(p)), "application/json")
            }
        }

        server.createContext("/api/history/group"){e->if(e.requestMethod=="GET")handleRequest(e,"GET"){def cs=URLDecoder.decode(e.getRequestURI().getQuery().split("=")[1],"UTF-8");sendResponse(e,MyJsonOutput.toJson(historyManager.getGroup(cs)),"application/json")}else if(e.requestMethod=="DELETE")handleRequest(e,"DELETE"){def d=new JsonSlurper().parse(e.requestBody);sql.execute("DELETE FROM call_history WHERE cs_name=?",[d.csName]);sendResponse(e,MyJsonOutput.toJson([status:"OK"]),"application/json")}}

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

                    sql.execute("DELETE FROM integration_flows WHERE task_id = ?", [taskId as Integer])

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

                    if (detail) {
                        def mappingsDb = sql.rows("SELECT * FROM flow_mappings WHERE flow_id = ?", [detail.id])
                        def formattedMappings = mappingsDb.collect { m ->
                            [ id: m.id, s: m.source_path, t: m.target_path, type: m.mapping_type, comm: m.transformation_rule ]
                        }
                        def result = detail as Map
                        result.file_path = MyJsonOutput.toJson(formattedMappings)
                        sendResponse(e, MyJsonOutput.toJson(result), "application/json")
                    } else {
                        sendResponse(e, MyJsonOutput.toJson([:]), "application/json")
                    }
                }
            }
            else if (e.requestMethod == "POST") {
                handleRequest(e, "POST") {
                    def data = new JsonSlurper().parse(e.requestBody)
                    def taskId = data.task_id as Integer
                    def flowOrder = data.flow_order as Integer

                    sql.withTransaction {
                        def existing = sql.firstRow("SELECT id FROM flow_details WHERE task_id = ? AND flow_order = ?", [taskId, flowOrder])
                        def currentFlowId = null

                        if (existing) {
                            currentFlowId = existing.id
                            sql.executeUpdate("""UPDATE flow_details SET 
                        protocol=?, format=?, connection_type=?, description=?,
                        operation_name=?, service_description=?,
                        target_description=?, protocol_out=?, format_out=?
                        WHERE id=?""",
                                    [data.protocol, data.format, data.connection_type, data.description,
                                     data.operation_name, data.service_description,
                                     data.target_description, data.protocol_out, data.format_out,
                                     currentFlowId])
                        } else {
                            sql.executeInsert("""INSERT INTO flow_details 
                        (task_id, flow_order, protocol, format, connection_type, description, operation_name, service_description, target_description, protocol_out, format_out)
                        VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                                    [taskId, flowOrder, data.protocol, data.format, data.connection_type, data.description,
                                     data.operation_name, data.service_description,
                                     data.target_description, data.protocol_out, data.format_out])
                            currentFlowId = sql.firstRow("SELECT last_insert_rowid() as id").id
                        }

                        if (data.mappings != null) {
                            sql.execute("DELETE FROM flow_mappings WHERE flow_id = ?", [currentFlowId])
                            if (data.mappings.size() > 0) {
                                def insertQry = "INSERT INTO flow_mappings (flow_id, source_path, target_path, mapping_type, transformation_rule) VALUES (?, ?, ?, ?, ?)"
                                sql.withBatch(100, insertQry) { stmt ->
                                    data.mappings.each { m ->
                                        stmt.addBatch([currentFlowId, m.s, m.t, m.type, m.comm])
                                    }
                                }
                            }
                        }
                    }
                    sendResponse(e, MyJsonOutput.toJson([status: "OK"]), "application/json")
                }
            }
        }

        server.start()
    }

    static void handleRequest(HttpExchange e, String m, Closure b){ try { if (e.requestMethod != m) { e.sendResponseHeaders(405, -1); return }; b.call() } catch (Exception x) { println "Ошибка: ${x.toString()}"; sendResponse(e, MyJsonOutput.toJson([error:"Ошибка на сервере", message:x.toString()]), "application/json", 500) } }
    static void sendResponse(HttpExchange e, String b, String c, int s = 200){ def bytes = b.getBytes(StandardCharsets.UTF_8); e.responseHeaders.add("Content-Type", "$c; charset=utf-8"); e.sendResponseHeaders(s, bytes.length); e.responseBody.withStream { it.write(bytes) } }
}