import { existsSync } from 'node:fs';
import * as path from 'node:path';
import { findJava as defaultFindJava, Java, JavaCandidate, MIN_JAVA_VERSION } from './java';
import { installedVersions, jarAssetName, nativeAssetName, nativeSuffix, pruneVersions, Release } from './releases';

export type LaunchMode = 'auto' | 'native' | 'jar' | 'jbang';

export interface Launch {
    kind: 'native' | 'jar' | 'jbang';
    command: string;
    args: string[];
    env: Record<string, string>;
    /** The server release being launched; unknown for JBang, which resolves it itself. */
    version?: string;
}

/** Same JVM options as the quarkus-agent-mcp alias in jbang-catalog.json. */
export const JAR_RUNTIME_OPTIONS = [
    '--add-opens', 'java.base/java.lang=ALL-UNNAMED',
    '--enable-native-access=ALL-UNNAMED',
    '-Xmx512m',
    '-XX:+UseSerialGC',
];

export const JBANG_ALIAS = 'quarkus-agent-mcp@quarkusio';

export interface ResolveOptions {
    mode: LaunchMode;
    /** 'latest' or a release tag such as 1.2.9. */
    version: string;
    /** Downloads go to `<storageDir>/<version>/<asset>`. */
    storageDir: string;
    javaCandidates: JavaCandidate[];
    /** Absolute path of the jbang launcher, if one was found. */
    jbang?: string;
    platform?: NodeJS.Platform;
    arch?: NodeJS.Architecture;
    getRelease(version: string): Promise<Release>;
    download(url: string, destination: string, executable: boolean): Promise<void>;
    findJava?(candidates: JavaCandidate[], rejected: string[]): Promise<Java | undefined>;
    log(message: string): void;
}

export async function resolveLaunch(options: ResolveOptions): Promise<Launch> {
    const { mode, log } = options;
    const findJava = options.findJava ?? defaultFindJava;

    if (mode === 'jbang') {
        if (!options.jbang) {
            throw new Error('JBang was not found on the PATH or in ~/.jbang/bin. Install it from https://www.jbang.dev/download/ or change quarkusAgent.launchMode.');
        }
        return jbangLaunch(options.jbang);
    }

    const problems: string[] = [];
    const release = await releaseOrInstalled(options, problems);

    if (mode === 'native' || mode === 'auto') {
        const suffix = nativeSuffix(options.platform, options.arch);
        if (!suffix) {
            problems.push(`no native binary is published for ${options.platform ?? process.platform}-${options.arch ?? process.arch}`);
        } else if (release) {
            const binary = await ensureAsset(options, release, nativeAssetName(release.version, suffix), true);
            if (binary) {
                log(`Using native binary ${binary}`);
                return { kind: 'native', command: binary, args: [], env: {}, version: release.version };
            }
            problems.push(`release ${release.version} has no ${suffix} native binary`);
        }
        if (mode === 'native') {
            throw launchError('Could not launch the native binary', problems);
        }
    }

    const rejected: string[] = [];
    const java = await findJava(options.javaCandidates, rejected);
    if (!java) {
        problems.push(`no Java ${MIN_JAVA_VERSION}+ found${rejected.length ? ` (${rejected.join('; ')})` : ''}`);
    } else if (release) {
        const jar = await ensureAsset(options, release, jarAssetName(release.version), false);
        if (jar) {
            log(`Using ${jar} with Java ${java.version} from ${java.source}`);
            return {
                kind: 'jar',
                command: java.executable,
                args: [...JAR_RUNTIME_OPTIONS, '-jar', jar],
                env: java.home ? { JAVA_HOME: java.home } : {},
                version: release.version,
            };
        }
        problems.push(`release ${release.version} has no uber-jar`);
    }
    if (mode === 'jar') {
        throw launchError('Could not launch the uber-jar', problems);
    }

    if (options.jbang) {
        log(`Falling back to JBang (${problems.join('; ')})`);
        return jbangLaunch(options.jbang);
    }
    problems.push('JBang was not found');
    throw launchError('Could not find a way to launch the Quarkus Agent MCP server', problems,
        `Install Java ${MIN_JAVA_VERSION}+ (or set quarkusAgent.javaHome) or JBang, then restart the server.`);
}

function jbangLaunch(jbang: string): Launch {
    return { kind: 'jbang', command: jbang, args: [JBANG_ALIAS], env: {} };
}

/**
 * The requested release from GitHub, or when GitHub can't be reached, the newest matching
 * version already downloaded, so the server still starts offline.
 */
async function releaseOrInstalled(options: ResolveOptions, problems: string[]): Promise<Release | undefined> {
    try {
        return await options.getRelease(options.version);
    } catch (error) {
        const installed = await installedVersions(options.storageDir);
        const fallback = options.version === 'latest' ? installed[0] : installed.find(v => v === options.version);
        options.log(`Could not look up release '${options.version}': ${message(error)}`
            + (fallback ? `; using downloaded ${fallback}` : ''));
        if (!fallback) {
            problems.push(`release '${options.version}' could not be looked up (${message(error)}) and nothing is downloaded yet`);
        }
        return fallback ? { version: fallback, assets: {} } : undefined;
    }
}

async function ensureAsset(options: ResolveOptions, release: Release, name: string, executable: boolean): Promise<string | undefined> {
    const destination = path.join(options.storageDir, release.version, name);
    if (existsSync(destination)) {
        return destination;
    }
    const url = release.assets[name];
    if (!url) {
        return undefined;
    }
    options.log(`Downloading ${url}`);
    await options.download(url, destination, executable);
    await pruneVersions(options.storageDir, release.version);
    return destination;
}

function launchError(summary: string, problems: string[], hint?: string): Error {
    return new Error(`${summary}: ${problems.join('; ')}.${hint ? ` ${hint}` : ''}`);
}

function message(error: unknown): string {
    return error instanceof Error ? error.message : String(error);
}
