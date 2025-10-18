import io.apptik.json.generator.JsonGenerator
import io.apptik.json.schema.SchemaV4
import groovy.json.JsonOutput
import org.json.JSONObject // Важно добавить этот импорт

// Определение JSON-схемы
def schemaString = '''
{
  "title": "Example Schema",
  "type": "object",
  "properties": {
    "firstName": {
      "type": "string"
    },
    "lastName": {
      "type": "string"
    },
    "age": {
      "description": "Age in years",
      "type": "integer",
      "minimum": 0
    }
  },
  "required": ["firstName", "lastName"]
}
'''

// Создание объекта схемы
// Для этой строки нужна библиотека org.json:json, но она обычно идет
// как зависимость к json-schema-validator, так что у вас она уже должна быть
def schema = new SchemaV4().wrap(new JSONObject(schemaString))

// Генерация JSON-объекта из схемы
// Обратите внимание: передаем null вторым параметром и вызываем .generate()
def generatedJson = new JsonGenerator(schema, null).generate()

// Вывод сгенерированного JSON в отформатированном виде
println(JsonOutput.prettyPrint(generatedJson.toString()))