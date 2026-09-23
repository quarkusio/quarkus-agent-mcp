import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { Java } from '../src/java';
import { JBANG_ALIAS, resolveLaunch, ResolveOptions } from '../src/launch';
import { Release } from '../src/releases';

const RELEASE: Release = {
    version: '1.2.9',
    assets: {
        'quarkus-agent-mcp-1.2.9-linux-x86_64': 'https://example/linux',
        'quarkus-agent-mcp-1.2.9-macos-aarch64': 'https://example/macos',
        'quarkus-agent-mcp-1.2.9-runner.jar': 'https://example/jar',
    },
};
const JAVA: Java = { source: 'JAVA_HOME', executable: '/jdk/bin/java', home: '/jdk', version: 21 };

function options(overrides: Partial<ResolveOptions> = {}): ResolveOptions & { downloads: string[] } {
    const downloads: string[] = [];
    return {
        mode: 'auto',
        version: 'latest',
        storageDir: mkdtempSync(path.join(os.tmpdir(), 'servers-')),
        javaCandidates: [],
        platform: 'linux',
        arch: 'x64',
        getRelease: async () => RELEASE,
        download: async (url, destination) => {
            downloads.push(url);
            mkdirSync(path.dirname(destination), { recursive: true });
            writeFileSync(destination, '');
        },
        findJava: async () => JAVA,
        log: () => undefined,
        downloads,
        ...overrides,
    };
}

test('auto prefers the native binary and downloads it once', async () => {
    const opts = options();
    const launch = await resolveLaunch(opts);
    assert.equal(launch.kind, 'native');
    assert.equal(launch.command, path.join(opts.storageDir, '1.2.9', 'quarkus-agent-mcp-1.2.9-linux-x86_64'));
    assert.equal(launch.version, '1.2.9');
    await resolveLaunch(opts);
    assert.deepEqual(opts.downloads, ['https://example/linux']);
});

test('auto uses the jar with the found JDK where no native binary exists', async () => {
    const opts = options({ platform: 'win32' });
    const launch = await resolveLaunch(opts);
    assert.equal(launch.kind, 'jar');
    assert.equal(launch.command, JAVA.executable);
    assert.deepEqual(launch.args.slice(-2), ['-jar', path.join(opts.storageDir, '1.2.9', 'quarkus-agent-mcp-1.2.9-runner.jar')]);
    assert.deepEqual(launch.env, { JAVA_HOME: '/jdk' });
});

test('auto uses the jar when the release lacks this platform\'s binary', async () => {
    const launch = await resolveLaunch(options({ platform: 'darwin', arch: 'x64' }));
    assert.equal(launch.kind, 'jar');
});

test('auto falls back to JBang without Java', async () => {
    const launch = await resolveLaunch(options({ platform: 'win32', findJava: async () => undefined, jbang: 'C:/jbang.cmd' }));
    assert.deepEqual([launch.kind, launch.command, launch.args], ['jbang', 'C:/jbang.cmd', [JBANG_ALIAS]]);
});

test('auto explains what is missing when nothing can launch', async () => {
    await assert.rejects(resolveLaunch(options({ platform: 'win32', findJava: async () => undefined })),
        /no Java 21\+ found.*JBang was not found/);
});

test('offline, the newest downloaded version is used', async () => {
    const opts = options({ getRelease: async () => { throw new Error('offline'); } });
    for (const v of ['1.2.8', '1.2.9']) {
        mkdirSync(path.join(opts.storageDir, v));
        writeFileSync(path.join(opts.storageDir, v, `quarkus-agent-mcp-${v}-linux-x86_64`), '');
    }
    const launch = await resolveLaunch(opts);
    assert.equal(launch.version, '1.2.9');
    assert.deepEqual(opts.downloads, []);
});

test('native mode fails clearly on an unsupported platform', async () => {
    await assert.rejects(resolveLaunch(options({ mode: 'native', platform: 'win32' })), /no native binary is published for win32-x64/);
});

test('jbang mode never looks up a release', async () => {
    const launch = await resolveLaunch(options({
        mode: 'jbang', jbang: '/usr/bin/jbang', getRelease: async () => { throw new Error('should not be called'); },
    }));
    assert.equal(launch.kind, 'jbang');
});
