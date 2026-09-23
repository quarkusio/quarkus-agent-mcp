import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { compareVersions, installedVersions, nativeSuffix, pruneVersions } from '../src/releases';

test('maps platforms to published native binaries', () => {
    assert.equal(nativeSuffix('linux', 'x64'), 'linux-x86_64');
    assert.equal(nativeSuffix('darwin', 'arm64'), 'macos-aarch64');
    assert.equal(nativeSuffix('darwin', 'x64'), 'macos-x86_64');
    assert.equal(nativeSuffix('linux', 'arm64'), undefined);
    assert.equal(nativeSuffix('win32', 'x64'), undefined);
});

test('compares versions numerically', () => {
    assert.ok(compareVersions('1.2.10', '1.2.9') > 0);
    assert.ok(compareVersions('1.3.0', '1.2.99') > 0);
    assert.equal(compareVersions('1.2.9', '1.2.9'), 0);
});

test('prunes all but the kept version and the newest other one', async () => {
    const dir = mkdtempSync(path.join(os.tmpdir(), 'servers-'));
    for (const v of ['1.2.7', '1.2.8', '1.2.9', '1.2.10']) {
        mkdirSync(path.join(dir, v));
    }
    await pruneVersions(dir, '1.2.10');
    assert.deepEqual(await installedVersions(dir), ['1.2.10', '1.2.9']);
});
