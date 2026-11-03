import assert from 'node:assert/strict';
import test from 'node:test';

import { normalizeJavap, collectApiSurface } from '../sdk-api-surface.mjs';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {execFileSync} from 'node:child_process';

test('Javap 表面只保留公开与受保护类型成员', () => {
    const output = `Compiled from "Example.java"
public class sample.Example<T> {
  public static final int LIMIT = 3;
    descriptor: I
  protected java.util.List<T> values();
    descriptor: ()Ljava/util/List;
  static {};
    descriptor: ()V
}
final class sample.Internal {
  public void hiddenWithItsType();
    descriptor: ()V
}`;
    assert.deepEqual(normalizeJavap('plugin-api', output), [
        'plugin-api\tMEMBER\tpublic class sample.Example<T>\tprotected java.util.List<T> values();\tdescriptor: ()Ljava/util/List;',
        'plugin-api\tMEMBER\tpublic class sample.Example<T>\tpublic static final int LIMIT = 3;\tdescriptor: I',
        'plugin-api\tTYPE\tpublic class sample.Example<T>'
    ]);
});

test('实际 JAR 中超过 Windows 命令行长度的类型仍全部进入 ABI 表面', () => {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'sdk-surface-'));
    try {
        const names = Array.from({length: 320}, (_, index) => 'Type' + 'x'.repeat(105) + index);
        fs.writeFileSync(path.join(root, 'Surface.java'), 'public class Surface {' + names.map(name =>
            'public static class ' + name + ' { public int value; }').join('\n') + '}');
        const run = (tool, args) => execFileSync(tool, args, {cwd: root, windowsHide: true, stdio: 'pipe'});
        run('javac', ['-encoding', 'UTF-8', 'Surface.java']);
        run('jar', ['--create', '--file', 'surface.jar', '.']);
        const surface = collectApiSurface([{name: 'fixture', path: path.join(root, 'surface.jar')}]);
        assert.equal(surface.split('\n').filter(line => line.includes('\tTYPE\t')).length, names.length + 1);
        for (const name of names) assert.ok(surface.includes('Surface$' + name + '\tpublic int value;'));
    } finally { fs.rmSync(root, {recursive: true, force: true}); }
});
