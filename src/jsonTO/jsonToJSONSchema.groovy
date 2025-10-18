package jsonTO

import groovy.json.*

// Функция для определения типа данных и создания соответствующей части схемы
def determineSchema(value) {
    if (value == null) {
        return [type: ["string", "number", "boolean", "array", "object", "null"]]
    } else if (value instanceof String) {
        return [type: "string"]
    } else if (value instanceof Number) {
        return [type: "number"]
    } else if (value instanceof Boolean) {
        return [type: "boolean"]
    } else if (value instanceof List) { // Это JSON-массив
        def itemsSchema = [:]
        if (!value.isEmpty()) {
            itemsSchema = determineSchema(value[0]) // Схема для первого элемента
        } else {
            // Пустой массив, разрешаем любые элементы по умолчанию
            itemsSchema = [type: ["string", "number", "boolean", "array", "object", "null"]]
        }
        return [type: "array", items: itemsSchema]
    } else if (value instanceof Map) { // Это JSON-объект
        def propertiesMap = [:]
        def requiredList = []
        value.each { key, val ->
            propertiesMap[key] = determineSchema(val)
            requiredList << key
        }
        return [
                type: "object",
                properties: propertiesMap,
                required: requiredList,
                additionalProperties: false // Добавлено для строгости: запрет дополнительных свойств
        ]
    } else {
        // Неизвестный тип
        return [type: ["string", "number", "boolean", "array", "object", "null"]]
    }
}

// --- Основная часть скрипта ---

// 1. Укажите путь к вашему JSON-файлу
def jsonFilePath = 'data.json' // Измените на путь к вашему файлу
def jsonSchemaFilePath = 'schema.json' // Куда сохранить JSON-схему

// 2. Создаем пример JSON-файла, если его нет (для тестирования)
def testJsonContent = """
{
  "Request": {
    "ClientInfo": {
      "INN": "1234567890",
      "KPP": "123456789",
      "OGRNIP": null // Установил null для проверки
    }
  }
}
"""

def jsonFile = new File(jsonFilePath)
if (!jsonFile.exists() || jsonFile.text.trim().isEmpty()) { // Проверяем также на пустой файл
    jsonFile.write testJsonContent
    println "Создан тестовый JSON-файл: ${jsonFile.canonicalPath}"
} else {
    println "Используется существующий JSON-файл: ${jsonFile.canonicalPath}"
}

try {
    // 3. Читаем и парсим JSON-файл
    def jsonText = jsonFile.text
    if (jsonText.trim().isEmpty()) {
        throw new IOException("JSON-файл пуст или содержит только пробелы.")
    }
    def jsonSlurper = new JsonSlurper()
    def parsedJson = jsonSlurper.parseText(jsonText)

    // 4. Генерируем JSON-схему
    def rootSchemaContent = determineSchema(parsedJson)

    def jsonSchema = [
            "\$schema": "http://json-schema.org/draft-07/schema#",
            "title": "Generated Schema for ${jsonFile.name}",
            "description": "Automatically generated schema from ${jsonFile.name}"
    ]
    jsonSchema.putAll(rootSchemaContent) // Объединяем метаданные с содержимым корневой схемы


    // 5. Сохраняем JSON-схему в файл
    def jsonSchemaOutput = new JsonBuilder(jsonSchema).toPrettyString()
    new File(jsonSchemaFilePath).write jsonSchemaOutput

    println "\nJSON-схема успешно сгенерирована и сохранена в: ${new File(jsonSchemaFilePath).canonicalPath}"
    println "Содержимое схемы:\n"
    println jsonSchemaOutput

} catch (e) {
    println "Ошибка при обработке JSON-файла: ${e.message}"
    e.printStackTrace()
}