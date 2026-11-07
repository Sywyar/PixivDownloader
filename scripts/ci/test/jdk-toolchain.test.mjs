import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { writeJavaHome } from '../../jdk-toolchain.mjs';

function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'pixiv-jdk-'));
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    const home = path.join(root, '开发 JDK');
    fs.mkdirSync(path.join(home, 'bin'), { recursive: true });
    fs.mkdirSync(path.join(home, 'jmods'));
    for (const name of ['java', 'javac']) {
        fs.writeFileSync(path.join(home, 'bin', name + (process.platform === 'win32' ? '.exe' : '')), 'fixture');
    }
    fs.writeFileSync(path.join(home, 'release'), 'JAVA_VERSION="99.0.1"\n');
    return { home, output: path.join(root, 'target', 'jdk.properties') };
}

test('选定 JDK 导出为 Ant 可读取的属性，保留空格和非 ASCII 路径', t => {
    const { home, output } = fixture(t);
    writeJavaHome(output, '99', home);
    const properties = fs.readFileSync(output, 'utf8');
    assert.match(properties, /\\u5f00\\u53d1 JDK/);
    assert.equal(properties.replace(/\\u([0-9a-f]{4})/g, (_, hex) => String.fromCharCode(parseInt(hex, 16))),
        `pixivdownload.toolchain.java.home=${fs.realpathSync(home)}\n`);
});

test('错误版本与不完整 JDK 不生成或覆盖属性', t => {
    const { home, output } = fixture(t);
    assert.throws(() => writeJavaHome(output, '100', home), /must be Java 100/);
    assert.equal(fs.existsSync(output), false);
    writeJavaHome(output, '99', home);
    const before = fs.readFileSync(output);
    fs.unlinkSync(path.join(home, 'bin', 'javac' + (process.platform === 'win32' ? '.exe' : '')));
    assert.throws(() => writeJavaHome(output, '99', home), { code: 'ENOENT' });
    assert.deepEqual(fs.readFileSync(output), before);
});
