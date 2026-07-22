package io.quarkus.agent.mcp;

import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

@QuarkusMain
public class Main {

    public static void main(String[] args) {
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            printHelp();
            return;
        }
        Quarkus.run(args);
    }

    private static void printHelp() {
        System.err.println("""
                Quarkus Agent MCP — MCP server for AI coding agents

                This server speaks the Model Context Protocol (MCP) over stdio.
                It is designed to be launched by an AI coding agent (Claude Code, etc.),
                not run interactively.

                INSTALL (native binary — recommended):
                  curl -sL https://github.com/quarkusio/quarkus-agent-mcp/releases/latest/download/install.sh | bash

                INSTALL (JBang):
                  claude mcp add -s user quarkus-agent -- jbang quarkus-agent-mcp@quarkusio

                MORE INFO:
                  https://github.com/quarkusio/quarkus-agent-mcp""");
    }
}
