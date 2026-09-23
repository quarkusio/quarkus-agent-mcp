import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync } from 'node:fs';
import * as os from 'node:os';
import * as path from 'node:path';
import { test } from 'node:test';
import { findJava, findOnPath, javaExecutable, parseJavaVersion } from '../src/java';

function fakeJdk(): string {
    const home = mkdtempSync(path.join(os.tmpdir(), 'jdk-'));
    mkdirSync(path.join(home, 'bin'));
    writeFileSync(javaExecutable(home), '');
    return home;
}

test('parses modern and legacy java -version output', () => {
    assert.equal(parseJavaVersion('openjdk version "21.0.2" 2024-01-16'), 21);
    assert.equal(parseJavaVersion('openjdk version "25" 2025-09-16'), 25);
    assert.equal(parseJavaVersion('java version "1.8.0_392"'), 8);
    assert.equal(parseJavaVersion('garbage'), undefined);
});

test('skips missing and too-old JDKs and reports why', async () => {
    const old = fakeJdk();
    const good = fakeJdk();
    const versions: Record<string, number> = { [javaExecutable(old)]: 17, [javaExecutable(good)]: 21 };
    const rejected: string[] = [];
    const java = await findJava([
        { source: 'missing', home: path.join(os.tmpdir(), 'no-such-jdk') },
        { source: 'old', home: old },
        { source: 'good', home: good },
    ], rejected, async exe => versions[exe]);
    assert.equal(java?.source, 'good');
    assert.equal(java?.home, good);
    assert.equal(rejected.length, 2);
    assert.match(rejected[1], /Java 17 is older than 21/);
});

test('finds commands on the PATH', () => {
    const home = fakeJdk();
    const bin = path.join(home, 'bin');
    const nowhere = path.join(home, 'nowhere');
    const env = { PATHEXT: '.EXE;.CMD' };
    assert.equal(findOnPath('java', { ...env, PATH: [nowhere, bin].join(path.delimiter) })?.toLowerCase(), javaExecutable(home).toLowerCase());
    assert.equal(findOnPath('java', { ...env, PATH: nowhere }), undefined);
});
