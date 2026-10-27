import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { pathToFileURL, fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';

const require = createRequire(import.meta.url);

/** 编译实际消费声明的 JS 模块，并证明错误参数被 TypeScript 拒绝。 */
export async function verifyRemoteClient(project) {
  const web = path.join(project, 'consumer/target/classes/web');
  const probe = path.join(web, 'type-probe.mts');
  const compiler = require.resolve('typescript/bin/tsc');
  const compile = file => spawnSync(process.execPath, [compiler, '--noEmit', '--strict', '--allowJs', '--checkJs',
    '--target', 'es2022', '--module', 'nodenext', '--moduleResolution', 'nodenext', file], { encoding: 'utf8' });
  try {
    const actual = compile(path.join(web, 'text-metrics.mjs'));
    assert.equal(actual.status, 0, actual.stdout + actual.stderr);
    fs.writeFileSync(probe, "import {createClient} from './generated/com.example.textmetrics.api.TextMetrics.mjs';\n"
      + "createClient().analyze({texts:[123]});\n", 'utf8');
    const rejected = compile(probe);
    assert.notEqual(rejected.status, 0, 'wrong frontend type must not compile');
    assert.match(rejected.stdout + rejected.stderr, /TS2322/u);
    const { analyzeTexts } = await import(pathToFileURL(path.join(web, 'text-metrics.mjs')).href);
    const { contractIdentity } = await import(pathToFileURL(path.join(web, 'generated/com.example.textmetrics.api.TextMetrics.mjs')).href);
    let calls = 0;
    const fetcher = async () => {
      calls++;
      return new Response(JSON.stringify({lengths:[3,2],total:5}), {headers:{'X-Pixiv-Contract':contractIdentity}});
    };
    assert.deepEqual(await analyzeTexts({texts:['abc','你好']}, undefined, fetcher), {lengths:[3,2],total:5});
    await assert.rejects(analyzeTexts({texts:[42]}, undefined, fetcher), error => error.code === 'REMOTE_VALUE_INVALID');
    assert.equal(calls,1);
  } finally { fs.rmSync(probe, {force:true}); }
}

if (path.resolve(process.argv[1] ?? '') === fileURLToPath(import.meta.url)) {
  await verifyRemoteClient(path.resolve(process.argv[2]));
}
