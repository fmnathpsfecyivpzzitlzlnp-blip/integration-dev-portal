import sqlite3
import json
import random
import uuid
from datetime import datetime, timedelta

DB_NAME = "D:\\project\\groovy\\GroovyProject\\DevToolseditor.db"

SYSTEMS = {
    "Россия": [("BS_EFO", "Единое Окно"), ("BS_PSB_ONLINE", "Онлайн"), ("BS_ATHENA_M", "Афина Москва"),
               ("FACTOR", "Фактор ESB"), ("BS_NAUMEN", "Наумен CRM"), ("BS_DBOCORP", "ДБО Корп"),
               ("BS_CFT_BANK", "ЦФТ Банк"), ("BS_DIASOFT", "Диасофт FA"), ("BS_RS_BANK", "RS-Bank"),
               ("BS_BIS", "БИСквит")],
    "США": [("US_CHASE_CORE", "Chase Core"), ("US_CITI_PAY", "Citi Gateway"), ("US_BOFA_GW", "BofA API"),
            ("US_WELLS_API", "Wells Fargo"), ("US_FEDWIRE", "Fedwire Funds"), ("US_CHIPS", "CHIPS"),
            ("US_ZELLE", "Zelle"), ("US_ACH_CLR", "ACH Clearing"), ("US_STRIPE", "Stripe"), ("US_PLAID", "Plaid")],
    "Испания": [("ES_SANTANDER", "Santander Global"), ("ES_BBVA_GLB", "BBVA API Market"), ("ES_CAIXA", "CaixaBank"),
                ("ES_SABADELL", "Banco Sabadell"), ("ES_BANKIA", "Bankia"), ("ES_IBERPAY", "Iberpay"),
                ("ES_BIZUM", "Bizum"), ("ES_REDSYS", "Redsys"), ("ES_CECA", "Cecabank"), ("ES_KUTXA", "Kutxabank")],
    "Китай": [("CN_ICBC_CORE", "ICBC Core"), ("CN_ALIPAY", "Alipay Open"), ("CN_WECHAT", "WeChat Pay"),
              ("CN_CCB_GLB", "CCB Global"), ("CN_ABC_SYS", "AgriBank"), ("CN_BOC_NET", "BOC Network"),
              ("CN_UNIONPAY", "UnionPay"), ("CN_CIPS", "CIPS"), ("CN_PBOC", "PBOC"), ("CN_PINGAN", "PingAn Cloud")]
}


def random_date(days_back):
    return datetime.now() - timedelta(days=random.randint(0, days_back), hours=random.randint(0, 23),
                                      minutes=random.randint(0, 59))


def generate_mock_body(sys_name, iteration):
    req_id = str(uuid.uuid4())
    curr_time = datetime.now().strftime("%Y-%m-%dT%H:%M:%SZ")
    rnd_sum = random.randint(100, 9999)
    user = random.choice(["Ivan", "Maria", "Oleg", "Anna", "Sergey", "Elena"])
    return json.dumps({
        "messageId": req_id,
        "system": sys_name,
        "timestamp": curr_time,
        "amount": rnd_sum,
        "client": user,
        "iteration": iteration
    }, indent=2)


