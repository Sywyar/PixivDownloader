'use strict';
/*
 * 共享 Vue 挂载 helper（/js/pixiv-vue.js, window.PixivVue）的运行态测试。
 *
 * 无浏览器 / 无 jsdom：用最小 DOM 在 Node 的 vm 沙箱里加载真实的 pixiv-vue.js，验证「为已开出的
 * data-qt-slot 槽位准备稳定 Vue 挂载宿主」这条补强契约的几条不变量。**关键修订**：宿主必须落在
 * `<template data-qt-slot>` 的**原开槽位置**（insertBefore 到模板之前，模板随后移除），使非空 Vue
 * 组件挂载后出现在该 slot 的真实位置——**不再**统一 append 到父容器末尾（旧实现会把 settings-card
 * 等挪到容器末尾、偏离原槽位）。
 *
 * 覆盖下载页全部 8 个 UI 槽位（与 /api/download/extensions 暴露的 target 一一对应）：
 *   quick-actions-bookmarks / quick-actions-mine / kind-option-quick / import-hint /
 *   kind-option-user / kind-option-search / search-filter / settings-card。
 *
 * 守住的不变量：
 *   1) prepareSlotHosts 为每个 <template data-qt-slot> 在**模板原位**备好一个 [data-vue-slot] 宿主
 *      （含无命令式片段的槽位），宿主紧邻在其模板**之前**（insertBefore 语义）。
 *   2) 走完 renderSlots 顺序（先备宿主、再移除模板）后，每个宿主停在该槽位的**真实位置**：
 *      - settings-card 落在 #download-settings-card 与 #save-as-schedule-card 之间；
 *      - import-hint / search-filter / kind-option-user 等保持原位顺序（夹在其两侧原邻居之间）；
 *      - 模板原本在容器末尾的槽位（kind-option-quick / kind-option-search）宿主才在末尾。
 *   3) 幂等：重复 prepareSlotHosts / 重复 anchorFor(target) 只产生一个宿主；模板移除后仍能命中。
 *   4) target 安全：任意含 CSS 元字符的 target 找不到时返回 null、绝不把 target 拼进选择器。
 *   5) 非空 host / mount 后位置不漂移：Vue 组件挂入宿主（宿主非空）后，宿主的左右邻居不变。
 *   6) 失败收敛：Vue 运行时加载失败时 mountUiSlot 解析为 null、不向宿主抛异常。
 *   7) 失败前不吞 fallback：Vue 的 mount 先清空容器再抛错时（mountInto 还原快照），mount / mountOn 收敛为
 *      null 且宿主既有命令式 fallback 子节点**原样保留**（一次失败的 Vue 升级绝不让槽位 / slot 变空白）。
 *
 * 运行： node src/test/js/pixiv-vue.test.js
 */
const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const SRC_PATH = path.join(__dirname, '..', '..', 'main', 'resources', 'static', 'js', 'pixiv-vue.js');
const SOURCE = fs.readFileSync(SRC_PATH, 'utf8');

// 控制沙箱里动态 <script> 加载是成功还是失败（用于模拟 Vue 运行时加载失败）。
let SCRIPT_LOAD_SHOULD_FAIL = false;

