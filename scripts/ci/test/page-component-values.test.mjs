import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
const codecs = new URL('../../../pixivdownload-plugin-api/src/main/resources/META-INF/pixivdownload/web/value-codecs.mjs', import.meta.url);
const source = fs.readFileSync(new URL('../../../pixivdownload-app/src/main/resources/static/js/pixiv-pages.mjs', import.meta.url), 'utf8')
    .replace("'/sdk/values/value-codecs.mjs'", JSON.stringify(codecs.href));
const {pureValue, portValue, connectionDispatcher} = await import('data:text/javascript;base64,' + Buffer.from(source).toString('base64'));

test('页面配置保留嵌套纯值且拒绝可执行或丢失语义的数据', () => {
    const value = {items: [{label: 'entry', checked: true}], optional: null};
    const copy = pureValue(value);
    assert.deepEqual(copy, value);
    copy.items[0].label = 'changed';
    assert.equal(value.items[0].label, 'entry');
    for (const invalid of [{run() {}}, {missing: undefined}, {number: NaN}, {date: new Date()}, {map: new Map()}])
        assert.throws(() => pureValue(invalid), /PAGE_VALUE_INVALID/);
    assert.throws(() => pureValue('x'.repeat(1024 * 1024)), /PAGE_VALUE_INVALID/);
});

test('端口按声明拒绝类型漂移，保留嵌套值及数值边界', () => {
    const port = {schema: JSON.stringify({type: 'object', properties: {
        text: {type: 'string', maxLength: 4}, number: {type: 'integer', minimum: 0, maximum: 10}
    }, required: ['text', 'number'], additionalProperties: false})};
    assert.deepEqual(portValue(port, {text: '🙂🙂', number: 10}), {text: '🙂🙂', number: 10});
    for (const value of [{text: 'five!', number: 1}, {text: 'ok', number: 11},
        {text: 'ok', number: '1'}, {text: 'ok', number: 1, extra: true}])
        assert.throws(() => portValue(port, value), /PAGE_VALUE_INVALID/);
});

test('端口复用远程标量编码并拒绝溢出与非有限值', () => {
    const port = encoding => ({schema: JSON.stringify({type:'string','x-encoding':encoding})});
    assert.equal(portValue(port('INSTANT_SECONDS_NANOS'), '31556889864403199:999999999'), '31556889864403199:999999999');
    assert.equal(portValue(port('FLOAT32_HEX'), '80000000'), '80000000');
    assert.throws(() => portValue(port('FLOAT32_HEX'), '7f800000'), /PAGE_VALUE_INVALID/);
    assert.throws(() => portValue(port('INSTANT_SECONDS_NANOS'), '31556889864403200:000000000'), /PAGE_VALUE_INVALID/);
});

test('链式转发等待异步输入并隔离坏订阅者，关闭后释放排队值', async () => {
    const received = [], failures = [];
    let finish;
    const done = new Promise(resolve => { finish = resolve; });
    const dispatcher = connectionDispatcher([
        {from: 'a', output: 'out', to: 'b', input: 'in'},
        {from: 'b', output: 'out', to: 'bad', input: 'in'},
        {from: 'b', output: 'out', to: 'c', input: 'in'}
    ], async (to, port, value) => {
        received.push([to, value]);
        if (to === 'bad') throw new Error('provider private error');
        if (to === 'b') { await Promise.resolve(); dispatcher.emit('b', 'out', value + 1); }
        if (to === 'c') finish();
    }, error => failures.push(error.message));
    dispatcher.emit('a', 'out', 1);
    assert.deepEqual(received, []);
    dispatcher.start(); await done;
    assert.deepEqual(received, [['b', 1], ['bad', 2], ['c', 2]]);
    assert.deepEqual(failures, ['PAGE_CONNECTION_FAILED']);
    dispatcher.destroy(); dispatcher.emit('a', 'out', 3);
    await Promise.resolve();
    assert.equal(received.length, 3);
    assert.throws(() => connectionDispatcher([
        {from: 'a', to: 'b'}, {from: 'b', to: 'c'}, {from: 'c', to: 'a'}
    ], () => {}), /PAGE_CONNECTION_CYCLE/);
});

test('转发队列容量拒绝不改变已接纳内容', async () => {
    let count = 0;
    const dispatcher = connectionDispatcher([{from: 'a', output: 'out', to: 'b', input: 'in'}], () => { count++; });
    for (let i = 0; i < 256; i++) dispatcher.emit('a', 'out', i);
    assert.throws(() => dispatcher.emit('a', 'out', 257), /PAGE_CONNECTION_BUDGET/);
    dispatcher.destroy(); dispatcher.start(); await Promise.resolve();
    assert.equal(count, 0);
});
