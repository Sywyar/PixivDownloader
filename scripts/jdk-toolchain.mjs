import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// exec-maven-plugin 已选定 JDK；这里只把同一路径交给不支持 Toolchains 的 Ant。
export function writeJavaHome(output, version, javaHome = process.env.JAVA_HOME) {
    if (!output || !/^\d+$/.test(version ?? '') || !javaHome) {
        throw new Error('Expected output file, Java major version and selected JAVA_HOME');
    }
    const home = fs.realpathSync(javaHome);
    const actual = /^JAVA_VERSION="(\d+)[^"]*"/m.exec(fs.readFileSync(path.join(home, 'release'), 'utf8'))?.[1];
    if (actual !== version) throw new Error(`Selected JDK must be Java ${version}; found ${actual ?? 'unknown'} at ${home}`);
    for (const name of ['java', 'javac']) {
        const binary = path.join(home, 'bin', `${name}${process.platform === 'win32' ? '.exe' : ''}`);
        if (!fs.statSync(binary).isFile()) throw new Error(`Missing JDK executable: ${binary}`);
    }
    if (!fs.statSync(path.join(home, 'jmods')).isDirectory()) throw new Error(`Missing JDK modules: ${home}`);
    const escaped = home.replace(/[\\:=#!]|[^\x20-\x7e]/g,
        c => `\\u${c.charCodeAt(0).toString(16).padStart(4, '0')}`);
    fs.mkdirSync(path.dirname(output), { recursive: true });
    fs.writeFileSync(output, `pixivdownload.toolchain.java.home=${escaped}\n`, 'utf8');
    console.log(`Build JDK: ${home} (Java ${actual})`);
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
    try { writeJavaHome(...process.argv.slice(2)); }
    catch (error) { console.error(`JDK toolchain failed: ${error.message}`); process.exitCode = 1; }
}
