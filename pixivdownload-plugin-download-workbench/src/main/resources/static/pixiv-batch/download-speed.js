'use strict';

    // 基线与每秒采样定时器随所属页面的聚合 SSE 启停，各布局自行渲染速度。
    function speedNowMs() {
        return (typeof performance !== 'undefined' && performance.now) ? performance.now() : Date.now();
    }

    // 累计单条传输流的正增量：每个传输流（某作品的第 N 张图 / 动图包 / 小说封面）的字节单调递增，
    // 用 key 记住上次见到的值，只把正增量计入全局累计，从而跨多作品 / 多图正确汇总。
    function addSpeedSample(key, cur) {
        if (!Number.isFinite(cur) || cur < 0) return;
        const prev = state.speedSamples[key] || 0;
        if (cur > prev) {
            state.speedAccumBytes += (cur - prev);
            state.speedSamples[key] = cur;
        } else if (cur < prev) {
            // 同 key 字节回退（极少见，视作新一段传输）：把当前值整体计入增量
            state.speedAccumBytes += cur;
            state.speedSamples[key] = cur;
        }
    }

    function accumulateDownloadSpeed(aid, data) {
        if (!aid || !data) return;
        const ip = data.imageProgress;
        if (ip) addSpeedSample(aid + ':img:' + (ip.imageNumber != null ? ip.imageNumber : 0), Number(ip.downloadedBytes));
        const up = data.ugoiraProgress;
        if (up) addSpeedSample(aid + ':zip', Number(up.zipDownloadedBytes));
        if (data.coverDownloadedBytes != null) addSpeedSample(aid + ':cover', Number(data.coverDownloadedBytes));
        // 该作品终态：清掉其传输流基线，避免 speedSamples 无限增长。
        if (data.completed || data.failed || data.cancelled) clearSpeedSamplesForItem(aid);
    }

    function clearSpeedSamplesForItem(aid) {
        const prefix = aid + ':';
        Object.keys(state.speedSamples).forEach(k => {
            if (k.indexOf(prefix) === 0) delete state.speedSamples[k];
        });
    }

    function startSpeedMeter() {
        if (state.speedTimer) return;   // 幂等：已在计量则不重置基线
        state.speedSamples = {};
        state.speedAccumBytes = 0;
        state.speedLastAccum = 0;
        state.speedLastTime = speedNowMs();
        renderDownloadSpeed(0);
        state.speedTimer = setInterval(sampleDownloadSpeed, 1000);
    }

    function stopSpeedMeter() {
        if (state.speedTimer) {
            clearInterval(state.speedTimer);
            state.speedTimer = null;
        }
        state.speedSamples = {};
        renderDownloadSpeed(0);
    }

    function sampleDownloadSpeed() {
        const now = speedNowMs();
        const dt = (now - state.speedLastTime) / 1000;
        const delta = state.speedAccumBytes - state.speedLastAccum;
        state.speedLastAccum = state.speedAccumBytes;
        state.speedLastTime = now;
        renderDownloadSpeed(dt > 0 ? Math.max(0, delta / dt) : 0);
    }
