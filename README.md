# Quarkus Agent MCP

A standalone MCP server that enables AI coding agents to create, manage, and interact with Quarkus applications. It runs as a separate process that survives app crashes, giving agents the ability to create projects, control application lifecycle (start/stop/restart), proxy Dev MCP tools, and search Quarkus documentation.

Part of the [DevStar](https://github.com/quarkusio/quarkus/discussions/53093) working group.

## Prerequisites

- Java 21+
- Docker or Podman (for documentation search)
- One of: [Quarkus CLI](https://quarkus.io/guides/cli-tooling), [Maven](https://maven.apache.org), or [JBang](https://www.jbang.dev/download/) (for creating new projects)

## Installation

### Build from source

```bash
git clone https://github.com/quarkusio/quarkus-agent-mcp.git
cd quarkus-agent-mcp
./mvnw package -DskipTests
```

This produces the runner jar at `target/quarkus-app/quarkus-run.jar`.

### Configure your coding agent

Once built, register the MCP server with your coding agent. Choose the section for your agent below.

#### Claude Code

```bash
claude mcp add quarkus-agent -- java -jar /path/to/quarkus-agent-mcp/target/quarkus-app/quarkus-run.jar
```

#### VS Code / GitHub Copilot

Add to `.vscode/mcp.json` in your workspace:

```json
{
  "servers": {
    "quarkus-agent": {
      "type": "stdio",
      "command": "java",
      "args": ["-jar", "/path/to/quarkus-agent-mcp/target/quarkus-app/quarkus-run.jar"]
    }
  }
}
```

#### Cursor

Add to `.cursor/mcp.json`:

```json
{
  "mcpServers": {
    "quarkus-agent": {
      "command": "java",
      "args": ["-jar", "/path/to/quarkus-agent-mcp/target/quarkus-app/quarkus-run.jar"]
    }
  }
}
```

#### Windsurf

Add to `~/.codeium/windsurf/mcp_config.json`:

```json
{
  "mcpServers": {
    "quarkus-agent": {
      "command": "java",
      "args": ["-jar", "/path/to/quarkus-agent-mcp/target/quarkus-app/quarkus-run.jar"]
    }
  }
}
```

#### Verify

After registering, ask your agent something like:

> "Search the Quarkus docs for how to create a REST endpoint"

If the MCP server is working, the agent will use `quarkus/searchDocs` and return documentation results.

## Usage

### Creating and developing a Quarkus app

Once the MCP server is registered with your coding agent, you can ask the agent to build Quarkus applications using natural language. The agent uses the MCP tools automatically.

**Example conversation:**

> **You:** Create a Quarkus REST API with a greeting endpoint and a PostgreSQL database
>
> **Agent:** _(uses `quarkus/searchDocs` to look up REST and datasource configuration, then `quarkus/create` to scaffold the project with `rest-jackson,jdbc-postgresql,hibernate-orm-panache` extensions — the app starts automatically in dev mode)_
>
> **You:** Add a `Greeting` entity and a REST endpoint that stores and retrieves greetings
>
> **Agent:** _(uses `quarkus/searchDocs` to look up Panache entity patterns, writes the code, then uses `quarkus/searchTools` to find continuous testing tools, resumes testing, checks `quarkus/logs` for test results)_

### Development workflow

The MCP server guides the agent through the optimal Quarkus development workflow:

```
1. CREATE           quarkus/create → project scaffolded + auto-started in dev mode
                    │
2. SEARCH DOCS      quarkus/searchDocs → look up APIs, config, best practices
                    │
3. DISCOVER TOOLS   quarkus/searchTools → find testing, config, endpoint tools
                    │
4. DEVELOP          ┌─────────────────────────────────────────────┐
   (repeat)         │  a) Pause continuous testing                │
                    │  b) Write code + tests                      │
                    │  c) Save all files                          │
                    │  d) Resume continuous testing                │
                    │     → triggers hot reload + runs tests      │
                    │  e) Check quarkus/logs for test results     │
                    │  f) Fix any failures, repeat                │
                    └─────────────────────────────────────────────┘
                    │
5. MONITOR          quarkus/status, quarkus/logs → check app health
```

**Key points:**

- **Hot reload** is automatic in Quarkus dev mode, but is triggered on the next access (HTTP request or test run), not on file save. Resuming continuous testing triggers hot reload.
- **Pause before editing** — the agent pauses continuous testing before making changes so that tests don't fail on partially-applied code.
- **Doc search first** — the agent searches Quarkus documentation before writing code to ensure it uses the correct APIs and patterns.
- **The MCP server survives crashes** — if the app crashes due to a code error, the agent can check `quarkus/logs` to see what went wrong and fix it. The app is still managed and can be restarted.

### What the agent can do with a running app

Once a Quarkus app is running in dev mode, the agent can discover and use all Dev MCP tools via `quarkus/searchTools` and `quarkus/callTool`. These typically include:

| Capability | How to discover | Example |
|-----------|----------------|---------|
| Continuous testing | `quarkus/searchTools` query: `test` | Start, stop, pause, resume tests |
| Configuration | `quarkus/searchTools` query: `config` | View and change config properties |
| Extensions | `quarkus/searchTools` query: `extension` | Add or remove extensions at runtime |
| Endpoints | `quarkus/searchTools` query: `endpoint` | List all REST endpoints and their URLs |
| Dev Services | `quarkus/searchTools` query: `dev-services` | View database URLs, container info |
| Log levels | `quarkus/searchTools` query: `log` | Change log levels at runtime |

### Documentation search

The agent can search Quarkus documentation at any time using `quarkus/searchDocs`. This uses semantic search (BGE embeddings + pgvector) over the full Quarkus documentation.

On first use, a Docker/Podman container with the pre-indexed documentation is started automatically. If a project directory is provided, the documentation version matches the project's Quarkus version.

**Examples of what to ask:**

- "Search the docs for how to configure a datasource"
- "Look up CDI dependency injection"
- "Find the docs on native image configuration"
- "How do I write tests in Quarkus?"

## MCP Tools Reference

### App Creation

| Tool | Description | Parameters |
|------|-------------|------------|
| `quarkus/create` | Create a new Quarkus app and auto-start it in dev mode | `outputDir` (required), `groupId`, `artifactId`, `extensions`, `buildTool` |

### Lifecycle Management

| Tool | Description | Parameters |
|------|-------------|------------|
| `quarkus/start` | Start a Quarkus app in dev mode | `projectDir` (required), `buildTool` |
| `quarkus/stop` | Graceful shutdown | `projectDir` (required) |
| `quarkus/restart` | Force restart (usually not needed — hot reload is automatic) | `projectDir` (required) |
| `quarkus/status` | Get app state | `projectDir` (required) |
| `quarkus/logs` | Get recent log output | `projectDir` (required), `lines` |
| `quarkus/list` | List all managed instances | _(none)_ |

### Dev MCP Proxy

| Tool | Description | Parameters |
|------|-------------|------------|
| `quarkus/searchTools` | Discover tools on the running app's Dev MCP server | `projectDir` (required), `query` |
| `quarkus/callTool` | Invoke a Dev MCP tool by name | `projectDir` (required), `toolName` (required), `toolArguments` |

### Documentation Search

| Tool | Description | Parameters |
|------|-------------|------------|
| `quarkus/searchDocs` | Semantic search over Quarkus documentation | `query` (required), `maxResults`, `projectDir` |

## Architecture

```
AI Coding Agent (Claude Code, Copilot, Cursor...)
        |  MCP (stdio)
        v
+------------------------------------------+
|  Quarkus Agent MCP (always running)      |
|                                          |
|  create --- quarkus create app / mvn     |
|  start/stop/restart --- child process    |
|  searchTools/callTool -- HTTP proxy      |
|  searchDocs --- embeddings + pgvector    |
+------+--------------+--------------+-----+
       |              |              |
       v              v              v
  quarkus dev    /q/dev-mcp     pgvector
  (may crash     (running       (Testcontainers,
   -- Agent MCP   app's Dev      pre-indexed
   survives)      MCP tools)     Quarkus docs)
```

The MCP server wraps `quarkus dev` as a child process, so it stays alive when the app crashes. This is the key differentiator from the built-in Dev MCP server.

## Configuration

Configuration via `application.properties`, system properties (`-D`), or environment variables:

| Property | Default | Description |
|----------|---------|-------------|
| `agent-mcp.doc-search.image-prefix` | `ghcr.io/quarkusio/chappie-ingestion-quarkus` | Docker image prefix for pre-indexed docs |
| `agent-mcp.doc-search.image-tag` | `latest` | Default image tag (overridden by detected Quarkus version) |
| `agent-mcp.doc-search.pg-user` | `quarkus` | PostgreSQL user |
| `agent-mcp.doc-search.pg-password` | `quarkus` | PostgreSQL password |
| `agent-mcp.doc-search.pg-database` | `quarkus` | PostgreSQL database |
| `agent-mcp.doc-search.min-score` | `0.82` | Minimum similarity score for search results |

## Building a Native Image

For instant startup (no JVM warmup):

```bash
./mvnw package -Dnative -DskipTests
```

Then reference the native binary in your MCP config:

```bash
claude mcp add quarkus-agent -- ./target/quarkus-agent-mcp-1.0.0-SNAPSHOT-runner
```

## Related Projects

- [Quarkus Dev MCP](https://github.com/quarkusio/quarkus) — Built-in MCP server inside running Quarkus apps
- [chappie-docling-rag](https://github.com/chappie-bot/chappie-docling-rag) — Builds the pgvector Docker images with pre-indexed Quarkus docs
- [quarkus-mcp-server](https://github.com/quarkiverse/quarkus-mcp-server) — Quarkiverse MCP Server extension used by this project

## License

Apache License 2.0