// ---- 最小 DOM ----
class Node {
    constructor(tag) {
        this.tag = String(tag).toLowerCase();
        this.attributes = {};
        this.children = [];
        this.parentNode = null;
        // 动态 <script> 用
        this.src = '';
        this.async = false;
        this.onload = null;
        this.onerror = null;
    }
    setAttribute(k, v) { this.attributes[k] = String(v); }
    getAttribute(k) {
        return Object.prototype.hasOwnProperty.call(this.attributes, k) ? this.attributes[k] : null;
    }
    appendChild(child) {
        child.parentNode = this;
        this.children.push(child);
        // 模拟动态脚本加载：下一 tick 触发 onload / onerror（loadScript / ensure 据此 resolve / reject）。
        if (child.tag === 'script') {
            setTimeout(() => {
                if (SCRIPT_LOAD_SHOULD_FAIL && typeof child.onerror === 'function') child.onerror();
                else if (typeof child.onload === 'function') child.onload();
            }, 0);
        }
        return child;
    }
    insertBefore(newNode, ref) {
        newNode.parentNode = this;
        const i = this.children.indexOf(ref);
        if (i < 0) this.children.push(newNode);
        else this.children.splice(i, 0, newNode);
        return newNode;
    }
    remove() {
        if (!this.parentNode) return;
        const i = this.parentNode.children.indexOf(this);
        if (i >= 0) this.parentNode.children.splice(i, 1);
        this.parentNode = null;
    }
    // 仅支持两个**固定字面**存在式选择器：'template[data-qt-slot]' 与 '[data-vue-slot]'。
    // 任何其它选择器都抛错——这正是「target 绝不拼进 querySelector」的守卫：一旦 helper 回归为把 target
    // 插值进选择器，沙箱即抛错、测试失败。
    querySelectorAll(selector) {
        let test;
        if (selector === 'template[data-qt-slot]') {
            test = (n) => n.tag === 'template' && n.getAttribute('data-qt-slot') !== null;
        } else if (selector === '[data-vue-slot]') {
            test = (n) => n.getAttribute('data-vue-slot') !== null;
        } else {
            throw new Error('Unexpected selector (target must never be interpolated into a selector): ' + selector);
        }
        const out = [];
        (function walk(node) {
            node.children.forEach(c => { if (test(c)) out.push(c); walk(c); });
        })(this);
        return out;
    }
    // 直系元素子节点（断言相邻关系用）。
    elementChildren() { return this.children; }
    lastElementChild() { return this.children[this.children.length - 1] || null; }
}

function makeDocument(body) {
    const head = new Node('head');
    return {
        head,
        documentElement: body,
        body,
        createElement: (t) => new Node(t),
        getElementById(id) {
            let found = null;
            (function walk(node) {
                for (const c of node.children) {
                    if (c.getAttribute('id') === id) { found = c; return; }
                    walk(c);
                    if (found) return;
                }
            })(body);
            return found;
        },
        querySelectorAll(sel) { return body.querySelectorAll(sel); }
    };
}

// ---- 邻居 / 宿主访问器（断言原位用，全部直接读 children 数组，绝不经选择器）----
function prevSib(node) {
    const p = node && node.parentNode; if (!p) return null;
    const i = p.children.indexOf(node); return i > 0 ? p.children[i - 1] : null;
}
function nextSib(node) {
    const p = node && node.parentNode; if (!p) return null;
    const i = p.children.indexOf(node); return (i >= 0 && i < p.children.length - 1) ? p.children[i + 1] : null;
}
function hostIn(parent, target) {
    return parent.children.find(c => c.getAttribute('data-vue-slot') === target) || null;
}
function hostsFor(parent, target) {
    return parent.children.filter(c => c.getAttribute('data-vue-slot') === target);
}

