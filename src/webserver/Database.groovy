// ============================================================================
// ФАЙЛ 2: Database.groovy
// ----------------------------------------------------------------------------
// Архитектурный слой: РАБОТА С БАЗОЙ ДАННЫХ (REPOSITORIES)
// Описание: Содержит классы для прямого взаимодействия с SQLite (запросы, 
// вставки, обновления). Вся оригинальная логика сохранена без единого 
// удаления, убрано только ключевое слово 'static'.
// ============================================================================

import groovy.sql.Sql
import java.text.SimpleDateFormat

// ============================================================================
// Менеджер Меток (База Знаний)
// Отвечает за сохранение, поиск и удаление категорий и сниппетов кода.
// ============================================================================
class LabelsManager {
    private Sql sql

    LabelsManager(Sql sql) { this.sql = sql }

    // Получение всех категорий и меток с сортировкой
    def getAll() { def categories = sql.rows("SELECT * FROM labels_categories ORDER BY display_order ASC, name ASC"); def labels = sql.rows("SELECT * FROM labels ORDER BY usage_count DESC, date_created DESC"); return [categories: categories, labels: labels] }

    // Поиск меток по тексту или категории
    def search(String query, Integer categoryId) {
        def labelsFromDb = categoryId != null ? sql.rows("SELECT * FROM labels WHERE category_id = ?", [categoryId]) : sql.rows("SELECT * FROM labels")
        def filteredLabels = labelsFromDb
        if (query && !query.trim().isEmpty()) {
            def normalizedQuery = query.trim().toLowerCase()
            filteredLabels = labelsFromDb.findAll { label -> (label.title?.toLowerCase()?.contains(normalizedQuery)) || (label.content?.toLowerCase()?.contains(normalizedQuery)) }
        }
        return filteredLabels.sort { a, b -> (b.usage_count <=> a.usage_count) ?: (b.date_created <=> a.date_created) }
    }

    // Создание или обновление категории
    def saveCategory(Map data) { if (data.id) sql.execute("UPDATE labels_categories SET name=?, display_order=? WHERE id=?", [data.name, data.display_order ?: 99, data.id]) else sql.execute("INSERT INTO labels_categories (name, display_order) VALUES (?, ?)", [data.name, data.display_order ?: 99]); return [status: 'OK'] }

    // Каскадное удаление категории (и всех меток внутри нее)
    def deleteCategory(int id) { sql.execute("DELETE FROM labels WHERE category_id=?", [id]); sql.execute("DELETE FROM labels_categories WHERE id=?", [id]); return [status: 'OK'] }

    // Создание или обновление метки (сниппета)
    def saveLabel(Map data) { if (data.id) sql.execute("UPDATE labels SET title=?, content=?, category_id=? WHERE id=?", [data.title, data.content, data.category_id, data.id]) else sql.execute("INSERT INTO labels (title, content, category_id) VALUES (?, ?, ?)", [data.title, data.content, data.category_id]); return [status: 'OK'] }

    // Удаление конкретной метки
    def deleteLabel(int id) { sql.execute("DELETE FROM labels WHERE id=?", [id]); return [status: 'OK'] }

    // Увеличение счетчика использования метки (для сортировки по популярности)
    def incrementUsage(int id) { sql.execute("UPDATE labels SET usage_count = usage_count + 1 WHERE id=?", [id]); return [status: 'OK'] }
}

// ============================================================================
// Менеджер Истории Вызовов (API Calls) и Учетных данных (Credentials)
// Отвечает за логирование запросов/ответов, пины и безопасное хранение паролей.
// ============================================================================
class CallHistoryManager {
    private Sql sql

    CallHistoryManager(Sql sql) { this.sql = sql }

