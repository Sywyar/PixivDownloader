import {test} from 'node:test';
import {execFileSync} from 'node:child_process';

test('计划参数的 Java 和浏览器缺省值保持同源生成', () => {
    execFileSync(process.execPath, ['scripts/schedule/generate-pixiv-defaults.mjs', '--check'], {
        cwd: new URL('../../../', import.meta.url), stdio: 'pipe'
    });
});
