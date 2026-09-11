// ============================================================================
// ФАЙЛ 1: Services.groovy
// ----------------------------------------------------------------------------
// Архитектурный слой: БИЗНЕС-ЛОГИКА И СЕРВИСЫ
// Описание: В этот файл вынесены все независимые классы (утилиты, парсеры, 
// конвертеры, генераторы Excel и моков). Это делает основной серверный файл
// чистым и предотвращает потерю кода. Оригинальная логика сохранена 1-в-1.
// ============================================================================

import groovy.xml.XmlSlurper
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import groovy.xml.XmlParser
import groovy.xml.MarkupBuilder
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
import java.net.URI

// ============================================================================
// Утилита для шифрования и дешифрования чувствительных данных (паролей)
// Использует симметричное шифрование AES с жестко заданным ключом.
// ============================================================================
class CryptoUtil {
    private static final String ALGO = "AES"
    private static final byte[] KEY = "DevToolsSecret12".getBytes("UTF-8")

    // Шифрует входящую строку и возвращает Base64
    static String encrypt(String plainText) {
        if (!plainText) return plainText
        def cipher = javax.crypto.Cipher.getInstance(ALGO)
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(KEY, ALGO))
        return java.util.Base64.getEncoder().encodeToString(cipher.doFinal(plainText.getBytes("UTF-8")))
    }

    // Расшифровывает Base64 обратно в текст. При ошибке возвращает оригинал.
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

// ============================================================================
// Безопасная обертка над стандартным JsonOutput (предотвращает падения)
// ============================================================================
class MyJsonOutput {
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

// ============================================================================
// DataConverter: Мощный движок для преобразования данных.
// Умеет парсить XML/JSON, сериализовать их обратно и генерировать JSON Schema.
// ============================================================================
class DataConverter {
    // Основной метод чтения (парсинга) входящего текста
    def parse(String text, String format) { if (!text || text.trim().isEmpty()) throw new IllegalArgumentException("Входной текст не может быть пустым."); if (format == 'json') return new JsonSlurper().parseText(text); if (format == 'xml') return normalizeXmlNode(new XmlParser().parseText(text)); throw new IllegalArgumentException("Неподдерживаемый формат: ${format}") }

    // Рекурсивное преобразование XML-узлов во внутренние Map/List структуры
    private def normalizeXmlNode(node) { if (node instanceof String) return node; def children = node.children().findAll { !(it instanceof String && it.trim().isEmpty()) }; if (children.isEmpty() && !node.text().isEmpty()) return node.text(); def map = [:]; children.each { child -> if (child.respondsTo('name')) { def name = child.name(); def value = normalizeXmlNode(child); if (map.containsKey(name)) { if (!(map[name] instanceof List)) map[name] = [map[name]]; map[name].add(value) } else { map[name] = value } } }; if (node.attributes()) node.attributes().each { k, v -> map["@${k}"] = v }; if (map.isEmpty() && !node.text().isEmpty()) return node.text(); return map }

    // Превращение внутренних данных обратно в строку (JSON или XML)
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

    // Вспомогательный метод для построения XML через MarkupBuilder
    private void buildXml(MarkupBuilder builder, data) { if (data instanceof Map) { data.each { key, value -> if (key.startsWith('@')) return; if (value instanceof List) { value.each { item -> builder."$key" { buildXml(builder, item) } } } else { def attrs = (value instanceof Map) ? value.findAll { it.key.startsWith('@') }.collectEntries { k, v -> [k.substring(1), v] } : [:]; def children = (value instanceof Map) ? value.findAll { !it.key.startsWith('@') } : value; builder."$key"(attrs) { buildXml(builder, children) } } } } else if (data != null && !(data instanceof List)) { builder.mkp.yield(data.toString()) } }

    // Автогенерация базовой JSON Schema на основе готового JSON-файла
    def generateJsonSchema(jsonData) { def rootSchemaContent = determineSchema(jsonData, "Root"); return ["\$schema": "http://json-schema.org/draft-07/schema#", title: "Generated Schema", description: "Automatically generated schema"] + rootSchemaContent }

