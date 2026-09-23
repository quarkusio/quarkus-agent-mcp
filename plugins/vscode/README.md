# Quarkus Agent MCP for VS Code

Registers the [Quarkus Agent MCP server](https://github.com/quarkusio/quarkus-agent-mcp) with VS Code, so agent mode (GitHub Copilot and other chat agents that use VS Code's MCP support) can create, run and develop Quarkus applications. No `mcp.json` editing needed.

Once installed, **Quarkus Agent** appears under *MCP Servers* in the Extensions view and its tools are offered in agent mode.

## How the server is launched

With the default `quarkusAgent.launchMode` of `auto`, the extension picks the first option that works:

1. **Native binary** (Linux x86_64, macOS). Downloaded from the GitHub release. Starts instantly and needs no JVM.
2. **Uber-jar** with a Java 21+ JDK. The JDK is taken from `quarkusAgent.javaHome`, the Java extension's `java.jdt.ls.java.home`, `JAVA_HOME` or the `PATH`, in that order.
3. **JBang**, via the `quarkus-agent-mcp@quarkusio` alias. JBang downloads a JDK itself if needed.

Downloads are kept in the extension's global storage and reused, so the server also starts offline. The latest release is looked up at most once a day; run **Quarkus Agent: Check for Updates** to look now.

Documentation search still needs Docker or Podman, and creating projects needs the Quarkus CLI, Maven or JBang, as described in the [server README](https://github.com/quarkusio/quarkus-agent-mcp#prerequisites).

## Settings

| Setting | Default | |
|---|---|---|
| `quarkusAgent.launchMode` | `auto` | `auto`, `native`, `jar` or `jbang` |
| `quarkusAgent.version` | `latest` | A release such as `1.2.9` to pin it |
| `quarkusAgent.javaHome` | | JDK 21+ for the uber-jar |
| `quarkusAgent.env` | `{}` | Extra environment variables for the server |

## Commands

- **Quarkus Agent: Check for Updates**
- **Quarkus Agent: Show Server Log**: opens `~/.quarkus/agent-mcp/agent-mcp.log`. The server only writes it after the agent calls `quarkus_agent_log` with action `enable`.

What the extension itself did (which launcher it chose and why, downloads, errors) is in the **Quarkus Agent** output channel.

## Development

```bash
cd plugins/vscode
npm install
npm test          # unit tests for launcher resolution
npm run package   # builds quarkus-agent-<version>.vsix
```

Press F5 with this folder open in VS Code to run it in an Extension Development Host, or install the `.vsix` with *Extensions: Install from VSIX...*.
