# Contributing Extension Documentation to RAG Search

This guide explains how to make your extension's documentation searchable by the Quarkus AI assistant. When a developer asks the assistant about your extension, it searches a vector database of documentation chunks to find relevant answers.

## How it works

The [quarkus-documentation-rag](https://github.com/quarkusio/quarkus-documentation-rag) Maven plugin processes your AsciiDoc guide into vector embeddings and outputs a SQL file (`META-INF/quarkus-rag.sql`) in your deployment JAR. At runtime, the Quarkus Agent MCP server discovers these SQL files and loads them into a pgvector database for semantic search.

The pipeline:

1. **Build time** — the Maven plugin parses your AsciiDoc, splits it into semantic chunks at section boundaries, generates 384-dimension vector embeddings (BGE Small EN v1.5), and writes idempotent SQL (`DELETE` by source + `INSERT` with embeddings and metadata).
2. **Runtime** — the MCP server starts a generic `pgvector/pgvector:pg17` container and loads all discovered SQL fragments into it.

No centralized rebuild is needed — each extension independently produces its own SQL fragment.

## Quarkiverse extensions

Once [quarkiverse-parent#235](https://github.com/quarkiverse/quarkiverse-parent/pull/235) is merged, Quarkiverse extensions can opt in by setting a single property in their **deployment** module's `pom.xml`:

```xml
<properties>
    <quarkus-rag.guide>docs/modules/ROOT/pages/index.adoc</quarkus-rag.guide>
</properties>
```

Then build with the RAG profile:

```bash
mvn install -Prag
```

That's it. The plugin uses your `${project.artifactId}` as the source name and silently skips modules where the property is not set.

### What the property points to

The `quarkus-rag.guide` property should be a path (relative to the module root) to your extension's main AsciiDoc guide. This is typically the Antora page that documents your extension's usage.

### Multiple guides

If your extension has multiple documentation pages, point to the main one. The plugin processes a single guide per module. If you need to include multiple pages, you can set up additional executions of the plugin manually — see the [plugin README](https://github.com/quarkusio/quarkus-documentation-rag) for configuration options.

## Quarkus core extensions

Core Quarkus documentation is handled centrally by the `devtools/extension-rag/` module ([quarkus#54119](https://github.com/quarkusio/quarkus/pull/54119)), which processes all ~274 AsciiDoc guides into a single aggregated artifact (`io.quarkus:quarkus-documentation-core-rag`). Individual core extensions don't need to do anything — the aggregated artifact covers all core guides.

The aggregated artifact is published to Maven Central with each Quarkus release and downloaded automatically by the MCP server when needed.

## Third-party libraries (non-Quarkiverse)

If your project doesn't inherit from `quarkiverse-parent`, you can add the plugin directly:

```xml
<plugin>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-documentation-rag-maven-plugin</artifactId>
    <version>${quarkus-documentation-rag.version}</version>
    <executions>
        <execution>
            <goals><goal>generate-rag</goal></goals>
            <configuration>
                <guides>
                    <guide>${project.basedir}/docs/guide.adoc</guide>
                </guides>
                <extensionName>${project.artifactId}</extensionName>
            </configuration>
        </execution>
    </executions>
</plugin>
```

The SQL file will be placed in `META-INF/quarkus-rag.sql` inside your deployment JAR. The MCP server discovers it automatically when the JAR is in the user's local Maven repository.

## How discovery works

The MCP server searches for RAG SQL in this order:

1. **Aggregated artifact** — checks for `io.quarkus:quarkus-documentation-core-rag:{version}` in `~/.m2/repository`. Downloads from Maven Central if not found locally (release versions only).
2. **Individual core extension JARs** — if no aggregated artifact is found, scans `~/.m2/repository/io/quarkus/quarkus-*-deployment/{version}/` for JARs containing `META-INF/quarkus-rag.sql`.
3. **Non-core extension JARs** — always parses the project's `pom.xml` to find Quarkiverse and third-party dependencies, then checks their deployment JARs for `META-INF/quarkus-rag.sql`.

Loading is incremental — when you add an extension to a project, the MCP server automatically discovers and loads its documentation without restarting. Each SQL fragment is identified by its source name, and only new sources are loaded.

## Verifying your contribution

After building with `-Prag`, verify the SQL was generated:

```bash
jar tf your-extension-deployment/target/your-extension-deployment-*.jar | grep quarkus-rag
```

You should see `META-INF/quarkus-rag.sql`. You can inspect it — it contains `DELETE` and `INSERT` statements with vector embeddings.

To test end-to-end, start the Quarkus Agent MCP server and search for a term from your documentation:

```
quarkus_searchDocs query="your extension feature"
```
