import { execFile } from 'node:child_process';
import { existsSync } from 'node:fs';
import * as path from 'node:path';

export const MIN_JAVA_VERSION = 21;

export interface JavaCandidate {
    /** Where the candidate came from, for log and error messages. */
    source: string;
    /** A JDK home directory; `bin/java` is used from it. */
    home?: string;
    /** A java executable, used when there is no home (e.g. found on the PATH). */
    executable?: string;
}

export interface Java {
    source: string;
    executable: string;
    home?: string;
    version: number;
}

export function javaExecutable(home: string, platform = process.platform): string {
    return path.join(home, 'bin', platform === 'win32' ? 'java.exe' : 'java');
}

/**
 * Parses the major version out of `java -version` output, which goes to stderr
 * and looks like `openjdk version "21.0.2" 2024-01-16` (or `"1.8.0_392"` for Java 8).
 */
export function parseJavaVersion(output: string): number | undefined {
    const match = /version "(\d+)(?:\.(\d+))?/.exec(output);
    if (!match) {
        return undefined;
    }
    const major = Number(match[1]);
    return major === 1 && match[2] ? Number(match[2]) : major;
}

export function javaVersion(executable: string): Promise<number | undefined> {
    return new Promise(resolve => {
        execFile(executable, ['-version'], { timeout: 10_000 }, (error, stdout, stderr) => {
            resolve(error ? undefined : parseJavaVersion(stderr || stdout));
        });
    });
}

/**
 * Returns the first candidate that exists and is at least {@link MIN_JAVA_VERSION}.
 * `rejected` collects the reason each earlier candidate was skipped.
 */
export async function findJava(
    candidates: JavaCandidate[],
    rejected: string[] = [],
    probe: (executable: string) => Promise<number | undefined> = javaVersion,
): Promise<Java | undefined> {
    for (const candidate of candidates) {
        const executable = candidate.home ? javaExecutable(candidate.home) : candidate.executable;
        if (!executable || !existsSync(executable)) {
            if (executable) {
                rejected.push(`${candidate.source}: ${executable} does not exist`);
            }
            continue;
        }
        const version = await probe(executable);
        if (version === undefined) {
            rejected.push(`${candidate.source}: could not run ${executable} -version`);
        } else if (version < MIN_JAVA_VERSION) {
            rejected.push(`${candidate.source}: Java ${version} is older than ${MIN_JAVA_VERSION}`);
        } else {
            return { source: candidate.source, executable, home: candidate.home, version };
        }
    }
    return undefined;
}

/** Looks a command up on a PATH string, honouring PATHEXT on Windows. */
export function findOnPath(
    command: string,
    env: NodeJS.ProcessEnv = process.env,
    platform = process.platform,
): string | undefined {
    const pathValue = env.PATH ?? env.Path ?? '';
    const extensions = platform === 'win32'
        ? (env.PATHEXT ?? '.EXE;.CMD;.BAT').split(';').filter(Boolean)
        : [''];
    const separator = platform === 'win32' ? ';' : ':';
    for (const dir of pathValue.split(separator).filter(Boolean)) {
        for (const extension of extensions) {
            const candidate = path.join(dir, command + extension);
            if (existsSync(candidate)) {
                return candidate;
            }
        }
    }
    return undefined;
}
