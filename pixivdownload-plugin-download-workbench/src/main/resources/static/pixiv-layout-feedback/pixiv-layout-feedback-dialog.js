/* eslint-disable */
/** 布局调查对话框渲染、交互与提交。 */
(function (global) {
    'use strict';

    var modules = global.PixivLayoutFeedbackModules
        || (global.PixivLayoutFeedbackModules = {});
    modules.dialog = Object.freeze({
        install: function (ctx) {
            var runtime = ctx.runtime;

    /* ============================================================
       i18n 与文案
    ============================================================ */

    function t(key, fallback) {
        if (runtime.i18nClient && typeof runtime.i18nClient.t === 'function') {
            try {
                return runtime.i18nClient.t(ctx.I18N_NS + ':' + key, fallback);
            } catch (_) {
                return fallback;
            }
        }
        return fallback;
    }

    function applyDialogTranslations() {
        if (!runtime.dialogElements || !runtime.dialogElements.root) return;
        if (runtime.i18nClient && typeof runtime.i18nClient.apply === 'function') {
            try {
                runtime.i18nClient.apply(runtime.dialogElements.root);
            } catch (_) {
                // 翻译失败不破坏弹窗
            }
        }
        updateCounterText();
        updateSubmitLabel();
        if (runtime.dialogElements.error && runtime.dialogElements.error.hidden === false) {
            updateErrorText();
        }
    }

    /* ============================================================
       弹窗交互
    ============================================================ */

    function isElementVisible(element) {
        if (!element) return false;
        try {
            return !element.hidden;
        } catch (_) {
            return true;
        }
    }

    function hasBlockingOverlay() {
        try {
            var body = global.document && global.document.body;
            if (body && body.classList && body.classList.contains('pixiv-feedback-open')) return true;
            if (global.document.querySelector('.pt-root') && isElementVisible(global.document.querySelector('.pt-root'))) return true;
            if (global.document.querySelector('.po-root')) return true;
            var modalRoot = global.document.getElementById('abModalRoot');
            if (modalRoot && !modalRoot.hidden && modalRoot.children && modalRoot.children.length) return true;
            var drawerRoot = global.document.getElementById('abDrawerRoot');
            if (drawerRoot && !drawerRoot.hidden && drawerRoot.children && drawerRoot.children.length) return true;
        } catch (_) {
            // 任一探测失败按「无阻塞」继续，调查异常不得影响页面
        }
        return false;
    }

    function buildElement(tagName, className) {
        var element = global.document.createElement(tagName);
        if (className) element.className = className;
        return element;
    }

    function openDialog(survey, choiceQuestion, suggestionQuestion) {
        if (runtime.dialogOpen || !global.document || !global.document.body) return false;
        var generation = ctx.currentRuntimeGeneration();
        runtime.dialogOpen = true;
        runtime.dialogSurveyId = survey.id;
        runtime.dialogChoiceQuestion = choiceQuestion;
        runtime.dialogSuggestionQuestion = suggestionQuestion;
        runtime.layoutSnapshot = ctx.currentLayoutId();
        runtime.selectedChoice = null;
        runtime.shownSent = false;
        runtime.submitting = false;

        try {
            runtime.dialogFocusBefore = global.document.activeElement;
            var backdrop = buildElement('div', 'plf-backdrop');
            var dialog = buildElement('section', 'plf-dialog');
            dialog.setAttribute('role', 'dialog');
            dialog.setAttribute('aria-modal', 'true');
            dialog.setAttribute('aria-labelledby', 'plf-title');
            dialog.setAttribute('aria-describedby', 'plf-description');
            dialog.tabIndex = -1;

            var closeButton = buildElement('button', 'plf-close');
            closeButton.type = 'button';
            closeButton.setAttribute('data-plf-action', 'close');
            closeButton.setAttribute('data-i18n-aria-label', ctx.I18N_NS + ':close');
            closeButton.setAttribute('aria-label', t('close', '关闭'));
            closeButton.textContent = '\u00d7';

            var title = buildElement('h2', 'plf-title');
            title.id = 'plf-title';
            title.setAttribute('data-i18n', ctx.I18N_NS + ':title');
            title.textContent = t('title', '帮助我们选择默认布局');

            var description = buildElement('p', 'plf-description');
            description.id = 'plf-description';
            description.setAttribute('data-i18n', ctx.I18N_NS + ':description');
            description.textContent = t('description', '新版本提供了三种下载工作台布局。请选择你更愿意长期使用的一种。');

            var group = buildElement('div', 'plf-cards');
            group.setAttribute('role', 'radiogroup');
            group.setAttribute('aria-label', t('choices-group', '选择你更愿意长期使用的布局'));
            group.setAttribute('data-i18n-aria-label', ctx.I18N_NS + ':choices-group');

            var currentId = ctx.currentLayoutId();
            ctx.LAYOUT_IDS.forEach(function (layoutId, index) {
                var card = buildElement('label', 'plf-card');
                card.setAttribute('data-plf-layout', layoutId);
                var input = buildElement('input');
                input.type = 'radio';
                input.name = 'plf-layout-choice';
                input.value = layoutId;
                input.className = 'plf-radio';
                if (index === 0) input.setAttribute('data-plf-first-radio', 'true');
                var nameSpan = buildElement('span', 'plf-card-name');
                var descSpan = buildElement('span', 'plf-card-desc');
                var currentBadge = buildElement('span', 'plf-current-badge');
                currentBadge.setAttribute('data-i18n', ctx.I18N_NS + ':current-layout');
                currentBadge.textContent = t('current-layout', '当前布局');
                currentBadge.hidden = currentId !== layoutId;
                nameSpan.setAttribute('data-i18n', ctx.I18N_NS + ':option-' + layoutOptionKey(layoutId));
                nameSpan.textContent = t('option-' + layoutOptionKey(layoutId), optionFallbackName(layoutId));
                descSpan.setAttribute('data-i18n', ctx.I18N_NS + ':option-' + layoutOptionKey(layoutId) + '-desc');
                descSpan.textContent = t('option-' + layoutOptionKey(layoutId) + '-desc', optionFallbackDesc(layoutId));
                card.appendChild(input);
                card.appendChild(nameSpan);
                card.appendChild(descSpan);
                card.appendChild(currentBadge);
                input.addEventListener('change', onChoiceChange);
                group.appendChild(card);
            });

            var suggestionWrap = buildElement('div', 'plf-suggestion');
            var suggestionLabel = buildElement('label', 'plf-suggestion-label');
            suggestionLabel.setAttribute('for', 'plf-suggestion-input');
            suggestionLabel.setAttribute('data-i18n', ctx.I18N_NS + ':suggestion-label');
            suggestionLabel.textContent = t('suggestion-label', '优化建议（可选）');
            var textarea = buildElement('textarea', 'plf-suggestion-input');
            textarea.id = 'plf-suggestion-input';
            textarea.rows = 3;
            // 不设原生 maxlength：它按 UTF-16 code unit 计数，与 1000 个
            // Unicode code point 的限制语义冲突；截断统一在 input 事件中按
            // code point 完成（onSuggestionInput）。
            textarea.setAttribute('data-i18n-placeholder', ctx.I18N_NS + ':suggestion-placeholder');
            textarea.placeholder = t('suggestion-placeholder', '例如：信息密度、导航位置、按钮大小、队列展示或移动端体验……');
            var counter = buildElement('span', 'plf-suggestion-counter');
            counter.id = 'plf-suggestion-counter';
            counter.setAttribute('data-plf-counter', 'true');
            counter.setAttribute('aria-live', 'polite');
            textarea.setAttribute('aria-describedby', 'plf-suggestion-counter');
            textarea.addEventListener('input', onSuggestionInput);
            if (!suggestionQuestion) {
                suggestionWrap.hidden = true;
                suggestionWrap.setAttribute('data-plf-no-suggestion', 'true');
            }
            suggestionWrap.appendChild(suggestionLabel);
            suggestionWrap.appendChild(textarea);
            suggestionWrap.appendChild(counter);

            var privacy = buildElement('p', 'plf-privacy');
            privacy.setAttribute('data-i18n', ctx.I18N_NS + ':privacy');
            privacy.textContent = t('privacy', '本问卷使用固定版本的 PostHog SDK，并向固定的事件接收接口发送问卷回答、调查标识、调查专用匿名标识、用于投递去重的稳定事件标识、应用版本、当前布局、调查结构版本、事件时间，以及传输所需的事件名和公开项目令牌。不发送原始安装身份、账号、Cookie、作品或本地路径。');

            var error = buildElement('p', 'plf-error');
            error.setAttribute('role', 'alert');
            error.setAttribute('aria-live', 'polite');
            error.hidden = true;
            error.setAttribute('data-plf-error', 'true');

            var actions = buildElement('div', 'plf-actions');
            var snoozeButton = buildElement('button', 'plf-button plf-button--secondary');
            snoozeButton.type = 'button';
            snoozeButton.setAttribute('data-plf-action', 'snooze');
            snoozeButton.setAttribute('data-i18n', ctx.I18N_NS + ':snooze');
            snoozeButton.textContent = t('snooze', '稍后再说');
            var neverButton = buildElement('button', 'plf-button plf-button--ghost');
            neverButton.type = 'button';
            neverButton.setAttribute('data-plf-action', 'never');
            neverButton.setAttribute('data-i18n', ctx.I18N_NS + ':never');
            neverButton.textContent = t('never', '不再询问');
            var submitButton = buildElement('button', 'plf-button plf-button--primary');
            submitButton.type = 'button';
            submitButton.setAttribute('data-plf-action', 'submit');
            submitButton.setAttribute('data-i18n', ctx.I18N_NS + ':submit');
            submitButton.textContent = t('submit', '提交反馈');
            submitButton.disabled = true;
            actions.appendChild(snoozeButton);
            actions.appendChild(neverButton);
            actions.appendChild(submitButton);

            var footer = buildElement('div', 'plf-footer');
            var githubLink = buildElement('a', 'plf-github');
            githubLink.href = 'https://github.com/Sywyar/PixivDownloader';
            githubLink.target = '_blank';
            githubLink.rel = 'noopener noreferrer';
            githubLink.setAttribute('data-i18n-title', ctx.I18N_NS + ':github-repo');
            githubLink.setAttribute('data-i18n-aria-label', ctx.I18N_NS + ':github-repo');
            githubLink.title = t('github-repo', '跳转到代码仓库');
            githubLink.setAttribute('aria-label', t('github-repo', '跳转到代码仓库'));
            var svgNs = 'http://www.w3.org/2000/svg';
            var githubSvg = global.document.createElementNS(svgNs, 'svg');
            githubSvg.setAttribute('viewBox', '0 0 16 16');
            githubSvg.setAttribute('width', '18');
            githubSvg.setAttribute('height', '18');
            githubSvg.setAttribute('fill', 'currentColor');
            githubSvg.setAttribute('aria-hidden', 'true');
            var githubPath = global.document.createElementNS(svgNs, 'path');
            githubPath.setAttribute('d', 'M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27s1.36.09 2 .27c1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8z');
            githubSvg.appendChild(githubPath);
            githubLink.appendChild(githubSvg);
            footer.appendChild(githubLink);
            footer.appendChild(actions);

            dialog.appendChild(closeButton);
            dialog.appendChild(title);
            dialog.appendChild(description);
            dialog.appendChild(group);
            dialog.appendChild(suggestionWrap);
            dialog.appendChild(privacy);
            dialog.appendChild(error);
            dialog.appendChild(footer);
            backdrop.appendChild(dialog);
            global.document.body.appendChild(backdrop);

            runtime.dialogElements = {
                root: backdrop,
                dialog: dialog,
                backdrop: backdrop,
                closeButton: closeButton,
                group: group,
                textarea: textarea,
                counter: counter,
                error: error,
                submitButton: submitButton,
                snoozeButton: snoozeButton,
                neverButton: neverButton,
                radios: Array.prototype.slice.call(group.querySelectorAll('input[type="radio"]'))
            };

            dialog.addEventListener('click', onDialogActionClick);
            backdrop.addEventListener('mousedown', onBackdropMouseDown);
            runtime.dialogKeydownHandler = onDialogKeyDown;
            global.document.addEventListener('keydown', runtime.dialogKeydownHandler, true);
            updateCounterText();
            updateSubmitLabel();
            applyDialogTranslations();
            ctx.setTimeoutSafe(function () {
                if (!ctx.isRuntimeGenerationActive(generation)) return;
                try {
                    dialog.focus();
                } catch (_) {
                    // 焦点移动失败不阻断弹窗
                }
            }, 0);
            return true;
        } catch (_) {
            runtime.dialogOpen = false;
            runtime.dialogElements = null;
            throw _;
        }
    }

    function layoutOptionKey(layoutId) {
        if (layoutId === 'pixiv-batch-landscape') return 'landscape';
        if (layoutId === 'pixiv-batch-portrait') return 'portrait';
        return 'alt';
    }

    function optionFallbackName(layoutId) {
        if (layoutId === 'pixiv-batch-landscape') return '横屏工作台';
        if (layoutId === 'pixiv-batch-portrait') return '竖屏经典布局';
        return '新版工作台';
    }

    function optionFallbackDesc(layoutId) {
        if (layoutId === 'pixiv-batch-landscape') return '适合宽屏显示器，模式、设置和下载队列同时展示。';
        if (layoutId === 'pixiv-batch-portrait') return '内容按纵向顺序排列，更接近原有使用方式。';
        return '采用独立的新工作台界面和下载面板。';
    }

    function onChoiceChange(event) {
        var input = event && event.target;
        if (!input || !input.value) return;
        runtime.selectedChoice = input.value;
        if (runtime.dialogElements && runtime.dialogElements.group) {
            runtime.dialogElements.group.querySelectorAll('input[type="radio"]').forEach(function (radio) {
                var card = radio.parentNode;
                if (card && card.classList) {
                    card.classList.toggle('is-checked', radio.checked);
                }
            });
        }
        if (runtime.dialogElements) {
            runtime.dialogElements.submitButton.disabled = false;
            hideError();
        }
    }

    function onSuggestionInput() {
        if (runtime.dialogElements && runtime.dialogElements.textarea) {
            var value = String(runtime.dialogElements.textarea.value);
            var points = Array.from(value);
            if (points.length > ctx.SUGGESTION_MAX_CODE_POINTS) {
                // 按 code point 截断（不会切断代理对 / 组合字符）。
                runtime.dialogElements.textarea.value = points.slice(0, ctx.SUGGESTION_MAX_CODE_POINTS).join('');
            }
        }
        updateCounterText();
        hideError();
    }

    function updateCounterText() {
        if (!runtime.dialogElements || !runtime.dialogElements.counter || !runtime.dialogElements.textarea) return;
        var count = ctx.codePointLength(runtime.dialogElements.textarea.value);
        var template = t('suggestion-counter', '{count} / {max}');
        var isFallback = template === '{count} / {max}';
        var text = template;
        if (isFallback) {
            text = count + ' / ' + ctx.SUGGESTION_MAX_CODE_POINTS;
        } else {
            text = template
                .replace('{count}', String(count))
                .replace('{max}', String(ctx.SUGGESTION_MAX_CODE_POINTS));
        }
        runtime.dialogElements.counter.textContent = text;
    }

    function updateSubmitLabel() {
        if (!runtime.dialogElements || !runtime.dialogElements.submitButton) return;
        runtime.dialogElements.submitButton.textContent = runtime.submitting
            ? t('submitting', '提交中…')
            : t('submit', '提交反馈');
    }

    function showError(key) {
        lastErrorKey = key;
        if (!runtime.dialogElements || !runtime.dialogElements.error) return;
        runtime.dialogElements.error.textContent = t(key, errorFallback(key));
        runtime.dialogElements.error.hidden = false;
    }

    function hideError() {
        if (!runtime.dialogElements || !runtime.dialogElements.error) return;
        runtime.dialogElements.error.hidden = true;
        runtime.dialogElements.error.textContent = '';
    }

    function updateErrorText() {
        if (!runtime.dialogElements || !runtime.dialogElements.error) return;
        runtime.dialogElements.error.textContent = t(currentErrorKey(), errorFallback(currentErrorKey()));
    }

    var lastErrorKey = null;
    function currentErrorKey() {
        return lastErrorKey || 'error-submit-failed';
    }

    function errorFallback(key) {
        if (key === 'error-required') return '请先选择一种布局。';
        if (key === 'error-suggestion-too-long') return '建议内容过长，请精简到 1000 字以内。';
        if (key === 'survey-unavailable') return '调查暂不可用。';
        if (key === 'error-state-verification') return '调查状态暂不可用，请稍后重试。';
        return '提交失败，请重试。';
    }

    function setSubmittingState(active) {
        if (!runtime.dialogElements) return;
        runtime.dialogElements.dialog.setAttribute('aria-busy', active ? 'true' : 'false');
        runtime.dialogElements.radios.forEach(function (input) { input.disabled = active; });
        if (runtime.dialogElements.textarea) runtime.dialogElements.textarea.disabled = active;
        runtime.dialogElements.submitButton.disabled = active || !runtime.selectedChoice;
        runtime.dialogElements.snoozeButton.disabled = active;
        runtime.dialogElements.neverButton.disabled = active;
        runtime.dialogElements.closeButton.disabled = active;
        updateSubmitLabel();
    }

    function onDialogActionClick(event) {
        if (runtime.submitting) return;
        var button = event && event.target && event.target.closest
            ? event.target.closest('button[data-plf-action]')
            : null;
        if (!button) return;
        var action = button.getAttribute('data-plf-action');
        if (action === 'submit') submitFeedback();
        else if (action === 'snooze') snooze();
        else if (action === 'never') dismissNever();
        else if (action === 'close') snooze();
    }

    function onBackdropMouseDown(event) {
        if (runtime.submitting) return;
        if (event.target === runtime.dialogElements.backdrop) snooze();
    }

    function onDialogKeyDown(event) {
        if (!runtime.dialogOpen) return;
        if (event.key === 'Escape') {
            event.preventDefault();
            if (typeof event.stopImmediatePropagation === 'function') event.stopImmediatePropagation();
            else if (typeof event.stopPropagation === 'function') event.stopPropagation();
            if (!runtime.submitting) snooze();
            return;
        }
        if (event.key !== 'Tab' || !runtime.dialogElements) return;
        var focusable = runtime.dialogElements.dialog.querySelectorAll(
            'button:not([disabled]), input:not([disabled]), textarea:not([disabled])'
        );
        if (!focusable.length) {
            event.preventDefault();
            runtime.dialogElements.dialog.focus();
            return;
        }
        var first = focusable[0];
        var last = focusable[focusable.length - 1];
        if (event.shiftKey && global.document.activeElement === first) {
            event.preventDefault();
            last.focus();
        } else if (!event.shiftKey && global.document.activeElement === last) {
            event.preventDefault();
            first.focus();
        }
    }

    function closeDialog(restoreFocus) {
        if (!runtime.dialogOpen) return;
        runtime.dialogOpen = false;
        var previousFocus = runtime.dialogFocusBefore;
        try {
            if (runtime.dialogKeydownHandler) {
                global.document.removeEventListener('keydown', runtime.dialogKeydownHandler, true);
            }
            if (runtime.dialogElements && runtime.dialogElements.root && runtime.dialogElements.root.parentNode) {
                runtime.dialogElements.root.parentNode.removeChild(runtime.dialogElements.root);
            }
        } catch (_) {
            // DOM 清理尽力而为
        }
        runtime.dialogElements = null;
        runtime.dialogKeydownHandler = null;
        runtime.dialogFocusBefore = null;
        if (restoreFocus && previousFocus && typeof previousFocus.focus === 'function') {
            try {
                if (global.document.contains && global.document.contains(previousFocus)) {
                    previousFocus.focus();
                }
            } catch (_) {
                // 焦点恢复失败不阻断
            }
        }
    }

    function snooze() {
        if (runtime.submitting || !runtime.dialogOpen) return;
        // 本地 snooze 使用安全加法（防 Number 非安全溢出）；snoozedUntil 属于客户端
        // 时钟域，只用于本地 fallback（服务端恢复前），绝不上传服务端。
        ctx.writeState('snoozed', ctx.safeClientTimeAdd(ctx.clientWallNow(), ctx.SNOOZE_MS));
        closeDialog(true);
    }

    function dismissNever() {
        if (runtime.submitting || !runtime.dialogOpen) return;
        var result = ctx.writeState('never');
        closeDialog(true);
        // 生命周期事件必须与最终有效状态一致：只有 writeState 的转移被接受且
        // effectiveState 确实为 never 时才发送 dismissed（transitionAccepted 守卫：
        // 重复 never / 已有更强 submitted / 更长 snooze 时不得重复发送 dismissed）。
        // 重复 never 操作不是重试 dismissed 的隐式机制。
        if (result && result.transitionAccepted === true
                && result.effectiveState && result.effectiveState.status === 'never') {
            ctx.sendDismissedBestEffort().catch(function () {});
        }
    }

    function trimSuggestion() {
        if (!runtime.dialogElements || !runtime.dialogElements.textarea) return '';
        return String(runtime.dialogElements.textarea.value).trim();
    }

    function submitFeedback() {
        if (runtime.submitting || !runtime.dialogOpen) return;
        if (!runtime.selectedChoice || !runtime.dialogChoiceQuestion) {
            lastErrorKey = 'error-required';
            showError('error-required');
            return;
        }
        var suggestion = trimSuggestion();
        if (ctx.codePointLength(suggestion) > ctx.SUGGESTION_MAX_CODE_POINTS) {
            lastErrorKey = 'error-suggestion-too-long';
            showError('error-suggestion-too-long');
            return;
        }
        runtime.submitting = true;
        setSubmittingState(true);
        hideError();

        // 发送前执行一次不走缓存的持久化状态读取：另一标签页可能已经提交，
        // 此时取消本次提交并关闭弹窗，不发送
        // 第二条 survey sent（弱去重，无法消除完全同时点击的竞态；
        // never / snoozed 只控制主动展示，不覆盖当前表单里的主动提交）。
        var freshState = ctx.readStateFresh();
        if (freshState && freshState.surveyId === runtime.dialogSurveyId
                && ctx.isSubmittedDecision(freshState)) {
            runtime.submitting = false;
            closeDialog(true);
            showHandledElsewhereNote();
            return;
        }

        var choiceId = runtime.dialogChoiceQuestion.id;
        var suggestionQuestion = runtime.dialogSuggestionQuestion;
        var suggestionId = suggestionQuestion ? suggestionQuestion.id : null;
        var surveyId = runtime.dialogSurveyId;
        var snapshot = runtime.layoutSnapshot;
        var generation = ctx.currentRuntimeGeneration();

        function sendCapture() {
            return ctx.surveyEventProperties(generation, {}).then(function (base) {
                if (!base) return;
                var props = {
                    '$survey_id': surveyId,
                    app_version: base.app_version,
                    current_layout: snapshot,
                    survey_schema_version: ctx.SURVEY_SCHEMA_VERSION
                };
                props['$survey_response_' + choiceId] = runtime.selectedChoice;
                if (suggestion && suggestionId) {
                    props['$survey_response_' + suggestionId] = suggestion;
                }
                return ctx.sendSurveyEvent(generation, 'survey sent', props);
            }).then(function () {
                // 事件接收端已返回 2xx，但若 generation 在结果
                // 处理前已失效（destroy），旧回调不得再写状态 / 显示 Toast / 动 DOM。
                if (!ctx.isRuntimeGenerationActive(generation)) return;
                runtime.submitting = false;
                // PostHog 已确认：本地同步写 submitted（含服务端 submitted 命令；
                // 服务端保存失败不撤销已接受的提交，保留本地回退，不显示
                // “PostHog 提交失败”，只记录不含用户数据的 warning）。
                ctx.writeState('submitted');
                closeDialog(true);
                showSuccessToast();
            });
        }

        // serverBacked：提交前强制 GET 最新服务端状态（跨设备 preflight）。
        // refresh 结果按明确契约分类：
        // - FRESH：重新读取当前 effective state；已 submitted → 取消本次 capture、关闭
        //   弹窗、显示「已在其他页面处理」、不发送 dismissed；never / snoozed 仍允许提交；
        // - UNAVAILABLE：按明确产品策略 fail-open（网络暂时不可用时允许提交），但
        //   capture 前重新读取一次本地 effective / localStorage 状态，本地已 submitted
        //   时仍然阻止；记录不含 token / Survey ID / 身份 / 用户输入的
        //   安全 warning；不向用户显示网络错误；
        // - INVALID：协议 / 身份 / 快照一致性异常，fail-closed——不发送 survey sent /
        //   dismissed、不关闭弹窗、保留布局选择 / 建议 / 字数 / 焦点、恢复控件、
        //   显示可重试错误 error-state-verification（不显示技术原因 / 身份值 / token）；
        // - CANCELLED：generation 已失效直接安全结束；generation 仍活动但 operation
        //   被取代时不继续 capture，恢复控件并显示同一可重试错误。
        // 这是弱去重：两台设备完全同时通过 preflight 并 capture 仍可能产生重复事件，
        // 不引入账号绑定、IP 或浏览器指纹。
        var preflight = Promise.resolve({status: ctx.REFRESH_FRESH, viewResult: ctx.VIEW_SAME});
        if (runtime.serverBacked) {
            preflight = ctx.refreshServerContext(generation);
        }
        preflight.then(function (result) {
            if (!ctx.isRuntimeGenerationActive(generation)) return;
            result = result || {status: ctx.REFRESH_UNAVAILABLE, reason: 'unknown'};
            if (result.status === ctx.REFRESH_CANCELLED) {
                // 当前 generation 仍活动但 operation 被取代：不继续 capture，
                // 恢复控件并显示同一可重试错误（不得静默继续）。
                runtime.submitting = false;
                setSubmittingState(false);
                lastErrorKey = 'error-state-verification';
                showError('error-state-verification');
                return;
            }
            if (result.status === ctx.REFRESH_INVALID) {
                // 协议 / 身份 / 快照异常：fail-closed。
                runtime.submitting = false;
                setSubmittingState(false);
                lastErrorKey = 'error-state-verification';
                showError('error-state-verification');
                return;
            }
            if (result.status === ctx.REFRESH_UNAVAILABLE) {
                // 暂时不可用：按明确产品策略 fail-open，但提交前重新读取一次本地
                // effective / localStorage 状态；本地已 submitted 时仍然阻止。
                if (ctx.hasSubmittedLocalDecision()) {
                    runtime.submitting = false;
                    closeDialog(true);
                    showHandledElsewhereNote();
                    return;
                }
                ctx.warn('layout survey: preflight state refresh unavailable; proceeding with local decision only');
                return sendCapture();
            }
            // REFRESH_FRESH：只以当前 effective submitted 去重（STALE 同样基于当前
            // 更高 revision 的权威状态，不会因迟到低 revision 响应放宽或收紧门禁）。
            var state = ctx.readState();
            if (ctx.isSubmittedDecision(state)) {
                runtime.submitting = false;
                closeDialog(true);
                showHandledElsewhereNote();
                return;
            }
            return sendCapture();
        }).catch(function () {
            if (!ctx.isRuntimeGenerationActive(generation)) return;
            runtime.submitting = false;
            setSubmittingState(false);
            lastErrorKey = 'error-submit-failed';
            showError('error-submit-failed');
        });
    }

    function showSuccessToast() {
        if (global.PixivFeedback && typeof global.PixivFeedback.toast === 'function') {
            try {
                global.PixivFeedback.toast({kind: 'success', message: t('submit-success', '感谢反馈，已提交你的布局偏好。')});
            } catch (_) {
                // toast 失败不影响提交结果
            }
        }
    }

    function showHandledElsewhereNote() {
        // 非阻塞提示：同一调查已在另一标签页处理。不重复发送 dismissed、
        // 不改写其它标签页的状态、不显示提交失败。
        if (global.PixivFeedback && typeof global.PixivFeedback.toast === 'function') {
            try {
                global.PixivFeedback.toast({kind: 'info', message: t('handled-elsewhere', '该布局调查已在其他标签页处理。')});
            } catch (_) {
                // 提示失败不影响已关闭的弹窗
            }
        }
    }

            Object.assign(ctx, {
                applyDialogTranslations: applyDialogTranslations,
                buildElement: buildElement,
                closeDialog: closeDialog,
                currentErrorKey: currentErrorKey,
                dismissNever: dismissNever,
                errorFallback: errorFallback,
                hasBlockingOverlay: hasBlockingOverlay,
                hideError: hideError,
                isElementVisible: isElementVisible,
                lastErrorKey: lastErrorKey,
                layoutOptionKey: layoutOptionKey,
                onBackdropMouseDown: onBackdropMouseDown,
                onChoiceChange: onChoiceChange,
                onDialogActionClick: onDialogActionClick,
                onDialogKeyDown: onDialogKeyDown,
                onSuggestionInput: onSuggestionInput,
                openDialog: openDialog,
                optionFallbackDesc: optionFallbackDesc,
                optionFallbackName: optionFallbackName,
                setSubmittingState: setSubmittingState,
                showError: showError,
                showHandledElsewhereNote: showHandledElsewhereNote,
                showSuccessToast: showSuccessToast,
                snooze: snooze,
                submitFeedback: submitFeedback,
                t: t,
                trimSuggestion: trimSuggestion,
                updateCounterText: updateCounterText,
                updateErrorText: updateErrorText,
                updateSubmitLabel: updateSubmitLabel
            });
        }
    });
})(window);
