import { createWriteStream } from 'node:fs';
import { chmod, mkdir, readdir, rename, rm } from 'node:fs/promises';
import * as path from 'node:path';
import { Readable } from 'node:stream';
import { pipeline } from 'node:stream/promises';
import type { ReadableStream } from 'node:stream/web';

export const REPO = 'quarkusio/quarkus-agent-mcp';

export interface Release {
    version: string;
    /** Asset file name to download URL. */
    assets: Record<string, string>;
}

/** The native binary suffix the release workflow publishes for this platform, if any. */
export function nativeSuffix(platform: NodeJS.Platform = process.platform, arch: NodeJS.Architecture = process.arch): string | undefined {
    if (platform === 'linux' && arch === 'x64') {
        return 'linux-x86_64';
    }
    if (platform === 'darwin') {
        return arch === 'arm64' ? 'macos-aarch64' : arch === 'x64' ? 'macos-x86_64' : undefined;
    }
    return undefined;
}

export function nativeAssetName(version: string, suffix: string): string {
    return `quarkus-agent-mcp-${version}-${suffix}`;
}

export function jarAssetName(version: string): string {
    return `quarkus-agent-mcp-${version}-runner.jar`;
}

/** Fetches `latest` or a specific tag from the GitHub Releases API. */
export async function fetchRelease(version: string, signal?: AbortSignal): Promise<Release> {
    const tag = version === 'latest' ? 'latest' : `tags/${encodeURIComponent(version)}`;
    const response = await fetch(`https://api.github.com/repos/${REPO}/releases/${tag}`, {
        headers: { Accept: 'application/vnd.github+json', 'User-Agent': 'quarkus-agent-vscode' },
        signal,
    });
    if (!response.ok) {
        throw new Error(`GitHub returned ${response.status} for release '${version}' of ${REPO}`);
    }
    const body = await response.json() as { tag_name: string; assets: { name: string; browser_download_url: string }[] };
    return {
        version: body.tag_name,
        assets: Object.fromEntries(body.assets.map(asset => [asset.name, asset.browser_download_url])),
    };
}

/** Downloads to a temporary file first, so an interrupted download never looks installed. */
export async function download(url: string, destination: string, executable: boolean, signal?: AbortSignal): Promise<void> {
    const response = await fetch(url, { headers: { 'User-Agent': 'quarkus-agent-vscode' }, signal });
    if (!response.ok || !response.body) {
        throw new Error(`Download of ${url} failed with HTTP ${response.status}`);
    }
    await mkdir(path.dirname(destination), { recursive: true });
    const partial = `${destination}.partial`;
    try {
        await pipeline(Readable.fromWeb(response.body as ReadableStream), createWriteStream(partial));
        if (executable) {
            await chmod(partial, 0o755);
        }
        await rename(partial, destination);
    } catch (error) {
        await rm(partial, { force: true });
        throw error;
    }
}

/** Compares dotted versions numerically, so 1.2.10 sorts after 1.2.9. */
export function compareVersions(a: string, b: string): number {
    const pa = a.split(/[.-]/);
    const pb = b.split(/[.-]/);
    for (let i = 0; i < Math.max(pa.length, pb.length); i++) {
        const na = Number(pa[i] ?? 0);
        const nb = Number(pb[i] ?? 0);
        if (Number.isNaN(na) || Number.isNaN(nb)) {
            const cmp = (pa[i] ?? '').localeCompare(pb[i] ?? '');
            if (cmp !== 0) {
                return cmp;
            }
        } else if (na !== nb) {
            return na - nb;
        }
    }
    return 0;
}

/** Versions already downloaded into `storageDir`, newest first. */
export async function installedVersions(storageDir: string): Promise<string[]> {
    try {
        const entries = await readdir(storageDir, { withFileTypes: true });
        return entries.filter(e => e.isDirectory()).map(e => e.name).sort(compareVersions).reverse();
    } catch {
        return [];
    }
}

/** Keeps `keep` and the newest other version (it may still be running), removes the rest. */
export async function pruneVersions(storageDir: string, keep: string): Promise<void> {
    const others = (await installedVersions(storageDir)).filter(v => v !== keep);
    for (const version of others.slice(1)) {
        await rm(path.join(storageDir, version), { recursive: true, force: true }).catch(() => undefined);
    }
}
