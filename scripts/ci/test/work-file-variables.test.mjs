import {test} from 'node:test';
import {execFileSync} from 'node:child_process';

test('下载与导入变量的 Java 和浏览器定义保持同源', () => {
    execFileSync(process.execPath, ['scripts/templates/generate-work-file-variables.mjs', '--check'], {
        cwd: new URL('../../../', import.meta.url), stdio: 'pipe'
    });
});
