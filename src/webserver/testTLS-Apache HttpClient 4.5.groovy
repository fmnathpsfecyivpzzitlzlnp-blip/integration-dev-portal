//
//import org.apache.http.client.methods.HttpGet
//import org.apache.http.client.methods.HttpPost
//import org.apache.http.entity.StringEntity
//import org.apache.http.impl.client.HttpClients
//import org.apache.http.ssl.SSLContexts
//import org.apache.http.conn.ssl.NoopHostnameVerifier
//import org.apache.http.conn.ssl.SSLConnectionSocketFactory
//import org.apache.http.client.protocol.HttpClientContext
//import org.apache.http.util.EntityUtils
//import java.security.KeyStore
//import javax.net.ssl.SSLContext
//
//
////Если ты укажешь tlsVersions: ["TLSv1.0", "TLSv1.1"], а скрипт упадет с ошибкой "No appropriate protocol" — это значит, что твоя версия Java запрещает эти древние протоколы на уровне конфигурации безопасности (java.security).
////В этом случае, чтобы дебажить старье, тебе нужно при запуске Groovy/Java добавить параметры:
////-Djdk.tls.client.protocols="TLSv1,TLSv1.1"
//
//
//// ==========================================
//// 🛠 ПРИМЕР ИСПОЛЬЗОВАНИЯ (USAGE EXAMPLE)
//// ==========================================
//
//def response = makeSecureRequest(
//        url: "https://example.com/api/resource",
//        method: "POST",
//
//        // --- ПАРАМЕТРЫ ДАННЫХ (PAYLOAD) ---
//        // Если ты хочешь просто проверить связь (пинг) — передавай null.
//        // Если нужно отправить JSON — передавай строку "{ \"key\": \"value\" }"
//        payload: "{ \"test\": \"debug_run\" }",
//
//        // --- ПАРАМЕТРЫ АВТОРИЗАЦИИ ---
//        username: "admin",
//        password: "changeit",
//
//        // --- СЕРТИФИКАТЫ ---
//        certPath: "/path/to/certificate.p12",
//        certPass: "certPassword",
//        // Код ожидает формат .p12 (PKCS12). Если у тебя .jks, просто поменяй на "JKS"
//        certType: "PKCS12",
//
//        // --- НАСТРОЙКИ СЕТИ И TLS ---
//        // Здесь можно выбрать ["TLSv1.0", "TLSv1.1"] для старых систем или ["TLSv1.3"] для новых
//        tlsVersions: ["TLSv1.2", "TLSv1.3"] as String[],
//
//        // Уровни: NONE, INFO, FULL, TLS_DEBUG
//        debugLevel: "TLS_DEBUG"
//)
//
//println "\n--- FINAL RESULT ---"
//println "Status: ${response.status}"
//// println "Body: ${response.body}"
//
//
//// ==========================================
//// 📦 ОСНОВНАЯ ФУНКЦИЯ (CORE FUNCTION)
//// ==========================================
//
//def makeSecureRequest(Map params) {
//    String url = params.url
//    String method = params.method ?: "GET"
//    String payload = params.payload
//    String debugLevel = params.debugLevel ?: "INFO"
//    String certType = params.certType ?: "PKCS12" // По умолчанию PKCS12
//    String[] tlsVersions = params.tlsVersions ?: ["TLSv1.2", "TLSv1.3"] as String[]
//
//    // --- БЛОК 1: СИСТЕМНАЯ ОТЛАДКА JVM ---
//    if (debugLevel == "TLS_DEBUG") {
//        println "\n🔥 [DEBUG] Enabling System javax.net.debug for SSL Handshake inspection..."
//        println "ℹ️  Смотри в консоль (STDERR), там появятся системные логи JVM:"
//        println "   1. ClientHello: Что твой клиент предлагает серверу (какие версии TLS, какие алгоритмы)."
//        println "   2. ServerHello: Что сервер выбрал. Если сервер выберет то, что твой клиент не поддерживает — соединение разорвется."
//        println "   3. Certificate chain: Какую цепочку сертификатов отдал сервер.\n"
//
//        System.setProperty("javax.net.debug", "ssl,handshake")
//    } else {
//        System.setProperty("javax.net.debug", "")
//    }
//
//    log(debugLevel, "INFO", "🚀 Starting Request to: $url")
//    log(debugLevel, "INFO", "⚙️  Allowed Protocols: ${tlsVersions.join(', ')}")
//
//    try {
//        // --- БЛОК 2: НАСТРОЙКА SSL (Context & Keystore) ---
//        SSLContext sslContext
//        if (params.certPath) {
//            log(debugLevel, "INFO", "🔑 Loading Client Certificate ($certType) from: ${params.certPath}")
//
//            // Динамически выбираем тип хранилища: JKS или PKCS12
//            KeyStore keyStore = KeyStore.getInstance(certType)
//            new File(params.certPath).withInputStream { stream ->
//                keyStore.load(stream, params.certPass.toCharArray())
//            }
//
//            sslContext = SSLContexts.custom()
//                    .loadKeyMaterial(keyStore, params.certPass.toCharArray())
//                    .loadTrustMaterial(null, { chain, authType -> true } as org.apache.http.ssl.TrustStrategy) // Trust ALL (Debug only!)
//                    .build()
//        } else {
//            sslContext = SSLContexts.custom()
//                    .loadTrustMaterial(null, { chain, authType -> true } as org.apache.http.ssl.TrustStrategy)
//                    .build()
//        }
//
//        // --- БЛОК 3: НАСТРОЙКА СОКЕТОВ (Handshake config) ---
//        SSLConnectionSocketFactory sslsf = new SSLConnectionSocketFactory(
//                sslContext,
//                tlsVersions, // Сюда передаем массив версий, которые мы разрешаем (например, TLSv1.0)
//                null,
//                NoopHostnameVerifier.INSTANCE
//        )
//
//        def clientBuilder = HttpClients.custom().setSSLSocketFactory(sslsf)
//        def httpClient = clientBuilder.build()
//
//        // --- БЛОК 4: ФОРМИРОВАНИЕ ЗАПРОСА ---
//        def request
//        if (payload || method.equalsIgnoreCase("POST")) {
//            request = new HttpPost(url)
//            if (payload) {
//                // Если нужно отправить JSON — передавай строку "{ \"key\": \"value\" }"
//                request.setEntity(new StringEntity(payload, "UTF-8"))
//                request.setHeader("Content-Type", "application/json")
//                log(debugLevel, "FULL", "📦 Request Payload added (Length: ${payload.length()})")
//            } else {
//                // Если ты хочешь просто проверить связь (пинг) — передавай null (сюда не зайдем, но для логики ясно)
//                log(debugLevel, "FULL", "📦 Request Payload is NULL (Empty POST/GET)")
//            }
//        } else {
//            request = new HttpGet(url)
//        }
//
//        // Хедеры
//        request.setHeader("User-Agent", "Groovy-Debug-Tool/2.0")
//
//        // Basic Auth
//        if (params.username && params.password) {
//            String auth = "${params.username}:${params.password}"
//            String encoded = auth.bytes.encodeBase64().toString()
//            request.setHeader("Authorization", "Basic $encoded")
//            log(debugLevel, "FULL", "🔒 Added Basic Auth Header")
//        }
//
//        // --- БЛОК 5: ВЫПОЛНЕНИЕ ---
//        HttpClientContext context = HttpClientContext.create()
//        long start = System.currentTimeMillis()
//
//        def response = httpClient.execute(request, context)
//
//        long duration = System.currentTimeMillis() - start
//
//        // --- БЛОК 6: АНАЛИЗ РЕЗУЛЬТАТА ---
//        def socket = context.getConnection().getSocket()
//        if (socket instanceof javax.net.ssl.SSLSocket) {
//            def session = socket.getSession()
//            log(debugLevel, "INFO", "✅ SSL/TLS Handshake Successful (${duration}ms)")
//            log(debugLevel, "INFO", "🛡️  Negotiated Protocol: ${session.getProtocol()}")
//            log(debugLevel, "INFO", "🔐 Negotiated Cipher: ${session.getCipherSuite()}")
//        }
//
//        int statusCode = response.getStatusLine().getStatusCode()
//        String responseBody = EntityUtils.toString(response.getEntity())
//
//        log(debugLevel, "INFO", "⬅️ Response Code: $statusCode")
//        log(debugLevel, "FULL", "📄 Response Body: $responseBody")
//
//        return [status: statusCode, body: responseBody, protocol: context.getConnection().getSocket()?.getSession()?.getProtocol()]
//
//    } catch (Exception e) {
//        log(debugLevel, "INFO", "❌ CONNECTION ERROR: ${e.message}")
//        if (debugLevel == "TLS_DEBUG" || debugLevel == "FULL") {
//            e.printStackTrace()
//        }
//        return [status: 0, error: e.message]
//    }
//}
//
//def log(String currentLevel, String requiredLevel, String message) {
//    int levelMap(String l) {
//        switch(l) {
//            case "NONE": return 0
//            case "INFO": return 1
//            case "FULL": return 2
//            case "TLS_DEBUG": return 3
//            default: return 1
//        }
//    }
//    if (levelMap(currentLevel) >= levelMap(requiredLevel)) {
//        println "[${requiredLevel}] $message"
//    }
//}