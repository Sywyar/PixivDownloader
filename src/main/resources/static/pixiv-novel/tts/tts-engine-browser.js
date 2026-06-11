/* eslint-disable */
/**
 * 听书「浏览器」引擎子模块：用浏览器内置 Web Speech API（speechSynthesis）逐段朗读。
 * 完全离线、即时、免费；音质与可用语言取决于操作系统已安装的语音包，纯前端运行，不经过后端。
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
            u.onend = () => {
                if (myToken !== state.token) return;
                ctx.speak(i + 1);
            };
            u.onerror = () => {
                if (myToken !== state.token) return;
                ctx.speak(i + 1);
            };
            window.speechSynthesis.cancel();
            window.speechSynthesis.speak(u);
        };
    }

    global.PixivTtsEngineBrowser = { install: install };
})(window);
