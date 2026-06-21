/* eslint-disable */
/**
 * 听书「语音 / 语言」子模块：语言探测 + 浏览器 / Edge 声库枚举、挑选与排序。
 *
 * detectLang / sortByLangFirst / chooseVoice 是纯函数（无状态）；docLang / populateVoices /
 * loadBrowserVoices / ensureEdgeVoices 需要主控制器的共享上下文 ctx（state / els / lsGet）。
 * install(ctx) 把这些函数挂到 ctx 上，供主控制器与其它子模块按引擎分发调用。
 */
(function (global) {
    'use strict';

    function detectLang(sample) {
        const s = sample || '';
        if (/[぀-ヿ]/.test(s)) return 'ja';
        if (/[가-힯]/.test(s)) return 'ko';
        if (/[一-鿿]/.test(s)) return 'zh';
        return 'en';
    }

    function sortByLangFirst(list, lang, getLocale) {
        const match = [];
        const rest = [];
        list.forEach((v) => {
            const loc = (getLocale(v) || '').toLowerCase();
            if (loc.startsWith(lang)) match.push(v); else rest.push(v);
        });
        return match.concat(rest);
    }

    /**
     * 选默认语音：若记住的语音与当前小说语言一致则沿用；否则自动选当前语言下的第一个语音
     * （sorted 已把匹配语言的语音排在最前）。这样换一本不同语言的小说会自动切到对应语言的语音。
     */
    function chooseVoice(sorted, savedId, lang, getId, getLocale) {
        const savedVoice = sorted.find((v) => getId(v) === savedId);
        if (savedVoice && (getLocale(savedVoice) || '').toLowerCase().startsWith(lang)) {
            return savedId;
        }
        return sorted[0] ? getId(sorted[0]) : '';
    }

    function install(ctx) {
        const state = ctx.state;
        const els = ctx.els;
        const LS = ctx.LS;

        ctx.detectLang = detectLang;
        ctx.sortByLangFirst = sortByLangFirst;
        ctx.chooseVoice = chooseVoice;

        ctx.docLang = function () {
            // 优先用小说声明的语言（如 zh-cn / ja / en），否则按正文文本启发式判断
            const nl = (state.novelLang || '').toLowerCase();
            if (nl.startsWith('zh')) return 'zh';
            if (nl.startsWith('ja')) return 'ja';
            if (nl.startsWith('ko')) return 'ko';
            if (nl.startsWith('en')) return 'en';
            const sample = state.segments.slice(0, 5).map((s) => s.text).join(' ');
            return detectLang(sample);
        };

        ctx.populateVoices = function () {
            const sel = els.voice;
            sel.innerHTML = '';
            const lang = ctx.docLang();
            if (state.engine === 'edge') {
                const saved = ctx.lsGet(LS.voiceEdge, '');
                const sorted = sortByLangFirst(state.edgeVoices, lang, (v) => v.locale);
                sorted.forEach((v) => {
                    const opt = document.createElement('option');
                    opt.value = v.shortName;
                    opt.textContent = `${v.locale} · ${v.localName || v.shortName}${v.gender ? ' (' + v.gender + ')' : ''}`;
                    sel.appendChild(opt);
                });
                sel.value = chooseVoice(sorted, saved, lang, (v) => v.shortName, (v) => v.locale);
            } else {
                const saved = ctx.lsGet(LS.voiceBrowser, '');
                const sorted = sortByLangFirst(state.browserVoices, lang, (v) => v.lang);
                sorted.forEach((v) => {
                    const opt = document.createElement('option');
                    opt.value = v.voiceURI || v.name;
                    opt.textContent = `${v.lang} · ${v.name}`;
                    sel.appendChild(opt);
                });
                sel.value = chooseVoice(sorted, saved, lang, (v) => v.voiceURI || v.name, (v) => v.lang);
            }
        };

        ctx.loadBrowserVoices = function () {
            if (!('speechSynthesis' in window)) return;
            state.browserVoices = window.speechSynthesis.getVoices() || [];
            if (state.engine === 'browser') ctx.populateVoices();
        };

        ctx.ensureEdgeVoices = function () {
            if (state.edgeVoicesLoaded) return Promise.resolve();
            return fetch('/api/tts/edge/voices', { credentials: 'same-origin' })
                .then((r) => (r.ok ? r.json() : []))
                .then((list) => {
                    state.edgeVoices = Array.isArray(list) ? list : [];
                    state.edgeVoicesLoaded = true;
                })
                .catch(() => {
                    state.edgeVoices = [];
                    state.edgeVoicesLoaded = true;
                });
        };
    }

    global.PixivTtsVoices = { install: install };
})(window);
