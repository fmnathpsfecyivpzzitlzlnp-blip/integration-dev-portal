import groovy.json.JsonSlurper
import groovy.json.JsonBuilder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Base64

class FesbClient {
    String baseUrl
    String authHeader

    FesbClient(String host, int port, String username, String password) {
        this.baseUrl = "http://${host}:${port}/manager/v3"
        String auth = "${username}:${password}"
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes())
    }

    private String sendRequest(String path, String method, String body = null) {
        HttpClient client = HttpClient.newHttpClient()

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create("${baseUrl}${path}"))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .method(method, body ? HttpRequest.BodyPublishers.ofString(body) : HttpRequest.BodyPublishers.noBody())

        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body()
        } else {
            throw new Exception("API Error: ${response.statusCode()} - ${response.body()}")
        }
    }

    // --- Broker / Domains ---

    def getDomains() {
        String json = sendRequest("/api/domain", "GET")
        return new JsonSlurper().parseText(json)
    }

    // --- Broker / СОПС (Routes) ---

    def startRoute(String domainGuid, String routeGuid) {
        println "Attempting to start route ${routeGuid} in domain ${domainGuid}..."
        return sendRequest("/api/broker/domain/${domainGuid}/route/${routeGuid}/start", "POST")
    }

    def stopRoute(String domainGuid, String routeGuid) {
        println "Attempting to stop route ${routeGuid}..."
        return sendRequest("/api/broker/domain/${domainGuid}/route/${routeGuid}/stop", "POST")
    }

    // --- System Info ---

    def getSystemUsage() {
        String json = sendRequest("/api/json/stat/systemUsage", "GET")
        return new JsonSlurper().parseText(json)
    }
}

// --- Execution Logic ---

try {
    // Initialize client (Adjust host, port, and credentials)
    def fesb = new FesbClient("localhost", 8181, "admin", "password")

    // 1. Check System Health
    def stats = fesb.getSystemUsage()
    println "### System Health ###"
    println "CPU Usage: ${stats.systemUsage?.cpuLoad}%"
    println "Heap Used: ${stats.systemUsage?.heapMemoryUsage?.used / 1024 / 1024} MB"
    println "---"

    // 2. List all Domains
    println "### Domains List ###"
    def domains = fesb.getDomains()
    domains.each { domain ->
        println "Domain: ${domain.name} | GUID: ${domain.guid} | Status: ${domain.status}"
    }

    // 3. Example: Restart a specific route if needed
    // fesb.stopRoute("domain-guid-here", "route-guid-here")
    // fesb.startRoute("domain-guid-here", "route-guid-here")

} catch (Exception e) {
    println "Error occurred: ${e.message}"
}
