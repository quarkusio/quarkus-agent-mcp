# Quarkus Agent MCP

<p align="center">
  <img src="logo.png" width="250" alt="Quarkus Agent MCP Logo">
</p>

A standalone MCP server that enables AI coding agents to create, manage, and interact with Quarkus applications. It runs as a separate process that survives app crashes, giving agents the ability to create projects, check for updates, read extension skills, control application lifecycle, proxy Dev MCP tools, and search Quarkus documentation.

Part of the [DevStar](https://github.com/quarkusio/quarkus/discussions/53093) working group.

## Prerequisites

- Java 21+
- Docker or Podman (for documentation search)
- One of: [Quarkus CLI](https://quarkus.io/guides/cli-tooling), [Maven](https://maven.apache.org), or [JBang](https://www.jbang.dev/download/) (for creating new projects)

## Installation

### Claude Code plugin

Add the marketplace and install the plugin:

```bash
claude plugin marketplace add quarkusio/quarkus-agent-mcp
claude plugin install quarkus-agent@quarkus-tools
```

This installs the plugin and configures the MCP server automatically. Requires [JBang](https://www.jbang.dev/download/).

### Via JBang (recommended)

[JBang](https://www.jbang.dev/download/) resolves the uber-jar from Maven Central automatically — no build step needed.

#### Claude Code

```bash
claude mcp add quarkus-agent -- jbang quarkus-agent-mcp@quarkusio
```

#### VS Code / GitHub Copilot

Add to `.vscode/mcp.json` in your workspace:

```json
{
  "servers": {
    "quarkus-agent": {
      "type": "stdio",
      "command": "jbang",
      "args": ["quarkus-agent-mcp@quarkusio"]
    }
  }
}
```

#### IBM Bob

Add to `.bob/mcp.json`:

```json
{
  "mcpServers": {
    "quarkus-agent": {
      "command": "jbang",
      "args": ["quarkus-agent-mcp@quarkusio"]
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
      "command": "jbang",
      "args": ["quarkus-agent-mcp@quarkusio"]
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
      "command": "jbang",
      "args": ["quarkus-agent-mcp@quarkusio"]
    }
  }
}
```

### Via direct download

Download the uber-jar from the [latest GitHub Release](https://github.com/quarkusio/quarkus-agent-mcp/releases/latest), then:

```bash
claude mcp add quarkus-agent -- java -jar /path/to/quarkus-agent-mcp-1.0.0-runner.jar
```

### Build from source

```bash
git clone https://github.com/quarkusio/quarkus-agent-mcp.git
cd quarkus-agent-mcp
./mvnw package -DskipTests -Dquarkus.package.jar.type=uber-jar
```

This produces the uber-jar at `target/quarkus-agent-mcp-1.0.0-SNAPSHOT-runner.jar`.

```bash
claude mcp add quarkus-agent -- java -jar /path/to/quarkus-agent-mcp/target/quarkus-agent-mcp-1.0.0-SNAPSHOT-runner.jar
```

#### Verify

After registering, ask your agent something like:

> "Search the Quarkus docs for how to create a REST endpoint"

If the MCP server is working, the agent will use `quarkus/searchDocs` and return documentation results.

## Usage

### Creating a new Quarkus app

Ask your agent to build a Quarkus application using natural language. The agent uses the MCP tools automatically.

**Example conversation:**

> **You:** Create a Quarkus REST API with a greeting endpoint and a PostgreSQL database
>
> **Agent:** _(uses `quarkus/create` to scaffold the project with `rest-jackson,jdbc-postgresql,hibernate-orm-panache` extensions — the app starts automatically in dev mode, and a `CLAUDE.md` is generated with project-specific workflow instructions)_
>
> **Agent:** _(calls `quarkus/skills` to learn the correct patterns for Panache, REST, and other extensions before writing any code)_
>
> **You:** Add a `Greeting` entity and a REST endpoint that stores and retrieves greetings
>
> **Agent:** _(writes the code following patterns from skills, then runs tests via a subagent using `quarkus/callTool`)_

### Development workflow

The MCP server guides the agent through the optimal Quarkus development workflow:

```
NEW PROJECT                           EXISTING PROJECT

1. quarkus/create                     1. quarkus/update (via subagent)
   → scaffolds + auto-starts             → checks version, suggests upgrades
   → generates CLAUDE.md
                                      2. quarkus/start
2. quarkus/skills                        → starts dev mode
   → learn extension patterns
                                      3. quarkus/skills
3. quarkus/searchDocs                    → learn extension patterns
   → look up APIs, config
                                      4. quarkus/searchDocs
4. Write code + tests                    → look up APIs, config

5. Run tests (via subagent)           5. Write code + tests
   → quarkus/callTool
   → devui-testing_runTests           6. Run tests (via subagent)
                                         → quarkus/callTool
6. Iterate                               → devui-testing_runTests
```

**Key points:**

- **Hot reload** is automatic in Quarkus dev mode — triggered on the next access (HTTP request or test run), not on file save.
- **Skills before code** — the agent reads extension-specific skills to learn correct patterns, testing approaches, and common pitfalls before writing any code.
- **Tests via subagents** — test execution is dispatched to a subagent so the main conversation stays responsive.
- **The MCP server survives crashes** — if the app crashes due to a code error, the agent can use `devui-exceptions_getLastException` to get structured exception details (class, message, stack trace, user code location) and fix it. Use `quarkus/logs` for broader context.
- **CLAUDE.md** — every new project gets a `CLAUDE.md` with Quarkus-specific workflow instructions that guide the agent.

### What the agent can do with a running app

Once a Quarkus app is running in dev mode, the agent can discover and use all Dev MCP tools via `quarkus/searchTools` and `quarkus/callTool`. The tool list is **dynamic** — it changes when extensions are added or removed, so the agent should re-discover tools after any extension change. Typical tools include:

| Capability | How to discover | Example |
|-----------|----------------|---------|
| Testing | `quarkus/searchTools` query: `test` | Run all tests, run a specific test class |
| Configuration | `quarkus/searchTools` query: `config` | View and change config properties |
| Extensions | `quarkus/searchTools` query: `extension` | Add or remove extensions at runtime |
| Endpoints | `quarkus/searchTools` query: `endpoint` | List all REST endpoints and their URLs |
| Dev Services | `quarkus/searchTools` query: `dev-services` | View database URLs, container info |
| Log levels | `quarkus/searchTools` query: `log` | Change log levels at runtime |
| Exceptions | `devui-exceptions_getLastException` | Get last compilation/deployment/runtime exception with stack trace and source location |

### Extension skills

The agent can read extension-specific coding skills using `quarkus/skills`. Skills contain patterns, testing guidelines, and common pitfalls for each extension — things like "always use `@Transactional` for write operations with Panache" or "don't create REST clients manually, let CDI inject them."

Skills are loaded using a three-layer chain (most specific wins):

1. **JAR skills** — from the `quarkus-extension-skills` JAR, automatically downloaded from Maven Central (or a configured mirror) for the project's Quarkus version. These are the official defaults.
2. **User-level skills** — from `~/.quarkus/skills/<extension-name>/SKILL.md` (or a directory configured via `agent-mcp.local-skills-dir`). Useful for extension developers testing new or modified skills without rebuilding the aggregated JAR.
3. **Project-level skills** — from `.quarkus/skills/<extension-name>/SKILL.md` in the project directory. Allows teams to customize extension patterns for their specific project conventions.

Each layer can either **enhance** (default) or **override** the previous layer, controlled by the `mode` field in the SKILL.md frontmatter:

- **`mode: enhance`** (default) — appends content to the base skill. The base content is preserved and the customization is added after a separator. This is ideal for adding project conventions or team standards without losing the built-in guidance.
- **`mode: override`** — fully replaces the base skill. Use this when you need complete control over a skill's content.

The agent can also create or update skill customizations using `quarkus/updateSkill`. When the user asks to customize a skill, the agent will ask:
1. **Enhance or override?** — append to the base skill or fully replace it.
2. **Project or global scope?** — save under `.quarkus/skills/` (this project only) or `~/.quarkus/skills/` (all projects).

### Documentation search

The agent can search Quarkus documentation at any time using `quarkus/searchDocs`. This uses semantic search (BGE embeddings + pgvector) over the full Quarkus documentation.

On first use, a Docker/Podman container with the pre-indexed documentation is started automatically. If a project directory is provided, the documentation version matches the project's Quarkus version.

### Update checking

For existing projects, `quarkus/update` checks if the Quarkus version is current and provides a full upgrade report:

- Compares build files against [reference projects](https://github.com/quarkusio/code-with-quarkus-compare)
- Runs `quarkus update --dry-run` (if CLI available) to preview automated migrations
- Links to the structural diff between versions
- Recommends manual actions for changes that automated migration doesn't cover

## MCP Tools Reference

### App Creation

| Tool | Description | Parameters |
|------|-------------|------------|
| `quarkus/create` | Create a new Quarkus app, auto-start in dev mode, generate CLAUDE.md | `outputDir` (required), `groupId`, `artifactId`, `extensions`, `buildTool`, `quarkusVersion` |

### Update Checking

| Tool | Description | Parameters |
|------|-------------|------------|
| `quarkus/update` | Check if project is up-to-date, provide upgrade report | `projectDir` (required) |

### Extension Skills

| Tool | Description | Parameters |
|------|-------------|------------|
| `quarkus/skills` | Get coding patterns, testing guidelines, and pitfalls for project extensions | `projectDir` (required), `query` |
| `quarkus/updateSkill` | Create or update a skill customization (enhance or override) | `projectDir` (required), `skillName` (required), `content` (required), `description`, `mode`, `scope` |

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
|  create ------- quarkus create app       |
|  update ------- version check + report   |
|  skills ------- extension-skills JAR     |
|  start/stop --- child process            |
|  searchTools -- HTTP proxy to Dev MCP    |
|  callTool ----- HTTP proxy to Dev MCP    |
|  searchDocs --- embeddings + pgvector    |
+------+---------+---------+----------+----+
       |         |         |          |
       v         v         v          v
  quarkus dev  /q/dev-mcp  pgvector   Maven Central
  (may crash   (running    (pre-      (extension-
   -- Agent    app's Dev   indexed    skills JAR)
   survives)   MCP tools)  docs)
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
| `agent-mcp.local-skills-dir` | `~/.quarkus/skills` | Directory for user-level skill customizations |

## Building a Native Image

For instant startup (no JVM warmup):

```bash
./mvnw package -Dnative -DskipTests -Dquarkus.package.jar.type=uber-jar
```

Then reference the native binary in your MCP config:

```bash
claude mcp add quarkus-agent -- ./target/quarkus-agent-mcp-*-runner
```

## Presentation

An HTML slide deck is available in the `presentation/` directory for team demos and talks.

```bash
# Open directly in a browser
xdg-open presentation/index.html   # Linux
open presentation/index.html        # macOS
```

Press **F** for fullscreen, arrow keys or space to navigate slides.

## Related Projects

- [Quarkus Dev MCP](https://github.com/quarkusio/quarkus) — Built-in MCP server inside running Quarkus apps
- [quarkus-skills](https://github.com/quarkusio/quarkus-skills) — Standalone skill files for Quarkus (Agent Skills specification)
- [code-with-quarkus-compare](https://github.com/quarkusio/code-with-quarkus-compare) — Reference projects for build file comparison
- [chappie-docling-rag](https://github.com/chappie-bot/chappie-docling-rag) — Builds the pgvector Docker images with pre-indexed Quarkus docs
- [quarkus-mcp-server](https://github.com/quarkiverse/quarkus-mcp-server) — Quarkiverse MCP Server extension used by this project

## Privacy Policy

Quarkus Agent MCP runs entirely on your local machine. It does **not** collect, transmit, or store any personal data, telemetry, or analytics.

The server makes the following outbound network requests, all in service of its documented features:

| Request | Destination | Purpose |
|---------|-------------|---------|
| Skills JAR download | `repo1.maven.org` (or your configured Maven mirror) | Download extension-specific coding patterns for your project's Quarkus version |
| Version check | `github.com/quarkusio/code-with-quarkus-compare` | Determine the latest Quarkus version and fetch reference build files for update comparison |
| Documentation container | `ghcr.io/quarkusio/chappie-ingestion-quarkus` | Pull a Docker image with pre-indexed Quarkus documentation for local semantic search |
| Dev MCP proxy | `localhost` only | Communicate with your running Quarkus application's Dev MCP server |

No data is sent to Quarkus, Red Hat, or any third party. All downloaded artifacts are cached locally in standard locations (`~/.m2/repository`, Docker image cache). Source code and project files never leave your machine.

## License

Apache License 2.0