def create_and_seed():
    conn = sqlite3.connect(DB_NAME)
    cursor = conn.cursor()

    # СТРОГАЯ ПРОВЕРКА И СОЗДАНИЕ ТАБЛИЦ (Если база пустая)
    cursor.execute('''CREATE TABLE IF NOT EXISTS systems
                      (
                          id
                          INTEGER
                          PRIMARY
                          KEY
                          AUTOINCREMENT,
                          sid
                          TEXT
                          NOT
                          NULL
                          UNIQUE,
                          description
                          TEXT,
                          created_at
                          TIMESTAMP
                          DEFAULT
                          CURRENT_TIMESTAMP
                      )''')
    cursor.execute('''CREATE TABLE IF NOT EXISTS task_settings
                      (
                          key
                          TEXT
                          PRIMARY
                          KEY,
                          value
                          TEXT
                      )''')
    cursor.execute('''CREATE TABLE IF NOT EXISTS call_history
                      (
                          id
                          INTEGER
                          PRIMARY
                          KEY
                          AUTOINCREMENT,
                          cs_name
                          TEXT,
                          bs_name
                          TEXT,
                          interface_o
                          TEXT,
                          request
                          TEXT,
                          response
                          TEXT,
                          service_url
                          TEXT,
                          domain_url
                          TEXT,
                          request_headers
                          TEXT,
                          response_headers
                          TEXT,
                          stack_trace
                          TEXT,
                          http_method
                          TEXT
                          DEFAULT
                          'POST',
                          http_status
                          INTEGER,
                          is_pinned
                          INTEGER
                          DEFAULT
                          0,
                          pin_comment
                          TEXT,
                          call_date
                          DATETIME
                      )''')
    cursor.execute('''CREATE TABLE IF NOT EXISTS api_tabs
                      (
                          id
                          INTEGER
                          PRIMARY
                          KEY
                          AUTOINCREMENT,
                          env
                          TEXT,
                          name
                          TEXT,
                          method
                          TEXT,
                          url
                          TEXT,
                          headers
                          TEXT,
                          body
                          TEXT,
                          data_rules
                          TEXT,
                          created_at
                          TIMESTAMP
                          DEFAULT
                          CURRENT_TIMESTAMP
                      )''')

    # Системы
    cursor.execute("DELETE FROM systems")
    all_systems = []
    for country, sys_list in SYSTEMS.items():
        for sid, desc in sys_list:
            cursor.execute("INSERT OR IGNORE INTO systems (sid, description) VALUES (?, ?)",
                           (sid, f"[{country}] {desc}"))
            all_systems.append(sid)

    # Настройки: Языки и Переменные окружений
    env_vars = {
        "DEV": {"base_url": "https://fesb-vip-dev:9443", "env_marker": "dev", "token": "Bearer dev_token_123"},
        "TEST": {"base_url": "https://fesb-vip-test:9443", "env_marker": "test", "token": "Bearer test_token_456"},
        "PROD": {"base_url": "https://fesb-vip-prod:9443", "env_marker": "prod", "token": "Bearer prod_token_789"}
    }
    cursor.execute("INSERT OR REPLACE INTO task_settings (key, value) VALUES (?, ?)",
                   ("api_environments_config", json.dumps(env_vars, ensure_ascii=False)))

    locales = {"supported_languages": [{"code": "ru", "name": "Русский"}, {"code": "en", "name": "English"},
                                       {"code": "zh", "name": "中文 (Китайский)"},
                                       {"code": "de", "name": "Deutsch (Немецкий)"},
                                       {"code": "ja", "name": "日本語 (Японский)"}], "default": "ru"}
    cursor.execute("INSERT OR REPLACE INTO task_settings (key, value) VALUES (?, ?)",
                   ("ui_localization_config", json.dumps(locales, ensure_ascii=False)))

    # История вызовов (с реальными правилами генерации внутри тел запросов)
    cursor.execute("DELETE FROM call_history")
    history_data = []
    for i in range(1, 151):  # Генерируем 150 логов
        sys_name = random.choice(all_systems)
        method = random.choice(["POST", "POST", "GET"])
        req_body = generate_mock_body(sys_name, i) if method == "POST" else ""
        res_status = random.choice([200, 200, 200, 201, 400, 401, 500])
        res_body = json.dumps({"status": "SUCCESS" if res_status < 400 else "ERROR", "code": res_status}, indent=2)
        headers = json.dumps({"Content-Type": "application/json", "Authorization": "Bearer token..."}, indent=2)
        c_date = random_date(30).strftime("%Y-%m-%d %H:%M:%S")
        history_data.append((sys_name, "ESB_CORE", f"if_{sys_name.lower()}", method, req_body, res_body,
                             f"https://api.test/{sys_name.lower()}", "api.test", headers, headers, "", res_status,
                             c_date))

    cursor.executemany(
        "INSERT INTO call_history (cs_name, bs_name, interface_o, http_method, request, response, service_url, domain_url, request_headers, response_headers, stack_trace, http_status, call_date) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        history_data)

    # Вкладки API (с правилами генерации!)
    cursor.execute("DELETE FROM api_tabs")

    rules_json = json.dumps({
        "msg_id": {"type": "uuid"},
        "current_time": {"type": "current_timestamp", "format": "yyyy-MM-dd'T'HH:mm:ss'Z'"},
        "rand_sum": {"type": "random_number", "min": 100, "max": 9999},
        "user_name": {"type": "from_list", "values": ["Ivan", "Maria", "Oleg", "Sergey"]},
        "iter": {"type": "increment", "start": 1, "step": 1}
    }, indent=2)

    body_tpl = '{\n  "messageId": "##msg_id##",\n  "timestamp": "##current_time##",\n  "amount": "##rand_sum##",\n  "client": "##user_name##",\n  "iteration": "##iter##"\n}'

    tabs_data = []
    for i in range(1, 11): tabs_data.append(
        ("DEV", f"Сложный Вызов DEV {i}", "POST", "https://fesb-vip-dev:9443/api/test",
         "{\n  \"Content-Type\": \"application/json\"\n}", body_tpl, rules_json))
    for i in range(1, 11): tabs_data.append(
        ("TEST", f"Сложный Вызов TEST {i}", "POST", "https://fesb-vip-test:9443/api/test",
         "{\n  \"Content-Type\": \"application/json\"\n}", body_tpl, rules_json))
    for i in range(1, 4): tabs_data.append(
        ("PROD", f"Вызов PROD {i}", "GET", "https://fesb-vip-prod:9443/api/health",
         "{\n  \"Content-Type\": \"application/json\"\n}", "", "{}"))

    cursor.executemany(
        "INSERT INTO api_tabs (env, name, method, url, headers, body, data_rules) VALUES (?, ?, ?, ?, ?, ?, ?)",
        tabs_data)

    conn.commit()
    conn.close()
    print(
        "Демо-база сгенерирована! Добавлены системы (40), настройки окружений, 150 логов со сложными телами и вкладки с правилами.")


if __name__ == "__main__":
    create_and_seed()