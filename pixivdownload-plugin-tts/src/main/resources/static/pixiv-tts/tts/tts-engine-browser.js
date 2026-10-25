/* eslint-disable */
/**
 * 听书「浏览器」引擎子模块：用浏览器内置 Web Speech API（speechSynthesis）逐段朗读。
 * 音质、语言和联网需求由浏览器提供的语音决定，朗读文本不经过本程序后端。
 * 通过 ctx 读写共享 state / els，并按引擎分发回调主控制器状态机（ctx.speak）。install(ctx) 注入。
 */
(function (global) {
    'use strict';

    function install(ctx) {
        const state = ctx.state;
        const els = ctx.els;

        ctx.speakBrowser = function (i, myToken) {
            if (!('speechSynthesis' in window)) {
                state.toast && state.toast(ctx.t('tts.browser-unsupported', '当前浏览器不支持内置语音'), 'error');
                ctx.stop();
                return;
            }
            const seg = state.segments[i];
            const u = new SpeechSynthesisUtterance(seg.text);
            const voiceId = els.voice.value;
            const voice = state.browserVoices.find((v) => (v.voiceURI || v.name) === voiceId);
            if (voice) { u.voice = voice; u.lang = voice.lang; }
            u.rate = ctx.browserRate();
            u.onboundary = (e) => {
                if (myToken !== state.token) return;
                if (seg.text.length > 0 && e.charIndex != null) {
                    ctx.updateBar(e.charIndex / seg.text.length);
                }
            };
            u.onend = u.onerror = () => {
                if (myToken !== state.token) return;
                ctx.speak(i + 1);
            };
            window.speechSynthesis.cancel();
            window.speechSynthesis.speak(u);
        };
    }

    global.PixivTtsEngineBrowser = { install: install };
})(window);
