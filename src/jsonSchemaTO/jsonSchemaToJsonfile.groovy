//package jsonSchemaTO
//import net.jimblackler.jsongenerator.Configuration
//// import net.jimblackler.jsongenerator.DefaultConfiguration <-- Эта строка удалена
//import net.jimblackler.jsongenerator.Generator
//import groovy.json.JsonOutput
//import groovy.json.JsonSlurper
//
//// Определение JSON-схемы в виде строки
//def schemaString = '''
//{
//  "type": "object",
//  "properties": {
//    "name": {
//      "type": "string",
//      "minLength": 3
//    },
//    "age": {
//      "type": "integer",
//      "minimum": 18
//    },
//    "email": {
//      "type": "string",
//      "format": "email"
//    },
//    "isActive": {
//      "type": "boolean"
//    },
//    "address": {
//      "type": "object",
//      "properties": {
//        "street": {
//          "type": "string"
//        },
//        "city": {
//          "type": "string"
//        }
//      },
//      "required": ["street", "city"]
//    },
//    "tags": {
//      "type": "array",
//      "items": {
//        "type": "string"
//      },
//      "minItems": 2
//    }
//  },
//  "required": ["name", "age", "email"]
//}
//'''
//
//// Создание объекта конфигурации для генератора (ИЗМЕНЕНО ЗДЕСЬ)
//Configuration config = new Configuration()
//
//// Создание экземпляра генератора
//Generator generator = new Generator(config, new JsonSlurper().parseText(schemaString))
//
//// Генерация JSON-объекта из схемы
//def generatedObject = generator.generate()
//
//// Преобразование сгенерированного объекта в красивую JSON-строку
//def prettyJson = JsonOutput.prettyPrint(JsonOutput.toJson(generatedObject))
//
//// Вывод результата
//println prettyJson