    // Рекурсивное вычисление типов полей для генератора схем
    private def determineSchema(value, String propertyName = "") { def baseSchema = [:]; if (propertyName) baseSchema.title = propertyName.capitalize(); if (value == null) return baseSchema + [type: ["string", "number", "boolean", "array", "object", "null"]]; if (value instanceof String) return baseSchema + [type: "string"]; if (value instanceof Number) return baseSchema + [type: "number"]; if (value instanceof Boolean) return baseSchema + [type: "boolean"]; if (value instanceof List) { def firstNonNull = value ? value.find {it != null} : null; def itemsSchema = determineSchema(firstNonNull, propertyName ? "${propertyName} Item" : "Array Item"); return baseSchema + [type: "array", items: itemsSchema] }; if (value instanceof Map) { def propertiesMap = value.collectEntries { k, v -> [k, determineSchema(v, k)] }; return baseSchema + [type: "object", properties: propertiesMap, required: value.keySet() as List, additionalProperties: false] }; return baseSchema + [type: ["string", "number", "boolean", "array", "object", "null"]] }
}

// ============================================================================
// Генератор Excel-отчетов на основе произвольного JSON
// ============================================================================
class ExcelExporter {
    private String getValueType(value) { if (value instanceof Map) return "object"; if (value instanceof List) return "array"; if (value instanceof String) { if (value.isNumber()) return value.contains('.') ? "decimal" : "integer"; return "string" }; if (value instanceof Number) return "integer"; if (value instanceof Boolean) return "boolean"; if (value == null) return "null"; return "unknown" }
    private void parseJsonStructure(String key, value, String level, List<Map> results) { String vT=getValueType(value); results.add(["Уровень":level,"Наименование":key,"Категория":"Element","Тип":vT,"Повторение":(vT=='array'?"0..n":"1..1"),"Описание":""]); if(vT=="object"){value.eachWithIndex{ck,cv,i->parseJsonStructure(ck,cv,"${level}.${i+1}",results)}}else if(vT=="array"&&value){def f=value.find{it!=null};if(f instanceof Map){f.eachWithIndex{ck,cv,i->parseJsonStructure(ck,cv,"${level}.${i+1}",results)}}} }
    byte[] createExcelFromStructure(Map data) { List<Map> rows=[]; data.eachWithIndex{k,v,i->parseJsonStructure(k,v,"${i+1}",rows)}; Workbook wb=new org.apache.poi.xssf.usermodel.XSSFWorkbook(); Sheet s=wb.createSheet("Structure"); Row hr=s.createRow(0); def hs=["Уровень", "Наименование", "Категория", "Тип", "Повторение", "Описание"]; hs.eachWithIndex{ h, i->hr.createCell(i).setCellValue(h)}; rows.eachWithIndex{rd,i-> Row r=s.createRow(i+1);rd.values().eachWithIndex{ v, j->r.createCell(j).setCellValue(v.toString())}}; hs.size().times{s.autoSizeColumn(it)}; def os=new ByteArrayOutputStream();wb.write(os);wb.close();return os.toByteArray()}
}

// ============================================================================
// Генератор Excel напрямую из WSDL/XSD файлов (читает complexType)
// ============================================================================
class WsdlExcelExporter {
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

// ============================================================================
// Генератор Excel-таблицы на основе JSON Schema
// ============================================================================
class JsonSchemaExcelExporter {
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

// ============================================================================
// Утилита для генерации примеров данных (Mock) на основе JSON Schema
// ============================================================================
class ExampleGenerator {
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

// ============================================================================
// MultiRequestExecutor: HTTP-клиент для массовой отправки запросов к API.
// Игнорирует SSL-ошибки (подходит для тестовых стендов) и сохраняет логи в историю.
// Примечание: Класс CallHistoryManager будет находиться в Database.groovy
// ============================================================================
class MultiRequestExecutor {
    private def historyManager

    MultiRequestExecutor(historyManager) { this.historyManager = historyManager }

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

    // Подстановка динамических параметров в тело запроса
    private String generateRequestBody(String template, Map rules, int iteration) { def body = template; rules?.each { key, rule -> def placeholder = "##${key}##"; def value; switch (rule.type) { case 'increment': value = (rule.start + (rule.step * (iteration - 1))); break; case 'random': value = rule.values[new Random().nextInt(rule.values.size())]; break; case 'uuid': value = UUID.randomUUID().toString(); break; case 'current_timestamp': value = new Date().format(rule.format ?: 'yyyy-MM-dd HH:mm:ss'); break; case 'random_number': def min = rule.min ?: 0; def max = rule.max ?: 100; value = new Random().nextInt((max - min) + 1) + min; break; case 'from_list': value = rule.values[(iteration - 1) % rule.values.size()]; break }; if (value != null) { body = body.replace(placeholder, value.toString()) } }; return body }

    // Форматирование лога для фронтенда
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

// ============================================================================
// Конвертер: Превращение XSD схемы в WSDL (SOAP) оболочку
// ============================================================================
class XsdToWsdlConverter {
    String generate(String xsdContent, String xsdFileName, String serviceType) {
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
                    rootElementNode = [name: { matcher[0][1] }]
                } else { throw new IllegalStateException("Не найден <element name='...'> в XSD.") }
            }
            String rootElementName = (rootElementNode instanceof Map) ? rootElementNode.name() : rootElementNode['@name'].text()

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
            e.printStackTrace()
            throw new RuntimeException("Ошибка: ${e.message}", e)
        }
    }
}