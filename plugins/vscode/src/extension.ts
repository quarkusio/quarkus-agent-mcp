import { existsSync } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import * as vscode from 'vscode';
import { findOnPath, JavaCandidate } from './java';
import { Launch, LaunchMode, resolveLaunch } from './launch';
import { download, fetchRelease, Release } from './releases';

const PROVIDER_ID = 'quarkus-agent';
const LABEL = 'Quarkus Agent';
const LATEST_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000;
const SERVER_LOG = path.join(os.homedir(), '.quarkus', 'agent-mcp', 'agent-mcp.log');

interface CachedRelease {
    release: Release;
    checkedAt: number;
}

export function activate(context: vscode.ExtensionContext): void {
    const log = vscode.window.createOutputChannel(LABEL, { log: true });
    const changed = new vscode.EventEmitter<void>();
    const storageDir = path.join(context.globalStorageUri.fsPath, 'servers');

    /**
     * Release lookups are cached so starting the server doesn't hit the GitHub API
     * (60 unauthenticated requests an hour) every time. Tags never change; 'latest'
     * is re-checked daily or on "Check for Updates".
     */
    async function getRelease(version: string, force = false): Promise<Release> {
        const key = `release:${version}`;
        const cached = context.globalState.get<CachedRelease>(key);
        const fresh = cached && (version !== 'latest' || Date.now() - cached.checkedAt < LATEST_CHECK_INTERVAL_MS);
        if (cached && fresh && !force) {
            return cached.release;
        }
        const release = await fetchRelease(version);
        await context.globalState.update(key, { release, checkedAt: Date.now() } satisfies CachedRelease);
        return release;
    }

    function settings() {
        const config = vscode.workspace.getConfiguration('quarkusAgent');
        return {
            mode: config.get<LaunchMode>('launchMode', 'auto'),
            version: config.get<string>('version', 'latest').trim() || 'latest',
            javaHome: config.get<string>('javaHome', '').trim(),
            env: config.get<Record<string, string>>('env', {}),
        };
    }

    /** The version shown before the server is resolved; a change makes VS Code offer a refresh. */
    function knownVersion(): string | undefined {
        const { mode, version } = settings();
        if (mode === 'jbang') {
            return undefined;
        }
        return version === 'latest'
            ? context.globalState.get<CachedRelease>('release:latest')?.release.version
            : version;
    }

    const provider: vscode.McpServerDefinitionProvider<vscode.McpStdioServerDefinition> = {
        onDidChangeMcpServerDefinitions: changed.event,

        provideMcpServerDefinitions: () => [
            // The real command is filled in by resolveMcpServerDefinition, which may need to download it.
            new vscode.McpStdioServerDefinition(LABEL, 'quarkus-agent-mcp', [], {}, knownVersion()),
        ],

        resolveMcpServerDefinition: async server => {
            const { mode, version, javaHome, env } = settings();
            let launch: Launch;
            try {
                launch = await resolveLaunch({
                    mode,
                    version,
                    storageDir,
                    javaCandidates: javaCandidates(javaHome),
                    jbang: findJBang(),
                    getRelease: v => getRelease(v),
                    download: async (url, destination, executable) => {
                        await vscode.window.withProgress(
                            { location: vscode.ProgressLocation.Notification, title: `Downloading ${path.basename(destination)}` },
                            () => download(url, destination, executable));
                    },
                    log: m => log.info(m),
                });
            } catch (error) {
                const text = error instanceof Error ? error.message : String(error);
                log.error(text);
                void vscode.window.showErrorMessage(`${LABEL}: ${text}`, 'Open Settings').then(choice => {
                    if (choice) {
                        void vscode.commands.executeCommand('workbench.action.openSettings', 'quarkusAgent');
                    }
                });
                throw error;
            }
            log.info(`Starting ${launch.kind}: ${launch.command} ${launch.args.join(' ')}`);
            server.command = launch.command;
            server.args = launch.args;
            server.env = { ...server.env, ...launch.env, ...env };
            // Not the IDE's working directory: on Windows that can be system32, where Quarkus
            // trips over the protected config directory (quarkusio/quarkus#53739).
            server.cwd = vscode.Uri.file(context.globalStorageUri.fsPath);
            await vscode.workspace.fs.createDirectory(server.cwd);
            return server;
        },
    };

    context.subscriptions.push(
        log,
        changed,
        vscode.lm.registerMcpServerDefinitionProvider(PROVIDER_ID, provider),
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration('quarkusAgent')) {
                changed.fire();
            }
        }),
        vscode.commands.registerCommand('quarkusAgent.checkForUpdates', async () => {
            const { mode, version } = settings();
            if (mode === 'jbang') {
                void vscode.window.showInformationMessage(`${LABEL}: JBang resolves the latest release on its own.`);
                return;
            }
            if (version !== 'latest') {
                void vscode.window.showInformationMessage(`${LABEL}: pinned to ${version} by the quarkusAgent.version setting.`);
                return;
            }
            const before = knownVersion();
            try {
                const latest = await getRelease('latest', true);
                if (latest.version === before) {
                    void vscode.window.showInformationMessage(`${LABEL}: ${latest.version} is the latest release.`);
                } else {
                    changed.fire();
                    void vscode.window.showInformationMessage(
                        `${LABEL}: ${latest.version} is available${before ? ` (was ${before})` : ''}. It is used the next time the server starts.`);
                }
            } catch (error) {
                void vscode.window.showErrorMessage(`${LABEL}: ${error instanceof Error ? error.message : error}`);
            }
        }),
        vscode.commands.registerCommand('quarkusAgent.showServerLog', async () => {
            if (existsSync(SERVER_LOG)) {
                await vscode.window.showTextDocument(vscode.Uri.file(SERVER_LOG));
            } else {
                void vscode.window.showInformationMessage(
                    `${LABEL}: ${SERVER_LOG} does not exist yet. Ask the agent to call quarkus_agent_log with action 'enable' to start file logging.`);
            }
        }),
    );
}

export function deactivate(): void {
    // Nothing to clean up: VS Code stops the server process itself.
}

/** JDKs to try, in order: our setting, the Java extension's setting, JAVA_HOME, the PATH. */
function javaCandidates(javaHome: string): JavaCandidate[] {
    const candidates: JavaCandidate[] = [];
    if (javaHome) {
        candidates.push({ source: 'quarkusAgent.javaHome', home: javaHome });
    }
    const jdtHome = vscode.workspace.getConfiguration('java.jdt.ls.java').get<string>('home', '').trim();
    if (jdtHome) {
        candidates.push({ source: 'java.jdt.ls.java.home', home: jdtHome });
    }
    if (process.env.JAVA_HOME) {
        candidates.push({ source: 'JAVA_HOME', home: process.env.JAVA_HOME });
    }
    const onPath = findOnPath('java');
    if (onPath) {
        candidates.push({ source: 'PATH', executable: onPath });
    }
    return candidates;
}

function findJBang(): string | undefined {
    const onPath = findOnPath('jbang');
    if (onPath) {
        return onPath;
    }
    const installed = path.join(os.homedir(), '.jbang', 'bin', process.platform === 'win32' ? 'jbang.cmd' : 'jbang');
    return existsSync(installed) ? installed : undefined;
}
