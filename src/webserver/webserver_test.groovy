package webserver

import com.sun.net.httpserver.HttpServer

// --- Настройки сервера ---
int port = 8080
HttpServer server = HttpServer.create(new InetSocketAddress(port), 0)

// --- 1. Обработчик, который отдает страницу в браузер (GET-запрос) ---
// Он сработает, когда вы откроете http://localhost:8080/
server.createContext('/') { httpExchange ->

    // ЭТО ДАННЫЕ ОТ GROOVY ДЛЯ СТРАНИЦЫ
    String dataFromGroovy = "Привет из Groovy! Текущее время на сервере: ${new Date()}"
    String userNameFromGroovy = "Пользователь123"

    // Генерируем HTML-страницу "на лету"
    String htmlPage = """
    <!DOCTYPE html>
    <html lang="ru">
    <head>
        <meta charset="UTF-8">
        <title>Groovy <-> Web</title>
        <style>
            body { font-family: sans-serif; line-height: 1.6; padding: 20px; }
            .data-from-groovy { background-color: #e0f7fa; padding: 15px; border-left: 5px solid #00bcd4; }
            form { margin-top: 20px; }
            input[type=text], input[type=submit] { padding: 10px; font-size: 16px; }
        </style>
    </head>
    <body>
        <h1>Обмен данными между Groovy и веб-страницей</h1>

        <!-- Блок, который отображает данные, полученные от Groovy -->
        <div class="data-from-groovy">
            <p><strong>Сообщение от сервера:</strong> ${dataFromGroovy}</p>
            <p><strong>Имя пользователя от сервера:</strong> ${userNameFromGroovy}</p>
        </div>
        
        <hr>

        <h2>Отправка данных обратно в Groovy</h2>
        <!-- Эта форма отправит данные обработчику /submit методом POST -->
        <form action="/submit" method="POST">
            <label for="message">Введите сообщение для Groovy:</label><br>
            <input type="text" id="message" name="messageFromUser" size="50" value="Это мое сообщение!">
            <br><br>
            
            <!-- КНОПКА №2: Отправляет данные ИЗ страницы В Groovy -->
            <input type="submit" value="Отправить в Groovy">
        </form>

    </body>
    </html>
    """

    // Отправляем сгенерированную страницу в браузер
    httpExchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
    httpExchange.sendResponseHeaders(200, htmlPage.bytes.length)
    httpExchange.responseBody.withWriter('UTF-8') { it << htmlPage }
}


// --- 2. Обработчик, который принимает данные со страницы (POST-запрос) ---
// Он сработает, когда вы нажмете кнопку "Отправить в Groovy" на странице
server.createContext('/submit') { httpExchange ->

    // Читаем данные, которые пришли из формы
    String requestBody = httpExchange.requestBody.getText('UTF-8')

    // Парсим данные формы (они приходят в виде "key=value&key2=value2")
    Map formData = requestBody.split('&').collectEntries {
        def pair = it.split('=', 2)
        [ (URLDecoder.decode(pair[0], 'UTF-8')) : URLDecoder.decode(pair[1], 'UTF-8') ]
    }

    String userMessage = formData.messageFromUser

    // Выводим полученное сообщение в консоль, где запущен скрипт
    println ">>> Groovy получил сообщение от пользователя: '${userMessage}'"

    // Формируем страницу-ответ для пользователя
    String responsePage = """
    <!DOCTYPE html>
    <html lang="ru">
    <head><meta charset="UTF-8"><title>Ответ от Groovy</title></head>
    <body>
        <h1>Сообщение получено!</h1>
        <p>Groovy успешно принял ваши данные: <strong>${userMessage}</strong></p>
        <a href="/">Вернуться на главную</a>
    </body>
    </html>
    """

    // Отправляем ответ в браузер
    httpExchange.responseHeaders.set("Content-Type", "text/html; charset=utf-8")
    httpExchange.sendResponseHeaders(200, responsePage.bytes.length)
    httpExchange.responseBody.withWriter('UTF-8') { it << responsePage }
}

// Запускаем сервер и ждем подключений
server.start()
println "Сервер запущен на http://localhost:${port}"