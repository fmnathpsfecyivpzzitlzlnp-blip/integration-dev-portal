package jsonTO

import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import org.apache.poi.ss.usermodel.Cell
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.Sheet
import org.apache.poi.ss.usermodel.Workbook
import org.apache.poi.xssf.usermodel.XSSFWorkbook

import java.io.FileOutputStream
import java.io.File

// --- НАЧАЛО ИСПОЛНЕНИЯ СКРИПТА ---

// 1. JSON-контент, который будет сохранен во входной файл.
def jsonContent = [
        Response: [
                ResultStatus: "1",
                IT_REQUEST_LIST: [
                        [
                                IsIndefinitely: "1",
                                ItStartDate: "2024-01-02",
                                ItEndDate: "2024-01-03",
                                ItDecisionDate: "2024-01-02",
                                ItDecisionNum: "string",
                                ItProduct: "string",
                                Status: "string",
                                StatusIdent: "string",
                                TariffZone: "string",
                                TariffPlan: "string",
                                RequestId: "string",
                                ItBranchTp: "string",
                                IT_ITEM_LIST: [
                                        [
                                                ServiceRate: "string",
                                                ServiceRateRange: "string",
                                                ServiceNumber: "string",
                                                ServiceName: "string",
                                                StandartRate: "string",
                                                ItItemStatus: "string",
                                                TariffReductFact: "14.12"
                                        ]
                                ]
                        ]
                ],
                VP_REQUEST_LIST: [
                        [
                                VpType: "string",
                                Status: "string",
                                StatusIdent: "string",
                                VpStage: "string",
                                VpCreateDate: "2024-01-02",
                                VpName: "string",
                                VpDocForm: "string",
                                VpCloseDate: "2024-01-02",
                                VpStartDate: "2024-01-02",
                                VpEndDate: "2024-01-02",
                                VpNumParticipant: "4",
                                RequestId: "string"
                        ]
                ],
                DEP_REQUEST_LIST: [
                        [
                                DepStage: "string",
                                Status: "string",
                                StatusIdent: "string",
                                DepForm: "string",
                                DepFormIdent: "string",
                                DepSum: "14.12",
                                DepType: "string",
                                DepCreateDate: "2024-01-02",
                                DepEndDate: "2024-01-02",
                                DepPlacementDate: "2024-01-02",
                                DepContractName: "string",
                                DepAccountNum: "string",
                                IsDepSigningDbo: "1",
                                IsDepUrgently: "1",
                                DepCurrencyIdent: "string",
                                DepGenAgreementNum: "string",
                                DepGenAgreementDate: "2024-01-02",
                                RequestId: "string"
                        ]
                ]
        ],
        Request: [
                ClientInfo: [
                        INN: "1234567890",
                        KPP: "123456789",
                        OGRNIP: ""
                ]
        ]
]

// Имя входного файла
def inputFilename = "input_data.json"

// Сохраняем контент в файл
new File(inputFilename).write(new JsonOutput().prettyPrint(new JsonOutput().toJson(jsonContent)), 'UTF-8')


// 2. Укажите имя для выходного Excel-файла.
def outputFilename = "json_structure_output.xlsx"

// 3. Запустите основную функцию.
createExcelFromJson(inputFilename, outputFilename)


/**
 * Определяет тип значения и возвращает его в виде строки.
 */
String getValueType(value) {
    if (value instanceof Map) return "object"
    if (value instanceof List) return "array"
    if (value instanceof String) {
        if (value.isNumber()) {
            return value.contains('.') ? "decimal" : "integer"
        }
        return "string"
    }
    if (value instanceof Integer || value instanceof Long) return "integer"
    if (value instanceof Float || value instanceof Double || value instanceof BigDecimal) return "decimal"
    if (value instanceof Boolean) return "boolean"
    if (value == null) return "null"
    return "unknown"
}

/**
 * Рекурсивно обходит JSON-объект для сбора информации о его структуре.
 */
void parseJsonStructure(String key, value, String level, List<Map> results) {
    String valueType = getValueType(value)
    String repetition = (valueType == 'array') ? "0..n" : "1..1"

    results.add([
            "Уровень": level,
            "Наименование": key,
            "Категория": "Element",
            "Тип": valueType,
            "Повторение": repetition,
            "Описание": ""
    ])

    if (valueType == "object") {
        int childCounter = 1
        value.each { childKey, childValue ->
            String childLevel = "${level}.${childCounter}"
            parseJsonStructure(childKey, childValue, childLevel, results)
            childCounter++
        }
    } else if (valueType == "array" && value) {
        def firstItem = value[0]
        if (firstItem instanceof Map) {
            int childCounter = 1
            firstItem.each { childKey, childValue ->
                String childLevel = "${level}.${childCounter}"
                parseJsonStructure(childKey, childValue, childLevel, results)
                childCounter++
            }
        }
    }
}

/**
 * Основная функция для чтения JSON и создания Excel-файла.
 */
void createExcelFromJson(String jsonFilePath, String excelFilePath) {
    try {
        def inputFile = new File(jsonFilePath)
        if (!inputFile.exists()) {
            println "Ошибка: Файл '${jsonFilePath}' не найден."
            return
        }
        def data = new JsonSlurper().parse(inputFile, 'UTF-8')

        List<Map> allRows = []
        int rootCounter = 1

        data.each { rootKey, rootValue ->
            parseJsonStructure(rootKey, rootValue, rootCounter.toString(), allRows)
            rootCounter++
        }

        Workbook workbook = new XSSFWorkbook()
        Sheet sheet = workbook.createSheet("JSON Structure")

        // Создаем заголовок
        Row headerRow = sheet.createRow(0)
        def headers = ["Уровень", "Наименование", "Категория", "Тип", "Повторение", "Описание"]
        headers.eachWithIndex { header, index ->
            Cell cell = headerRow.createCell(index)
            cell.setCellValue(header)
        }

        // Заполняем данными
        allRows.eachWithIndex { rowData, rowIndex ->
            Row row = sheet.createRow(rowIndex + 1)
            rowData.values().eachWithIndex { value, cellIndex ->
                row.createCell(cellIndex).setCellValue(value.toString())
            }
        }

        // Автоматическое выравнивание ширины колонок
        headers.size().times { sheet.autoSizeColumn(it) }

        try (FileOutputStream fileOut = new FileOutputStream(excelFilePath)) {
            workbook.write(fileOut)
            println "Структура успешно проанализирована и сохранена в файл: '${excelFilePath}'"
        } catch (IOException e) {
            println "\nОШИБКА СОХРАНЕНИЯ:"
            println "Не удалось сохранить файл '${excelFilePath}'."
            println "Возможная причина: файл уже открыт в Excel или нет прав на запись. Пожалуйста, закройте его и попробуйте снова."
        }

    } catch (Exception e) {
        println "Произошла непредвиденная ошибка: ${e.message}"
    }
}