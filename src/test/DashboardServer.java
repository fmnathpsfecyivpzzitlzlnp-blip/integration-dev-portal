package test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public class DashboardServer {

    private static final String DATA_DIR = "tasks_html";

    public static void main(String[] args) throws Exception {
        File dir = new File(DATA_DIR);
        if (!dir.exists()) dir.mkdir();

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", new DashboardHandler());
        server.setExecutor(null);
        server.start();

        System.out.println("Сервер запущен: http://localhost:8080/");
    }

    static class DashboardHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            StringBuilder theadBuilder = new StringBuilder();
            StringBuilder tbodyBuilder = new StringBuilder();

            File folder = new File(DATA_DIR);
            File[] files = folder.listFiles((d, name) -> name.endsWith(".html"));

            if (files != null) {
                boolean headExtracted = false;
                for (File file : files) {
                    Document doc = Jsoup.parse(file, "UTF-8");
                    Element table = doc.selectFirst("table.cellTableWidget");
                    if (table == null) continue;

                    if (!headExtracted) {
                        Element thead = table.selectFirst("thead");
                        if (thead != null) {
                            theadBuilder.append("<tr>");
                            for (Element th : thead.select("th")) {
                                Element caption = th.selectFirst("div[id=caption]");
                                String colName = caption != null ? caption.text() : th.text();
                                theadBuilder.append("<th>").append(colName.replace("Настройки", "").trim()).append("</th>");
                            }
                            theadBuilder.append("</tr>");
                            headExtracted = true;
                        }
                    }

                    Elements rows = table.select("tbody tr.tableRow");
                    for (Element row : rows) {
                        tbodyBuilder.append("<tr>");
                        for (Element td : row.select("td")) {
                            td.select(".iconHolder, .advlistFastFilter, .advlistSort").remove();
                            Element content = td.selectFirst(".cellInsider");
                            String cellHtml = content != null ? content.html() : td.html();
                            tbodyBuilder.append("<td>").append(cellHtml).append("</td>");
                        }
                        tbodyBuilder.append("</tr>");
                    }
                }
            }

            String response = buildHtml(theadBuilder.toString(), tbodyBuilder.toString());
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }

        private String buildHtml(String thead, String tbody) {
            return "<!DOCTYPE html>\n" +
                    "<html lang=\"ru\">\n" +
                    "<head>\n" +
                    "    <meta charset=\"UTF-8\">\n" +
                    "    <title>Дашборд задач</title>\n" +
                    "    <style>\n" +
                    "        body { font-family: 'Segoe UI', Arial, sans-serif; background: #eff3f8; margin: 0; padding: 20px; color: #323232; }\n" +
                    "        .panel { background: #fff; padding: 15px 20px; border-radius: 4px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); margin-bottom: 20px; }\n" +
                    "        .header-panel { display: flex; justify-content: space-between; align-items: center; }\n" +
                    "        .btn { background: #0063b0; color: #fff; border: none; padding: 6px 12px; border-radius: 3px; cursor: pointer; font-size: 13px; margin-left: 5px; }\n" +
                    "        .btn:hover { background: #06497d; }\n" +
                    "        .btn-outline { background: #fff; color: #0063b0; border: 1px solid #0063b0; }\n" +
                    "        .columns-panel { display: flex; flex-wrap: wrap; gap: 10px; font-size: 12px; margin-top: 10px; }\n" +
                    "        .columns-panel label { display: flex; align-items: center; gap: 4px; cursor: pointer; background: #f9f9f9; padding: 4px 8px; border-radius: 3px; border: 1px solid #ddd; }\n" +
                    "        .summary-panel { display: flex; gap: 20px; flex-wrap: wrap; margin-bottom: 20px; }\n" +
                    "        .summary-box { flex: 1; min-width: 200px; background: #fff; padding: 15px; border-radius: 4px; box-shadow: 0 1px 3px rgba(0,0,0,0.1); border-top: 3px solid #0063b0; }\n" +
                    "        .summary-box h4 { margin: 0 0 10px 0; font-size: 14px; color: #555; }\n" +
                    "        .summary-item { display: flex; justify-content: space-between; font-size: 13px; margin-bottom: 5px; border-bottom: 1px dashed #eee; }\n" +
                    "        table { width: 100%; border-collapse: collapse; background: #fff; box-shadow: 0 1px 3px rgba(0,0,0,0.1); font-size: 12px; }\n" +
                    "        th, td { border: 1px solid #e0e0e0; padding: 8px; vertical-align: top; }\n" +
                    "        th { background: #f4f4f4; position: sticky; top: 0; z-index: 10; min-width: 150px; }\n" +
                    "        .col-header { display: flex; justify-content: space-between; align-items: center; font-weight: bold; color: #085896; margin-bottom: 5px; cursor: pointer; }\n" +
                    "        .col-filter { width: 100%; box-sizing: border-box; padding: 4px; margin-bottom: 5px; border: 1px solid #ccc; font-size: 11px; }\n" +
                    "        .dropdown-btn { width: 100%; padding: 4px; font-size: 11px; cursor: pointer; border: 1px solid #ccc; background: #fff; margin-bottom: 5px; }\n" +
                    "        .dropdown-content { display: none; position: absolute; background: #fff; width: max-content; min-width: 200px; max-width: 500px; max-height: 350px; overflow-y: auto; overflow-x: hidden; box-shadow: 0 8px 16px rgba(0,0,0,0.2); z-index: 9999; border: 1px solid #ccc; border-radius: 4px; padding: 5px; top: 100%; left: 0; }\n" +
                    "        .dropdown-content label { display: flex; align-items: flex-start; gap: 5px; padding: 5px; cursor: pointer; font-size: 11px; white-space: normal; border-bottom: 1px solid #f0f0f0; margin: 0; }\n" +
                    "        .dropdown-content label:hover { background: #f1f1f1; }\n" +
                    "        .hide-btn { font-size: 10px; width: 100%; padding: 3px; cursor: pointer; border: 1px solid #e0a8a8; background: #fff; color: #c82333; margin-top: 5px; }\n" +
                    "        a { color: #0063b0; text-decoration: none; }\n" +
                    "    </style>\n" +
                    "</head>\n" +
                    "<body>\n" +
                    "    <div class=\"panel header-panel\">\n" +
                    "        <h2 style=\"margin:0;\">Сводный дашборд</h2>\n" +
                    "        <div>\n" +
                    "            <button class=\"btn btn-outline\" onclick=\"resetSettings()\">Сбросить настройки</button>\n" +
                    "            <button class=\"btn\" onclick=\"location.reload()\">Загрузить планово (Обновить)</button>\n" +
                    "        </div>\n" +
                    "    </div>\n" +
                    "    <div class=\"panel\">\n" +
                    "        <strong>Отображаемые столбцы:</strong>\n" +
                    "        <div class=\"columns-panel\" id=\"columns-toggle\"></div>\n" +
                    "    </div>\n" +
                    "    <div class=\"summary-panel\" id=\"summary-panel\"></div>\n" +
                    "    <table id=\"dashboard-table\">\n" +
                    "        <thead>" + thead + "</thead>\n" +
                    "        <tbody>" + tbody + "</tbody>\n" +
                    "    </table>\n" +
                    "\n" +
                    "    <script>\n" +
                    "        document.addEventListener(\"DOMContentLoaded\", () => {\n" +
                    "            const table = document.getElementById(\"dashboard-table\");\n" +
                    "            if (!table.querySelector(\"thead tr\")) return;\n" +
                    "            const thead = table.querySelector(\"thead tr\");\n" +
                    "            const tbody = table.querySelector(\"tbody\");\n" +
                    "            const rows = Array.from(tbody.querySelectorAll(\"tr\"));\n" +
                    "            const stateKey = \"dashboardComplexState\";\n" +
                    "            \n" +
                    "            let headers = Array.from(thead.children).map(th => th.textContent.trim());\n" +
                    "            \n" +
                    "            let state = JSON.parse(localStorage.getItem(stateKey));\n" +
                    "            if (!state) {\n" +
                    "                state = { hiddenCols: [], textFilters: {}, checkFilters: {}, sortCol: null, sortAsc: true };\n" +
                    "                let jiraIdx = headers.findIndex(h => h.includes(\"Импорт кастомных полей Jira\"));\n" +
                    "                if(jiraIdx > -1) state.hiddenCols.push(String(jiraIdx));\n" +
                    "            }\n" +
                    "            \n" +
                    "            window.resetSettings = () => { localStorage.removeItem(stateKey); location.reload(); };\n" +
                    "            const saveState = () => localStorage.setItem(stateKey, JSON.stringify(state));\n" +
                    "\n" +
                    "            const colToggleContainer = document.getElementById('columns-toggle');\n" +
                    "            headers.forEach((name, i) => {\n" +
                    "                let isHidden = state.hiddenCols.includes(String(i));\n" +
                    "                let checkedStr = isHidden ? '' : 'checked';\n" +
                    "                colToggleContainer.innerHTML += `<label><input type=\"checkbox\" class=\"global-col-toggle\" data-col=\"${i}\" ${checkedStr}> ${name}</label>`;\n" +
                    "            });\n" +
                    "\n" +
                    "            function getUniqueValues(colIdx) {\n" +
                    "                let vals = new Set();\n" +
                    "                rows.forEach(r => { if(r.children[colIdx]) vals.add(r.children[colIdx].textContent.trim()); });\n" +
                    "                return Array.from(vals).filter(v => v !== '').sort();\n" +
                    "            }\n" +
                    "\n" +
                    "            Array.from(thead.children).forEach((th, i) => {\n" +
                    "                const originalText = headers[i];\n" +
                    "                const uniqueVals = getUniqueValues(i);\n" +
                    "                let checkboxesHtml = uniqueVals.map(val => {\n" +
                    "                    let isChecked = state.checkFilters[i] && state.checkFilters[i].includes(val) ? 'checked' : '';\n" +
                    "                    return `<label><input type=\"checkbox\" class=\"val-check\" data-col=\"${i}\" value=\"${val}\" ${isChecked}> <span>${val}</span></label>`;\n" +
                    "                }).join('');\n" +
                    "\n" +
                    "                th.innerHTML = `\n" +
                    "                    <div class=\"col-header sortable\" data-col=\"${i}\">\n" +
                    "                        <span>${originalText}</span> <span>${state.sortCol == i ? (state.sortAsc ? '▲' : '▼') : ''}</span>\n" +
                    "                    </div>\n" +
                    "                    <input type=\"text\" class=\"col-filter\" data-col=\"${i}\" placeholder=\"Текст...\" value=\"${state.textFilters[i] || ''}\">\n" +
                    "                    <div style=\"position: relative;\">\n" +
                    "                        <button class=\"dropdown-btn\" data-col=\"${i}\">Значения ▼</button>\n" +
                    "                        <div class=\"dropdown-content\">${checkboxesHtml}</div>\n" +
                    "                    </div>\n" +
                    "                    <button class=\"hide-btn\" data-col=\"${i}\">Скрыть столбец</button>\n" +
                    "                `;\n" +
                    "            });\n" +
                    "\n" +
                    "            document.addEventListener('click', e => {\n" +
                    "                const isDropdownBtn = e.target.classList.contains('dropdown-btn');\n" +
                    "                const isInsideDropdown = e.target.closest('.dropdown-content');\n" +
                    "\n" +
                    "                if (!isDropdownBtn && !isInsideDropdown) {\n" +
                    "                    document.querySelectorAll('.dropdown-content').forEach(el => {\n" +
                    "                        el.style.display = 'none';\n" +
                    "                        el.closest('th').style.zIndex = '10';\n" +
                    "                    });\n" +
                    "                } else if (isDropdownBtn) {\n" +
                    "                    document.querySelectorAll('.dropdown-content').forEach(el => {\n" +
                    "                        if (el !== e.target.nextElementSibling) {\n" +
                    "                            el.style.display = 'none';\n" +
                    "                            el.closest('th').style.zIndex = '10';\n" +
                    "                        }\n" +
                    "                    });\n" +
                    "                    const content = e.target.nextElementSibling;\n" +
                    "                    const th = e.target.closest('th');\n" +
                    "                    if (content.style.display === 'block') {\n" +
                    "                        content.style.display = 'none';\n" +
                    "                        th.style.zIndex = '10';\n" +
                    "                    } else {\n" +
                    "                        content.style.display = 'block';\n" +
                    "                        th.style.zIndex = '100';\n" +
                    "                    }\n" +
                    "                }\n" +
                    "            });\n" +
                    "\n" +
                    "            document.addEventListener('change', e => {\n" +
                    "                if (e.target.classList.contains('global-col-toggle')) {\n" +
                    "                    let col = e.target.getAttribute('data-col');\n" +
                    "                    if (e.target.checked) state.hiddenCols = state.hiddenCols.filter(c => c !== col);\n" +
                    "                    else if (!state.hiddenCols.includes(col)) state.hiddenCols.push(col);\n" +
                    "                    saveState(); applyState();\n" +
                    "                }\n" +
                    "                if (e.target.classList.contains('val-check')) {\n" +
                    "                    let col = e.target.getAttribute('data-col');\n" +
                    "                    let val = e.target.value;\n" +
                    "                    if (!state.checkFilters[col]) state.checkFilters[col] = [];\n" +
                    "                    if (e.target.checked) state.checkFilters[col].push(val);\n" +
                    "                    else state.checkFilters[col] = state.checkFilters[col].filter(v => v !== val);\n" +
                    "                    saveState(); applyState();\n" +
                    "                }\n" +
                    "            });\n" +
                    "\n" +
                    "            table.addEventListener('input', e => {\n" +
                    "                if (e.target.classList.contains('col-filter')) {\n" +
                    "                    state.textFilters[e.target.getAttribute('data-col')] = e.target.value.toLowerCase();\n" +
                    "                    saveState(); applyState();\n" +
                    "                }\n" +
                    "            });\n" +
                    "\n" +
                    "            table.addEventListener('click', e => {\n" +
                    "                if (e.target.classList.contains('hide-btn')) {\n" +
                    "                    let col = e.target.getAttribute('data-col');\n" +
                    "                    if (!state.hiddenCols.includes(col)) state.hiddenCols.push(col);\n" +
                    "                    document.querySelector(`.global-col-toggle[data-col=\"${col}\"]`).checked = false;\n" +
                    "                    saveState(); applyState();\n" +
                    "                }\n" +
                    "                let sortable = e.target.closest('.sortable');\n" +
                    "                if (sortable) {\n" +
                    "                    let col = sortable.getAttribute('data-col');\n" +
                    "                    if (state.sortCol == col) state.sortAsc = !state.sortAsc;\n" +
                    "                    else { state.sortCol = col; state.sortAsc = true; }\n" +
                    "                    saveState(); location.reload();\n" +
                    "                }\n" +
                    "            });\n" +
                    "\n" +
                    "            function applyState() {\n" +
                    "                Array.from(thead.children).forEach((th, i) => {\n" +
                    "                    let hide = state.hiddenCols.includes(String(i));\n" +
                    "                    th.style.display = hide ? 'none' : '';\n" +
                    "                    rows.forEach(r => { if(r.children[i]) r.children[i].style.display = hide ? 'none' : ''; });\n" +
                    "                });\n" +
                    "\n" +
                    "                let visibleCount = 0;\n" +
                    "                let statusCounts = {};\n" +
                    "                let statusColIdx = headers.findIndex(h => h.toLowerCase().includes('статус') || h.toLowerCase().includes('состояние'));\n" +
                    "\n" +
                    "                rows.forEach(row => {\n" +
                    "                    let show = true;\n" +
                    "                    for (let i = 0; i < headers.length; i++) {\n" +
                    "                        let cellText = row.children[i] ? row.children[i].textContent.trim() : '';\n" +
                    "                        let tFilter = state.textFilters[i];\n" +
                    "                        if (tFilter && !cellText.toLowerCase().includes(tFilter)) { show = false; break; }\n" +
                    "                        \n" +
                    "                        let cFilter = state.checkFilters[i];\n" +
                    "                        if (cFilter && cFilter.length > 0 && !cFilter.includes(cellText)) { show = false; break; }\n" +
                    "                    }\n" +
                    "                    row.style.display = show ? '' : 'none';\n" +
                    "                    \n" +
                    "                    if (show) {\n" +
                    "                        visibleCount++;\n" +
                    "                        if (statusColIdx > -1 && row.children[statusColIdx]) {\n" +
                    "                            let status = row.children[statusColIdx].textContent.trim();\n" +
                    "                            statusCounts[status] = (statusCounts[status] || 0) + 1;\n" +
                    "                        }\n" +
                    "                    }\n" +
                    "                });\n" +
                    "\n" +
                    "                let summaryHtml = `<div class=\"summary-box\"><h4>Всего задач</h4><div class=\"summary-item\"><span>Отображается:</span> <strong>${visibleCount}</strong></div></div>`;\n" +
                    "                if (Object.keys(statusCounts).length > 0) {\n" +
                    "                    let stats = Object.entries(statusCounts).map(([k,v]) => `<div class=\"summary-item\"><span>${k || 'Пусто'}:</span> <strong>${v}</strong></div>`).join('');\n" +
                    "                    summaryHtml += `<div class=\"summary-box\"><h4>Разбивка по статусам</h4>${stats}</div>`;\n" +
                    "                }\n" +
                    "                document.getElementById('summary-panel').innerHTML = summaryHtml;\n" +
                    "            }\n" +
                    "            applyState();\n" +
                    "        });\n" +
                    "    </script>\n" +
                    "</body>\n" +
                    "</html>";
        }
    }
}