// 构造贴近下载页真实结构的 DOM：覆盖全部 8 个 data-qt-slot 槽位，且每个槽位都带可辨识的两侧邻居。
// 槽位在容器中的位置严格镜像 pixiv-batch.html：
//   - quick-actions（flex）夹在按钮之间；import-format-box 夹在两 div 之间；
//   - .kind-switcher#user-kind-switcher 的 kind-option-user 夹在「插画」「约稿」两 label 之间（相邻选择器关键用例）；
//   - .kind-switcher#search-kind-switcher 的 kind-option-search 与 .quick-kind-switcher 的 kind-option-quick 在容器末尾；
//   - search-extra-grid（grid）夹在两 item 之间；settings-card 直接位于页面块流、夹在 下载设置卡 与 存为计划任务卡 之间。
function buildDom() {
    const body = new Node('body');
    const refs = { body };

    function tpl(target) { const t = new Node('template'); t.setAttribute('data-qt-slot', target); return t; }
    function withId(tag, id, attrs) {
        const n = new Node(tag); n.setAttribute('id', id);
        if (attrs) Object.keys(attrs).forEach(k => n.setAttribute(k, attrs[k]));
        return n;
    }
    function appendAll(parent, nodes) { nodes.forEach(n => parent.appendChild(n)); return parent; }

    // 1) .quick-actions（flex）：两个槽位分别夹在按钮之间
    refs.quickActions = new Node('div'); refs.quickActions.setAttribute('class', 'quick-actions');
    refs.qaBmHide = withId('button', 'qa-bm-hide', { 'data-quick': 'my-illust-bookmarks-hide' });
    refs.tplQaBookmarks = tpl('quick-actions-bookmarks');
    refs.qaMine = withId('button', 'qa-mine', { 'data-quick': 'my-illusts' });
    refs.tplQaMine = tpl('quick-actions-mine');
    refs.qaRequest = withId('button', 'qa-request', { 'data-quick': 'my-request-artworks' });
    appendAll(refs.quickActions, [refs.qaBmHide, refs.tplQaBookmarks, refs.qaMine, refs.tplQaMine, refs.qaRequest]);
    body.appendChild(refs.quickActions);

    // 2) .quick-kind-switcher：模板在末尾（illust label 之后）
    refs.quickKind = new Node('span');
    refs.quickKind.setAttribute('class', 'quick-kind-switcher'); refs.quickKind.setAttribute('id', 'quick-inner-kind-switcher');
    refs.quickKindIllust = new Node('label'); refs.quickKindIllust.setAttribute('data-quick-kind', 'illust');
    refs.tplKindQuick = tpl('kind-option-quick');
    appendAll(refs.quickKind, [refs.quickKindIllust, refs.tplKindQuick]);
    body.appendChild(refs.quickKind);

    // 3) .import-format-box：模板夹在两 div 之间
    refs.importBox = new Node('div'); refs.importBox.setAttribute('class', 'import-format-box');
    refs.importExample = withId('div', 'import-example');
    refs.tplImportHint = tpl('import-hint');
    refs.importBareId = withId('div', 'import-bareid');
    appendAll(refs.importBox, [refs.importExample, refs.tplImportHint, refs.importBareId]);
    body.appendChild(refs.importBox);

    // 4) .kind-switcher#user-kind-switcher：模板夹在 illust / request 两 label 之间（相邻选择器关键用例）
    refs.userKind = new Node('div');
    refs.userKind.setAttribute('class', 'kind-switcher'); refs.userKind.setAttribute('id', 'user-kind-switcher');
    refs.userIllust = new Node('label'); refs.userIllust.setAttribute('data-kind', 'illust');
    refs.tplKindUser = tpl('kind-option-user');
    refs.userRequest = new Node('label'); refs.userRequest.setAttribute('data-kind', 'request');
    appendAll(refs.userKind, [refs.userIllust, refs.tplKindUser, refs.userRequest]);
    body.appendChild(refs.userKind);

    // 5) .kind-switcher#search-kind-switcher：模板在末尾（illust label 之后）
    refs.searchKind = new Node('div');
    refs.searchKind.setAttribute('class', 'kind-switcher'); refs.searchKind.setAttribute('id', 'search-kind-switcher');
    refs.searchIllust = new Node('label'); refs.searchIllust.setAttribute('data-kind', 'illust');
    refs.tplKindSearch = tpl('kind-option-search');
    appendAll(refs.searchKind, [refs.searchIllust, refs.tplKindSearch]);
    body.appendChild(refs.searchKind);

    // 6) .search-extra-grid（grid）：模板夹在两 item 之间
    refs.grid = new Node('div'); refs.grid.setAttribute('class', 'search-extra-grid');
    refs.sfBefore = withId('div', 'sf-before', { 'class': 'search-illust-only' });
    refs.tplSearchFilter = tpl('search-filter');
    refs.sfAfter = withId('div', 'sf-after', { 'class': 'search-extra-item' });
    appendAll(refs.grid, [refs.sfBefore, refs.tplSearchFilter, refs.sfAfter]);
    body.appendChild(refs.grid);

    // 7) settings-card：直接位于页面块流（这里用 body），夹在 下载设置卡 与 存为计划任务卡 之间
    refs.downloadSettingsCard = withId('div', 'download-settings-card', { 'class': 'card' });
    refs.tplSettings = tpl('settings-card');
    refs.saveScheduleCard = withId('div', 'save-as-schedule-card', { 'class': 'card' });
    appendAll(body, [refs.downloadSettingsCard, refs.tplSettings, refs.saveScheduleCard]);

    // 8 个槽位的 [target, 容器, 期望左邻, 期望右邻]（renderSlots 移除模板后的最终原位邻居；末尾槽右邻为 null）
    refs.slots = [
        ['quick-actions-bookmarks', refs.quickActions, refs.qaBmHide,            refs.qaMine],
        ['quick-actions-mine',      refs.quickActions, refs.qaMine,              refs.qaRequest],
        ['kind-option-quick',       refs.quickKind,    refs.quickKindIllust,     null],
        ['import-hint',             refs.importBox,    refs.importExample,       refs.importBareId],
        ['kind-option-user',        refs.userKind,     refs.userIllust,          refs.userRequest],
        ['kind-option-search',      refs.searchKind,   refs.searchIllust,        null],
        ['search-filter',           refs.grid,         refs.sfBefore,            refs.sfAfter],
        ['settings-card',           refs.body,         refs.downloadSettingsCard, refs.saveScheduleCard]
    ];
    refs.templatesByTarget = {
        'quick-actions-bookmarks': refs.tplQaBookmarks,
        'quick-actions-mine': refs.tplQaMine,
        'kind-option-quick': refs.tplKindQuick,
        'import-hint': refs.tplImportHint,
        'kind-option-user': refs.tplKindUser,
        'kind-option-search': refs.tplKindSearch,
        'search-filter': refs.tplSearchFilter,
        'settings-card': refs.tplSettings
    };
    return refs;
}

