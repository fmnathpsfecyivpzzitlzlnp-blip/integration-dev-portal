//
//// ---------------- НОВЫЕ ИМПОРТЫ (HTTP CLIENT 5) ----------------
//import org.apache.hc.client5.http.classic.methods.HttpGet
//import org.apache.hc.client5.http.classic.methods.HttpPost
//import org.apache.hc.core5.http.io.entity.StringEntity
//import org.apache.hc.client5.http.impl.classic.HttpClients
//import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder
//import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactory
//import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder
//import org.apache.hc.client5.http.ssl.NoopHostnameVerifier
//import org.apache.hc.client5.http.protocol.HttpClientContext
//import org.apache.hc.core5.http.io.entity.EntityUtils
//import org.apache.hc.core5.ssl.SSLContexts
//import org.apache.hc.core5.http.ContentType // Для указания типов
//import java.security.KeyStore
//import javax.net.ssl.SSLContext
//
//// ==========================================
//// 🛠 ПРИМЕР ИСПОЛЬЗОВАНИЯ
//// ==========================================
//
//def response = makeSecureRequest(
//        url: "https://example.com",
//        method: "GET",
//
//        // payload: "{ \"test\": 123 }", // Раскомментируй для POST
//
//        // Параметры TLS и сертификатов
//        certPath: null,  // Путь к .p12 или .jks
//        certPass: "changeit",
//        certType: "PKCS12", // Или "JKS"
//
//        // Версии протоколов (В версии 5 они задаются чуть строже)
//        tlsVersions: ["TLSv1.2", "TLSv1.3"] as String[],
//
//        debugLevel: "TLS_DEBUG" // NONE, INFO, FULL, TLS_DEBUG
//)
//
//println "\n--- FINAL RESULT ---"
//println "Status: ${response.status}"
//// println "Body: ${response.body}"
//
//
//// ==========================================
//// 📦 ОСНОВНАЯ ФУНКЦИЯ (HTTP CLIENT 5 VERSION)
//// ==========================================
//
//def makeSecureRequest(Map params) {
//    String url = params.url
//    String method = params.method ?: "GET"
//    String payload = params.payload
//    String debugLevel = params.debugLevel ?: "INFO"
//    String certType = params.certType ?: "PKCS12"
//    String[] tlsVersions = params.tlsVersions ?: ["TLSv1.2", "TLSv1.3"] as String[]
//
//    // --- 1. СИСТЕМНАЯ ОТЛАДКА ---
//    if (debugLevel == "TLS_DEBUG") {
//        println "\n🔥 [DEBUG] Enabling System javax.net.debug..."
//        println "   1. ClientHello: Клиент предлагает версии и шифры."
//        println "   2. ServerHello: Сервер выбирает шифр. Если нет общих — разрыв."
//        println "   3. Certificate chain: Цепочка сертификатов от сервера.\n"
//        System.setProperty("javax.net.debug", "ssl,handshake")
//    } else {
//        System.setProperty("javax.net.debug", "")
//    }
//
//    log(debugLevel, "INFO", "🚀 Request to: $url [Lib: HttpClient 5]")
//
//    try {
//        // --- 2. SSL CONTEXT ---
//        SSLContext sslContext
//        if (params.certPath) {
//            log(debugLevel, "INFO", "🔑 Loading Cert ($certType): ${params.certPath}")
//            KeyStore keyStore = KeyStore.getInstance(certType)
//            new File(params.certPath).withInputStream { keyStore.load(it, params.certPass.toCharArray()) }
//
//            sslContext = SSLContexts.custom()
//                    .loadKeyMaterial(keyStore, params.certPass.toCharArray())
//                    .loadTrustMaterial(null, { chain, authType -> true } as org.apache.hc.core5.ssl.TrustStrategy) // Trust All
//                    .build()
//        } else {
//            sslContext = SSLContexts.custom()
//                    .loadTrustMaterial(null, { chain, authType -> true } as org.apache.hc.core5.ssl.TrustStrategy)
//                    .build()
//        }
//
//        // --- 3. НАСТРОЙКА СОЕДИНЕНИЯ (Отличие v5) ---
//        // В v5 создание SocketFactory делается через Builder
//        def sslSocketFactory = SSLConnectionSocketFactoryBuilder.create()
//                .setSslContext(sslContext)
//                .setTlsVersions(tlsVersions) // Явное указание версий
//                .setHostnameVerifier(NoopHostnameVerifier.INSTANCE)
//                .build()
//
//        // Нужен ConnectionManager для управления SSL
//        def cm = PoolingHttpClientConnectionManagerBuilder.create()
//                .setSSLSocketFactory(sslSocketFactory)
//                .build()
//
//        def httpClient = HttpClients.custom()
//                .setConnectionManager(cm)
//                .build()
//
//        // --- 4. ЗАПРОС ---
//        def request
//        if (payload || method.equalsIgnoreCase("POST")) {
//            request = new HttpPost(url)
//            if (payload) {
//                // В v5 StringEntity требует ContentType
//                request.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON))
//                log(debugLevel, "FULL", "📦 Payload: $payload")
//            }
//        } else {
//            request = new HttpGet(url)
//        }
//
//        request.setHeader("User-Agent", "Groovy-HttpClient5-Tool")
//
//        // Basic Auth (Ручная сборка хедера, работает надежнее всего)
//        if (params.username && params.password) {
//            String auth = "${params.username}:${params.password}"
//            String encoded = Base64.getEncoder().encodeToString(auth.getBytes("UTF-8"))
//            request.setHeader("Authorization", "Basic $encoded")
//            log(debugLevel, "FULL", "🔒 Auth Header Added")
//        }
//
//        // --- 5. ВЫПОЛНЕНИЕ ---
//        HttpClientContext context = HttpClientContext.create()
//        long start = System.currentTimeMillis()
//
//        // execute возвращает CloseableHttpResponse
//        def responseObj = httpClient.execute(request, context)
//
//        long duration = System.currentTimeMillis() - start
//
//        // --- 6. АНАЛИЗ SSL ---
//        // В HttpClient 5 доступ к сокету сложнее, но SSLSession можно достать из контекста
//        def sslSession = context.getSSLSession()
//        if (sslSession) {
//            log(debugLevel, "INFO", "✅ TLS Handshake OK (${duration}ms)")
//            log(debugLevel, "INFO", "🛡️  Protocol: ${sslSession.getProtocol()}")
//            log(debugLevel, "INFO", "🔐 Cipher: ${sslSession.getCipherSuite()}")
//        } else {
//            // Если сессии нет в контексте (бывает при переиспользовании), но запрос прошел
//            log(debugLevel, "INFO", "✅ Request Finished (${duration}ms) - (Session details cached or hidden)")
//        }
//
//        int statusCode = responseObj.getCode() // В v5 getCode(), а не getStatusCode()
//        String responseBody = EntityUtils.toString(responseObj.getEntity())
//
//        log(debugLevel, "INFO", "⬅️ Response: $statusCode")
//        log(debugLevel, "FULL", "📄 Body: $responseBody")
//
//        return [status: statusCode, body: responseBody]
//
//    } catch (Exception e) {
//        log(debugLevel, "INFO", "❌ ERROR: ${e.message}")
//        if (debugLevel == "TLS_DEBUG" || debugLevel == "FULL") e.printStackTrace()
//        return [status: 0, error: e.message]
//    }
//}
//
//def log(String currentLevel, String requiredLevel, String message) {
//    int levelMap(String l) {
//        switch(l) {
//            case "NONE": return 0; case "INFO": return 1; case "FULL": return 2; case "TLS_DEBUG": return 3
//            default: return 1
//        }
//    }
//    if (levelMap(currentLevel) >= levelMap(requiredLevel)) println "[${requiredLevel}] $message"
//}