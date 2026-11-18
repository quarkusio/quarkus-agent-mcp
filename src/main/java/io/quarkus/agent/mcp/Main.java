package io.quarkus.agent.mcp;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

@QuarkusMain
public class Main {

    public static void main(String[] args) {
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printHelp();
            return;
        }
        if (args.length > 0 && ("--version".equals(args[0]) || "-V".equals(args[0]))) {
            System.out.println(version());
            return;
        }
        Quarkus.run(args);
    }

    /**
     * Reads the version from a Maven-filtered resource. Quarkus config is not available
     * yet at this point, because {@code --version} returns before {@link Quarkus#run}.
     */
    static String version() {
        try (InputStream in = Main.class.getResourceAsStream("/quarkus-agent-mcp-version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                return props.getProperty("version", "unknown");
            }
        } catch (IOException e) {
            // fall through
        }
        return "unknown";
    }

    private static void printHelp() {
        System.err.println("""
                Quarkus Agent MCP — MCP server for AI coding agents

                This server speaks the Model Context Protocol (MCP) over stdio.
                It is designed to be launched by an AI coding agent (Claude Code, etc.),
                not run interactively.

                OPTIONS:
                  -h, --help       Show this help
                  -V, --version    Print the version and exit

                INSTALL (native binary — recommended):
                  curl -sL https://github.com/quarkusio/quarkus-agent-mcp/releases/latest/download/install.sh | bash

                INSTALL (JBang):
                  claude mcp add -s user quarkus-agent -- jbang quarkus-agent-mcp@quarkusio

                MORE INFO:
                  https://github.com/quarkusio/quarkus-agent-mcp""");
    }
}
