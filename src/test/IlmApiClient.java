package test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class IlmApiClient {

//    Шаг 1. Реверс-инжиниринг внутреннего API
//
//    Откройте консоль разработчика в браузере (F12), перейдите на вкладку Network (Сеть) и отфильтруйте запросы по Fetch/XHR.
//
//    Обновите страницу ILM-системы и долистайте до появления таблицы с задачами.
//
//    Найдите запрос, в ответе которого приходят нужные текстовые данные (это может быть JSON, XML или специфичный текстовый формат GWT-RPC, обычно начинающийся на //OK или //EX).
//
//    Изучите вкладки Headers и Payload этого запроса: вам понадобятся точный URL, метод (обычно POST), специфичные заголовки (особенно Content-Type и X-GWT-Permutation) и структура тела запроса, где зашиты параметры пагинации.

    public static void main(String[] args) throws Exception {
        // точный URL из вкладки Network
        String targetUrl = "https://alm.headoffice.psbank.local/sd/operator/gwt-rpc-endpoint";
        String user = "DOMAIN_USER";
        String password = "DOMAIN_PASS";

        String authHeader = "Basic " + Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));

        // Тело запроса из вкладки Payload.
        // В GWT-RPC это часто длинная строка с разделителями '|'.
        // Здесь же обычно находятся параметры пагинации (например, номера строк от 0 до 50).
        String payload = "7|0|6|https://alm.headoffice.psbank.local/sd/operator/|...|";

        HttpClient client = HttpClient.newBuilder()
                // При необходимости здесь настраивается обход локальных SSL-сертификатов
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(targetUrl))
                .header("Authorization", authHeader)
                // Обязательные заголовки для корректной работы GWT-RPC
                .header("Content-Type", "text/x-gwt-rpc; charset=utf-8")
                .header("X-GWT-Permutation", "СЮДА_ВСТАВИТЬ_ХЭШ_ИЗ_БРАУЗЕРА")
                .header("X-GWT-Module-Base", "https://alm.headoffice.psbank.local/sd/operator/")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            String responseBody = response.body();
            System.out.println("Получен ответ: " + responseBody);
            // Дальнейшая обработка данных
        } else {
            System.out.println("Ошибка: " + response.statusCode());
        }
    }
}