// 在新鲜 vm 沙箱里加载 pixiv-vue.js（隔离 helper 的模块级状态，如 runtimePromise）。
function loadHelper(dom, extras) {
    const document = makeDocument(dom.body);
    const sandbox = Object.assign({
        window: {},
        document,
        console: { warn() {}, log() {}, error() {} },
        setTimeout,
        clearTimeout,
        Promise
    }, extras || {});
    vm.createContext(sandbox);
    vm.runInContext(SOURCE, sandbox);
    return { PixivVue: sandbox.window.PixivVue, document, sandbox };
}

// 模拟 renderSlots 的模板清理：移除全部 <template data-qt-slot>。
function removeTemplates(body) {
    body.querySelectorAll('template[data-qt-slot]').forEach(t => t.remove());
}

let passed = 0;
function ok(label, cond) { assert.ok(cond, label); passed++; }

async function main() {
    // ===== 场景 1：prepareSlotHosts 在模板原位备宿主（紧邻在模板之前，含无片段槽位、含末尾在页面块流的 settings-card）=====
    {
        const dom = buildDom();
        const { PixivVue } = loadHelper(dom);
        ok('PixivVue 暴露 prepareSlotHosts', typeof PixivVue.prepareSlotHosts === 'function');
        const made = PixivVue.prepareSlotHosts();   // 默认 root = document
        ok('为全部 8 个 data-qt-slot 槽位各备 1 个宿主', made.length === 8
            && dom.slots.every(([target, parent]) => hostsFor(parent, target).length === 1));
        // 每个宿主紧邻在其模板**之前**（insertBefore 语义 → 模板原位）。
        let allBeforeTemplate = true;
        for (const [target, parent] of dom.slots) {
            const host = hostIn(parent, target);
            if (nextSib(host) !== dom.templatesByTarget[target]) { allBeforeTemplate = false; break; }
        }
        ok('每个宿主都紧邻插在其模板之前（insertBefore → 模板原位，而非父容器末尾）', allBeforeTemplate);
        // settings-card 宿主必须仍在页面块流的原槽位（紧邻 #download-settings-card 之后），不是被挪到 body 末尾。
        const settingsHost = hostIn(dom.body, 'settings-card');
        ok('settings-card 宿主在原槽位（紧邻 #download-settings-card 之后），不是 body 末尾',
            prevSib(settingsHost) === dom.downloadSettingsCard
            && dom.body.lastElementChild() === dom.saveScheduleCard
            && dom.body.lastElementChild() !== settingsHost);
    }

    // ===== 场景 2：走完 renderSlots 顺序（备宿主 → 移除模板）后，8 个宿主都停在该槽位的真实原位邻居 =====
    {
        const dom = buildDom();
        const { PixivVue } = loadHelper(dom);
        PixivVue.prepareSlotHosts();
        removeTemplates(dom.body);

        for (const [target, parent, expPrev, expNext] of dom.slots) {
            const host = hostIn(parent, target);
            ok('移除模板后 ' + target + ' 宿主仍在', host !== null);
            ok(target + ' 左邻居为原位前节点', prevSib(host) === expPrev);
            ok(target + ' 右邻居为原位后节点' + (expNext ? '' : '（末尾槽位 → null）'), nextSib(host) === expNext);
        }
        // 任务点名校验：settings-card 夹在 download-settings-card 与 save-as-schedule-card 之间。
        const settingsHost = hostIn(dom.body, 'settings-card');
        ok('settings-card 宿主夹在 #download-settings-card 与 #save-as-schedule-card 之间',
            prevSib(settingsHost) === dom.downloadSettingsCard && nextSib(settingsHost) === dom.saveScheduleCard);
        // kind-option-user 夹在两 label 之间、且不是容器末尾（相邻选择器解耦后允许夹在中间）。
        const userHost = hostIn(dom.userKind, 'kind-option-user');
        ok('kind-option-user 宿主夹在「插画」「约稿」两 label 之间，且非容器末尾',
            prevSib(userHost) === dom.userIllust && nextSib(userHost) === dom.userRequest
            && dom.userKind.lastElementChild() === dom.userRequest);
        // 两 label 在 DOM 上已被宿主分隔（不再相邻）——这正是为何 CSS 改用 :not(:first-child) 而非 label + label。
        const ukKids = dom.userKind.children;
        ok('user-kind-switcher 移除模板后子序列为 [label illust, host, label request]',
            ukKids.length === 3 && ukKids[0] === dom.userIllust
            && ukKids[1] === userHost && ukKids[2] === dom.userRequest);
        // 末尾型槽位（模板原本在容器末尾）宿主才在末尾。
        ok('kind-option-quick 宿主在 .quick-kind-switcher 末尾',
            dom.quickKind.lastElementChild() === hostIn(dom.quickKind, 'kind-option-quick'));
        ok('kind-option-search 宿主在 #search-kind-switcher 末尾',
            dom.searchKind.lastElementChild() === hostIn(dom.searchKind, 'kind-option-search'));
    }

    // ===== 场景 3：幂等（重复 prepareSlotHosts / 重复 anchorFor 只产生一个宿主）+ 模板移除后仍命中 =====
    {
        const dom = buildDom();
        const { PixivVue } = loadHelper(dom);
        PixivVue.prepareSlotHosts();
        PixivVue.prepareSlotHosts();
        const a1 = PixivVue.anchorFor('kind-option-user');
        const a2 = PixivVue.anchorFor('kind-option-user');
        ok('重复 prepareSlotHosts 后 kind-option-user 仍只有 1 个宿主',
            hostsFor(dom.userKind, 'kind-option-user').length === 1);
        ok('重复 anchorFor 返回同一宿主节点（不创建重复）', a1 === a2 && a1 !== null);
        ok('anchorFor 命中的就是原位宿主（夹在两 label 之间）', a1 === hostIn(dom.userKind, 'kind-option-user'));
        // 移除模板后 anchorFor 仍命中既有宿主（branch2：[data-vue-slot] / getElementById），且未新增宿主。
        removeTemplates(dom.body);
        const a3 = PixivVue.anchorFor('kind-option-user');
        ok('模板移除后 anchorFor 仍返回既有宿主', a3 === a1);
        ok('模板移除后宿主数仍为 1', hostsFor(dom.userKind, 'kind-option-user').length === 1);
    }

    // ===== 场景 4：target 安全（任意 CSS 元字符 target 返回 null、不抛、不拼进选择器）=====
    {
        const dom = buildDom();
        const { PixivVue } = loadHelper(dom);
        PixivVue.prepareSlotHosts();
        const weird = ['a"]b', '*', ']', 'x\\y', 'has space', 'kind-option-user"]'];
        let allNullNoThrow = true;
        for (const t of weird) {
            let r;
            try { r = PixivVue.anchorFor(t); } catch (e) { allNullNoThrow = false; break; }
            if (r !== null) { allNullNoThrow = false; break; }
        }
        ok('任意含元字符的未知 target → anchorFor 返回 null 且不抛（target 不进选择器）', allNullNoThrow);
        ok('空 target → null', PixivVue.anchorFor('') === null && PixivVue.anchorFor(null) === null);
    }

    // ===== 场景 5：非空 host / mount 后位置不漂移（Vue 就位，挂入宿主后宿主非空、左右邻居不变）=====
    {
        const dom = buildDom();
        const Vue = {
            createApp(opts) {
                return {
                    mount(el) {
                        el.appendChild(new Node('span')); // 使宿主非空（CSS :empty 兜底解除）
                        return { mounted: true, opts };
                    }
                };
            }
        };
        const { PixivVue } = loadHelper(dom, { window: { Vue } });
        PixivVue.prepareSlotHosts();
        removeTemplates(dom.body);

        // 挂载前记录两个代表性宿主的邻居（块流的 settings-card + 夹在 label 间的 kind-option-user）。
        const settingsHost = hostIn(dom.body, 'settings-card');
        const userHost = hostIn(dom.userKind, 'kind-option-user');
        const sPrev = prevSib(settingsHost), sNext = nextSib(settingsHost);
        const uPrev = prevSib(userHost), uNext = nextSib(userHost);

        const r1 = await PixivVue.mountUiSlot(
            { slotId: 'novel.settings-card', target: 'settings-card', moduleUrl: null }, { template: '<div>S</div>' });
        const r2 = await PixivVue.mountUiSlot(
            { slotId: 'novel.kind-option-user', target: 'kind-option-user', moduleUrl: null }, { template: '<label>U</label>' });

        ok('Vue 就位时 mountUiSlot(settings-card) 返回句柄并命中其宿主',
            r1 && r1.el === settingsHost && r1.app && r1.vm);
        ok('Vue 就位时 mountUiSlot(kind-option-user) 返回句柄并命中其宿主',
            r2 && r2.el === userHost && r2.app && r2.vm);
        ok('挂载后 settings-card 宿主非空', settingsHost.children.length > 0);
        ok('挂载后 kind-option-user 宿主非空', userHost.children.length > 0);
        // 关键：挂载只往宿主**内部**追加内容，宿主在父容器里的位置（左右邻居）不漂移。
        ok('挂载后 settings-card 宿主位置不漂移（仍夹在 下载设置卡 与 存为计划任务卡 之间）',
            prevSib(settingsHost) === sPrev && nextSib(settingsHost) === sNext
            && sPrev === dom.downloadSettingsCard && sNext === dom.saveScheduleCard);
        ok('挂载后 kind-option-user 宿主位置不漂移（仍夹在两 label 之间）',
            prevSib(userHost) === uPrev && nextSib(userHost) === uNext
            && uPrev === dom.userIllust && uNext === dom.userRequest);
    }

    // ===== 场景 6：Vue 运行时加载失败 → mountUiSlot 解析为 null、不抛 =====
    {
        SCRIPT_LOAD_SHOULD_FAIL = true;
        try {
            const dom = buildDom();
            const { PixivVue } = loadHelper(dom); // window.Vue 缺失 + 脚本加载失败
            PixivVue.prepareSlotHosts();
            let threw = false, res;
            try {
                res = await PixivVue.mountUiSlot({ slotId: 's', target: 'kind-option-user' }, { template: '<i></i>' });
            } catch (e) { threw = true; }
            ok('Vue 加载失败时 mountUiSlot 不抛异常', threw === false);
            ok('Vue 加载失败时 mountUiSlot 解析为 null（优雅降级）', res === null);
        } finally {
            SCRIPT_LOAD_SHOULD_FAIL = false;
        }
    }

    // ===== 场景 7：mountOn(el) 把组件挂到「调用方已解析好的真实元素」（非 template 锚点，供 nav / page-section 用）=====
    {
        const dom = buildDom();
        const Vue = {
            createApp(opts) {
                return { mount(el) { el.appendChild(new Node('span')); return { mounted: true, opts }; } };
            }
        };
        const { PixivVue, document } = loadHelper(dom, { window: { Vue } });
        ok('PixivVue 暴露 mountOn', typeof PixivVue.mountOn === 'function');
        const host = document.createElement('div');   // 调用方自行（固定选择器）解析出的真实元素
        dom.body.appendChild(host);
        const r = await PixivVue.mountOn(host, { template: '<i>x</i>' });
        ok('mountOn 挂到给定元素并返回句柄（{app,vm,el}）', r && r.el === host && r.app && r.vm && host.children.length > 0);
        const rNull = await PixivVue.mountOn(null, { template: '<i></i>' });
        ok('mountOn(null) 解析为 null（不抛）', rNull === null);
    }

    // ===== 场景 8：mountOn 在 Vue 运行时加载失败时解析为 null、不抛（优雅降级）=====
    {
        SCRIPT_LOAD_SHOULD_FAIL = true;
        try {
            const dom = buildDom();
            const { PixivVue, document } = loadHelper(dom); // window.Vue 缺失 + 脚本加载失败
            const host = document.createElement('div'); dom.body.appendChild(host);
            let threw = false, res;
            try { res = await PixivVue.mountOn(host, { template: '<i></i>' }); } catch (e) { threw = true; }
            ok('Vue 加载失败时 mountOn 不抛异常', threw === false);
            ok('Vue 加载失败时 mountOn 解析为 null（优雅降级）', res === null);
        } finally {
            SCRIPT_LOAD_SHOULD_FAIL = false;
        }
    }

    // ===== 场景 9：mountInto 在 Vue「先清空容器再抛错」时还原命令式 fallback（mountOn + mount 两入口都不丢内容）=====
    {
        const dom = buildDom();
        // 伪 Vue：createApp().mount(el) 先清空 el（模拟 runtime-dom mount 的 container.innerHTML=''），再抛错。
        const Vue = {
            createApp() {
                return {
                    mount(el) {
                        (el.children || []).slice().forEach(c => c.remove()); // 先清空（detach 现有 fallback 子节点）
                        throw new Error('mount blew up after clearing container');
                    },
                    unmount() {}
                };
            }
        };
        const { PixivVue, document } = loadHelper(dom, { window: { Vue } });

        // (a) mountOn 入口：调用方解析好的元素已有命令式 fallback 子节点（如导航 slot 的首屏命令式链接）。
        const host = document.createElement('div');
        const fb1 = document.createElement('span'); fb1.setAttribute('data-fb', 'on');
        host.appendChild(fb1);
        dom.body.appendChild(host);
        const r1 = await PixivVue.mountOn(host, { template: '<i>x</i>' });
        ok('场景9: mountOn 挂载「先清空再抛错」收敛为 null（不抛）', r1 === null);
        ok('场景9: mountOn 失败后命令式 fallback 仍在（未被吞掉）',
            host.children.length === 1 && host.children[0] === fb1);

        // (b) mount(target) 入口：模板锚点宿主预置命令式 fallback 子节点。
        PixivVue.prepareSlotHosts();
        const userHost = PixivVue.anchorFor('kind-option-user');
        const fb2 = document.createElement('label'); fb2.setAttribute('data-fb', 'target');
        userHost.appendChild(fb2);
        const r2 = await PixivVue.mount('kind-option-user', { template: '<label>U</label>' });
        ok('场景9: mount(target) 挂载「先清空再抛错」收敛为 null', r2 === null);
        ok('场景9: mount(target) 失败后宿主命令式 fallback 仍在',
            userHost.children.length === 1 && userHost.children[0] === fb2);
    }

    console.log(`\npixiv-vue.test.js: ${passed} assertions passed (9 scenarios, 8 slots) ✓`);
}

main().catch(err => {
    console.error('TEST FAILED:', err && err.message ? err.message : err);
    process.exit(1);
});
