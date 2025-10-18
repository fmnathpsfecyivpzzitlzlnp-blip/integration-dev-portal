// --- НАЧАЛО НОВОГО СКРИПТА ---

package multipleRequest

// Стандартные импорты Groovy
import groovy.json.JsonSlurper
import groovy.xml.XmlUtil
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

// Стандартные импорты Java
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.UUID
import java.util.Random

// ДЛЯ REST / JSON ЗАПРОСОВ
def restJsonConfig = [
        url: 'https://httpbin.org/post',
        method: 'POST',
        messageType: 'JSON',
        numberOfRequests: 5,
        headers: ['Content-Type': 'application/json', 'Accept': 'application/json'],
        template: '''
        {
            "user": {
                "id": "##userId##",
                "name": "##userName##"
            },
            "transactionId": "##transactionId##"
        }
    ''',
        dataRules: [
                userId: [type: 'increment', start: 1001, step: 1],
                userName: [type: 'random', values: ['Alice', 'Bob', 'Charlie']],
                transactionId: [type: 'uuid']
        ],
        logSettings: [
                logIndividualFiles: true,
                logStackTrace: true
        ]
]

// ДЛЯ SOAP / XML ЗАПРОСОВ
def soapXmlConfig = [
        url: 'https://www.dataaccess.com/webservicesserver/NumberConversion.wso', // URL SOAP-сервиса
        method: 'POST', // SOAP всегда использует POST
        messageType: 'XML',
        numberOfRequests: 3,
        headers: [
                'Content-Type': 'text/xml; charset=utf-8',
                // SOAPAction часто является обязательным для SOAP 1.1 и указывает, какую операцию мы вызываем
                'SOAPAction': 'http://www.dataaccess.com/webservicesserver/NumberToWords'
        ],
        template: '''<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <NumberToWords xmlns="http://www.dataaccess.com/webservicesserver/">
      <ubiNum>##number##</ubiNum>
    </NumberToWords>
  </soap:Body>
</soap:Envelope>''', // <-- ШАБЛОН ВАШЕГО SOAP-СООБЩЕНИЯ
        dataRules: [
                // Правило для замены данных в шаблоне
                number: [type: 'random_number', min: 1, max: 1000]
        ],
        logSettings: [
                logIndividualFiles: true,
                logStackTrace: true
        ]
]


def executeRequests(config) {
    // 1. Создаем HTTP-клиент. Он создается один раз и используется для всех запросов.
    def client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2) // Предпочитать современный протокол HTTP/2
            .connectTimeout(Duration.ofSeconds(10)) // Таймаут на подключение
            .build()

    def mainLogFile = new File("main_log.txt")
    mainLogFile.write("")

    println "Начинаю выполнение ${config.numberOfRequests} запросов к ${config.url}..."

    (1..config.numberOfRequests).each { i ->
        def requestBody = generateRequestBody(config.template, config.dataRules, i)
        def logEntry = [:]
        logEntry.requestTimestamp = new Date().toString()
        logEntry.requestBody = requestBody

        try {
            // 2. Собираем запрос для каждой итерации с помощью Java HttpRequest.Builder
            def requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(config.url))
                    .timeout(Duration.ofSeconds(20)) // Таймаут на весь запрос

            // Добавляем заголовки из конфигурации
            config.headers.each { key, value ->
                requestBuilder.header(key, value)
            }

            // Устанавливаем метод и тело запроса.
            // BodyPublishers.ofString() - стандартный способ отправить строку.
            if (config.method.equalsIgnoreCase('POST')) {
                requestBuilder.POST(HttpRequest.BodyPublishers.ofString(requestBody))
            } else if (config.method.equalsIgnoreCase('GET')) {
                requestBuilder.GET()
            } // Можно добавить PUT, DELETE и т.д. по аналогии

            def request = requestBuilder.build()

            // 3. Отправляем запрос и получаем ответ.
            // BodyHandlers.ofString() - стандартный способ получить тело ответа в виде строки.
            def response = client.send(request, HttpResponse.BodyHandlers.ofString())

            // 4. Записываем результат в лог
            logEntry.responseStatus = response.statusCode()
            logEntry.responseBody = response.body()

        } catch (Exception e) {
            logEntry.error = e.getMessage()
            if (config.logSettings.logStackTrace) {
                // Преобразуем стектрейс в строку для логирования
                def sw = new StringWriter()
                e.printStackTrace(new PrintWriter(sw))
                logEntry.stackTrace = sw.toString()
            }
        }

        def logString = formatLogEntry(logEntry)
        if (config.logSettings.logIndividualFiles) {
            new File("request_${i}.log").write(logString)
        }
        mainLogFile.append(logString + "\n" + "="*40 + "\n")
        println "Выполнен запрос ${i}/${config.numberOfRequests}... Статус: ${logEntry.responseStatus ?: 'ОШИБКА'}"
    }
    println "Работа завершена. Смотрите результаты в файле main_log.txt и отдельных файлах request_*.log"
}

def generateRequestBody(template, rules, iteration) {
    def body = template
    rules.each { key, rule ->
        def placeholder = "##${key}##"
        def value
        switch (rule.type) {
            case 'increment':
                value = (rule.start + (rule.step * (iteration - 1)))
                break
            case 'random':
                value = rule.values[new Random().nextInt(rule.values.size())]
                break
            case 'uuid':
                value = UUID.randomUUID().toString()
                break
            case 'current_timestamp':
                value = new Date().format(rule.format ?: 'yyyy-MM-dd HH:mm:ss')
                break
            case 'random_number':
                def min = rule.min ?: 0
                def max = rule.max ?: 100
                value = new Random().nextInt((max - min) + 1) + min
                break
            case 'from_list':
                value = rule.values[(iteration - 1) % rule.values.size()]
                break
        }
        if (value != null) {
            body = body.replace(placeholder, value.toString())
        }
    }
    return body
}

def formatLogEntry(entry) {
    def builder = new StringBuilder()
    builder.append("Timestamp: ${entry.requestTimestamp}\n")
    builder.append("Request Body:\n${entry.requestBody}\n")
    builder.append("---------------------------------\n")
    builder.append("Response Status: ${entry.responseStatus ?: 'N/A'}\n")
    builder.append("Response Body:\n${entry.responseBody ?: 'N/A'}\n")
    if (entry.error) {
        builder.append("Error: ${entry.error}\n")
    }
    if (entry.stackTrace) {
        builder.append("Stack Trace:\n${entry.stackTrace}\n")
    }
    return builder.toString()
}

// Запуск выполнения
//executeRequests(restJsonConfig)
executeRequests(soapXmlConfig)