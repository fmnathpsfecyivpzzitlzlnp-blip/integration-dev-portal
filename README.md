Markdown

# Integration Dev Portal

A self-hosted, lightweight developer portal designed specifically for system integration engineers (ESB, ETL, Microservices). It provides a unified workspace for managing integration tasks, testing APIs, converting schemas, and generating documentation.

## Core Features
* **Integration Task Tracker:** Kanban-style board and registry tailored for API/ESB pipelines with Role-Based Access Control (RBAC).
* **Constructors & Converters:** Visual builders for WSDL/XSD, XML ↔ JSON conversion, and JSON Schema generation.
* **API Call History:** Execute API requests directly from the portal and save full request/response traces.
* **Knowledge Base:** Centralized storage for SQL/Groovy snippets and configuration templates with syntax highlighting.
* **Offline-First:** Runs entirely locally or on a private server with zero external database dependencies.

## Tech Stack
* **Backend:** Java 17, Groovy 5.x (Custom HTTP Server)
* **Database:** SQLite
* **Frontend:** Vanilla JS, jQuery, CodeMirror, jsTree, Chart.js

## Quick Start

### 1. Prerequisites
* JDK 17+ installed.
* Groovy 5.x binaries.
* Required `.jar` dependencies (SQLite JDBC, POI, SLF4J, etc.) placed in the `libs/` and `lib/` directories.

### 2. Installation
Clone the repository:
```bash
git clone [https://github.com/your-username/integration-dev-portal.git](https://github.com/your-username/integration-dev-portal.git)
cd integration-dev-portal

3. Running the Server

Compile and run the Server.groovy script, ensuring your classpath includes the required Groovy libraries and the SQLite JDBC driver.

Example launch command:
Bash

java -Dfile.encoding=UTF-8 -cp "out/production/GroovyProject;libs/*;lib/*" Server

The server will start on http://localhost:47183.
4. First Login & Database

Upon the first launch, the system automatically generates an SQLite database file (portal.db) inside the db/ directory.

Default Administrator Credentials:

    Login: root

    Password: root

(Please change this password immediately in the "System -> Users & Roles" section).