    // Сохранение лога HTTP-вызова со всеми заголовками и телом
    def saveCall(Map data) {
        def dbFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        def formattedDate = dbFormatter.format(new Date())
        sql.execute("INSERT INTO call_history (cs_name, bs_name, interface_o, http_method, request, response, service_url, domain_url, request_headers, response_headers, stack_trace, http_status, call_date) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                [data.cs_name, data.bs_name, data.interface_o, data.http_method, data.request, data.response, data.service_url, data.domain_url, data.request_headers, data.response_headers, data.stack_trace, data.http_status, formattedDate])
    }

    // Установка "пина" на важный лог (чтобы он не удалился при очистке)
    def togglePin(int id, String comment, int isPinned) {
        sql.execute("UPDATE call_history SET is_pinned=?, pin_comment=? WHERE id=?", [isPinned, comment, id])
        return [status: 'OK']
    }

    // Получение полной истории вызовов
    def getAll() { return sql.rows("SELECT * FROM call_history ORDER BY id DESC") }

    // Получение истории по конкретной внешней системе
    def getGroup(String csName) { return sql.rows("SELECT * FROM call_history WHERE cs_name = ? ORDER BY id ASC", [csName]) }

    // Продвинутый поиск по истории с фильтрацией (включая группировку и даты)
    def search(Map params) {
        def wc = []
        def qp = []
        boolean doGroup = false

        params.each { k, v ->
            if (v && !v.toString().trim().isEmpty()) {
                def nv = "%${v.toString().trim().toLowerCase()}%"
                switch(k) {
                    case 'csName': wc.add("LOWER(cs_name) LIKE ?"); qp.add(nv); break;
                    case 'bsName': wc.add("LOWER(bs_name) LIKE ?"); qp.add(nv); break;
                    case 'interfaceName': wc.add("LOWER(interface_o) LIKE ?"); qp.add(nv); break;
                    case 'url': wc.add("LOWER(service_url) LIKE ?"); qp.add(nv); break;
                    case 'content':
                        wc.add("""(
                            LOWER(request) LIKE ? OR 
                            LOWER(response) LIKE ? OR 
                            LOWER(stack_trace) LIKE ? OR
                            LOWER(request_headers) LIKE ? OR
                            LOWER(response_headers) LIKE ?
                        )""")
                        5.times { qp.add(nv) }
                        break;
                    case 'dateFrom': wc.add("datetime(call_date) >= datetime(?)"); qp.add("${v} 00:00:00"); break;
                    case 'dateTo':   wc.add("datetime(call_date) <= datetime(?)"); qp.add("${v} 23:59:59"); break;
                    case 'groupByInterface': if (v == 'true' || v == 'on') doGroup = true; break;
                }
            }
        }

        def whereClause = wc ? " WHERE " + wc.join(" AND ") : ""

        if (doGroup) {
            // Выборка только последних двух вызовов для каждого интерфейса
            def qb = new StringBuilder("""
                WITH RankedCalls AS (
                    SELECT *, 
                           ROW_NUMBER() OVER (PARTITION BY bs_name, interface_o ORDER BY call_date DESC) as rn
                    FROM call_history
                    ${whereClause}
                )
                SELECT * FROM RankedCalls WHERE rn <= 2 ORDER BY id DESC
            """)
            return sql.rows(qb.toString(), qp)
        } else {
            def qb = new StringBuilder("SELECT * FROM call_history ${whereClause} ORDER BY id DESC")
            return sql.rows(qb.toString(), qp)
        }
    }

    // Получение списка сохраненных кредов
    def getAllCredentials() { return sql.rows("SELECT id, name FROM credentials ORDER BY name ASC") }

    // Получение конкретного креда (пароль маскируется для фронтенда)
    def getCredentialById(int id) {
        def row = sql.firstRow("SELECT * FROM credentials WHERE id = ?", [id])
        if (!row) return null
        def cred = new HashMap(row)
        if (cred.password) cred.password = "********"
        return cred
    }

    // Получение сырого креда (с зашифрованным паролем для внутренних нужд)
    def getRawCredential(int id) { return sql.firstRow("SELECT * FROM credentials WHERE id = ?", [id]) }

    // Безопасное сохранение креда (с шифрованием AES через CryptoUtil)
    def saveCredential(Map data) {
        if (data.id) {
            if (data.password && data.password != "********") {
                sql.execute("UPDATE credentials SET name=?, login=?, password=? WHERE id=?",
                        [data.name, data.login, CryptoUtil.encrypt(data.password), data.id])
            } else {
                sql.execute("UPDATE credentials SET name=?, login=? WHERE id=?",
                        [data.name, data.login, data.id])
            }
        } else {
            sql.execute("INSERT INTO credentials (name, login, password) VALUES (?,?,?)",
                    [data.name, data.login, CryptoUtil.encrypt(data.password)])
        }
        return [status: 'OK']
    }

    // Удаление сохраненных данных доступа
    def deleteCredential(int id) { sql.execute("DELETE FROM credentials WHERE id=?", [id]); return [status: 'OK']}
}