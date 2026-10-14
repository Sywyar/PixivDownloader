'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const root = resolve(__dirname, '../../main/resources/static');

for (const page of ['pixiv-batch.html', 'pixiv-batch-alt.html']) {
    test(`${page} 速度累计保留流隔离、字节回退、终态清理和计时生命周期`, () => {
        let now = 1000, next = 0;
        const timers = new Map(), speeds = [];
        const c = vm.createContext({state: {}, performance: {now: () => now},
            renderDownloadSpeed: value => speeds.push(value),
            setInterval(callback, interval) {
                assert.equal(interval, 1000);
                timers.set(++next, callback);
                return next;
            },
            clearInterval: id => timers.delete(id)});
        for (const [, src] of readFileSync(resolve(root, page), 'utf8')
            .matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)) {
            if (src.endsWith('/download-speed.js')) {
                vm.runInContext(readFileSync(resolve(root, '.' + src), 'utf8'), c);
            }
        }
        c.startSpeedMeter();
        c.accumulateDownloadSpeed('a', {imageProgress: {imageNumber: 1, downloadedBytes: 100},
            ugoiraProgress: {zipDownloadedBytes: 50}, coverDownloadedBytes: 25});
        c.startSpeedMeter();
        assert.equal(timers.size, 1);
        assert.equal(c.state.speedAccumBytes, 175);
        c.accumulateDownloadSpeed('a', {imageProgress: {imageNumber: 1, downloadedBytes: 100}});
        c.accumulateDownloadSpeed('a', {imageProgress: {imageNumber: 1, downloadedBytes: 20}});
        c.accumulateDownloadSpeed('b', {imageProgress: {downloadedBytes: 50}});
        for (const value of [-1, NaN, Infinity]) c.addSpeedSample('invalid', value);
        c.accumulateDownloadSpeed('a', {completed: true, imageProgress: {imageNumber: 1, downloadedBytes: 30}});
        assert.equal(c.state.speedAccumBytes, 255);
        assert.deepEqual(Object.keys(c.state.speedSamples), ['b:img:0']);
        now += 1000;
        [...timers.values()][0]();
        assert.equal(speeds.at(-1), 255);
        [...timers.values()][0]();
        assert.equal(speeds.at(-1), 0);
        for (const terminal of ['failed', 'cancelled']) {
            c.accumulateDownloadSpeed('terminal', {imageProgress: {downloadedBytes: 10},
                ugoiraProgress: {zipDownloadedBytes: 20}, coverDownloadedBytes: 30});
            c.accumulateDownloadSpeed('terminal', {[terminal]: true});
            assert.deepEqual(Object.keys(c.state.speedSamples), ['b:img:0']);
        }
        c.stopSpeedMeter();
        c.stopSpeedMeter();
        assert.equal(timers.size, 0);
        assert.equal(c.state.speedTimer, null);
        assert.equal(speeds.at(-1), 0);
        assert.deepEqual(Object.keys(c.state.speedSamples), []);
        c.startSpeedMeter();
        assert.equal(c.state.speedAccumBytes, 0);
        assert.equal(timers.size, 1);
        c.stopSpeedMeter();
    });
}
