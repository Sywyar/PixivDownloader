// 界面与交互核对：空队列 / 队首 / 滚到底三种状态的截图与几何、聚焦、Tab、页内查找、滚动高度与提示间距。
// 用法：由 visual-pair.mjs 调用：node visual.mjs <端口> <输出目录>
import fs from 'node:fs';
import path from 'node:path';
import {chromium, browserLaunch} from './lib/platform.mjs';
const [, , portArg, outDir, itemsArg] = process.argv;
const BASE = `http://127.0.0.1:${portArg}`;
fs.mkdirSync(outDir, {recursive: true});
// 队列长度由 visual-pair.mjs 传入；聚焦与查找的目标行按长度比例取在列表靠后的位置。
const N = Number(itemsArg || 3000);
const FOCUS_ROW = Math.floor(N * 0.75);
const FIND_ROW = Math.floor(N * 0.9);
const ID_BASE = 140000000;
const ids = Array.from({length: N}, (_, i) => String(ID_BASE + i)).join('\n');
const results = {};
// 浏览器语言与页面源语言一致，取自模拟后端读取的 locales.json。
const {locale} = await (await fetch(BASE + '/__info')).json();
const browser = await chromium.launch(browserLaunch({args: [`--lang=${locale}`]}));
const variants = [
    {name: 'wide', w: 1440, h: 820},
    {name: 'narrow', w: 600, h: 800},
    {name: 'short', w: 1440, h: 520},
    {name: 'dark', w: 1440, h: 820, dark: true},
    {name: 'bigtext', w: 1440, h: 820, font: '200%'}
];

const GEOMETRY = `(function (listSel, rowSel) {
    const list = document.querySelector(listSel);
    const rows = Array.from(document.querySelectorAll(rowSel));
    const visible = rows.filter(r => { const b = r.getBoundingClientRect(); return b.bottom > 0 && b.top < innerHeight; });
    const r = e => { const b = e.getBoundingClientRect(); return [b.x, b.y, b.width, b.height].map(v => Math.round(v * 10) / 10).join(','); };
    const out = {list: r(list), body: document.body.scrollHeight, rows: visible.length, boxes: []};
    visible.slice(0, 12).forEach(row => { out.boxes.push(r(row)); row.querySelectorAll('*').forEach(e => out.boxes.push(e.tagName + ':' + r(e))); });
    return out;
})`;
const raf = page => page.evaluate(() => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r))));

async function open(pageName, v) {
    await fetch(BASE + '/__reset', {method: 'POST'});
    const context = await browser.newContext({viewport: {width: v.w, height: v.h}, locale});
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(String(e)));
    page.on('console', m => { if (m.text().startsWith('CSP violation')) errors.push(m.text()); });
    await context.route(u => !String(u).startsWith(BASE), r => r.abort());
    await page.addInitScript(() => {
        localStorage.setItem('pixiv_onboarding_v1', JSON.stringify({status: 'completed', name: 'x'}));
        document.addEventListener('securitypolicyviolation', e => console.error('CSP violation', e.violatedDirective));
    });
    await page.goto(BASE + (pageName === 'alt' ? '/pixiv-batch-alt.html' : '/pixiv-batch.html'));
    await page.waitForFunction(() => typeof state !== 'undefined' && window.PixivBatch?.queueTypes?.get?.('illust'));
    await page.waitForTimeout(2000);
    await page.evaluate(({alt, dark, font}) => {
        switchMode('single-import');
        if (alt) toggleDock(true);
        if (dark) document.documentElement.dataset.theme = 'dark';
        if (font) document.documentElement.style.fontSize = font;
        document.activeElement?.blur?.();
    }, {alt: pageName === 'alt', dark: !!v.dark, font: v.font || null});
    await page.waitForTimeout(600);
    return {context, page, errors};
}

async function enqueue(page, alt) {
    await page.evaluate(([isAlt, text]) => {
        if (isAlt) { document.getElementById('abImportInput').value = text; runImportParse(false); }
        else { document.getElementById('single-import-textarea').value = text; parseSingleImport(); }
        document.activeElement?.blur?.();
        document.querySelectorAll('.ab-toast, .toast').forEach(t => t.remove());
    }, [alt, ids]);
    await page.waitForFunction(([sel, n]) => document.querySelectorAll(sel).length >= n,
        [alt ? '#abQueueList > .ab-queue-item' : '#queue-list .queue-item', N]);
    await page.waitForTimeout(1500);
    await page.evaluate(() => document.querySelectorAll('.ab-toast, .toast, [role=status].is-visible').forEach(t => t.remove()));
}

// 列表最近的滚动祖先（没有时为文档滚动元素）。
const SCROLLER = `(function (list) {
    for (let el = list; el; el = el.parentElement) {
        const s = getComputedStyle(el);
        if (/(auto|scroll)/.test(s.overflowY) && el.scrollHeight > el.clientHeight) return el;
    }
    return document.scrollingElement;
})`;

for (const pageName of ['classic', 'alt']) {
    const alt = pageName === 'alt';
    const listSel = alt ? '#abQueueList' : '#queue-list';
    const rowSel = alt ? '#abQueueList > .ab-queue-item' : '#queue-list .queue-item';
    for (const v of variants) {
        const key = `${pageName}-${v.name}`;
        const {context, page, errors} = await open(pageName, v);
        await page.screenshot({path: path.join(outDir, `${key}-empty.png`), fullPage: false});
        await enqueue(page, alt);
        await page.screenshot({path: path.join(outDir, `${key}-queued-top.png`)});
        const geoTop = await page.evaluate(([ls, rs, src]) => eval(src)(ls, rs), [listSel, rowSel, GEOMETRY]);
        const scroll = await page.evaluate(([ls, scrollerSrc]) => {
            const scroller = eval(scrollerSrc)(document.querySelector(ls));
            const before = scroller.scrollHeight;
            scroller.scrollTop = scroller.scrollHeight;
            return {before, scrollerTag: scroller.id || scroller.tagName};
        }, [listSel, SCROLLER]);
        await raf(page); await page.waitForTimeout(400);
        await page.evaluate(([ls, scrollerSrc]) => { const s = eval(scrollerSrc)(document.querySelector(ls)); s.scrollTop = s.scrollHeight; }, [listSel, SCROLLER]);
        await raf(page); await page.waitForTimeout(400);
        scroll.after = await page.evaluate(([ls, scrollerSrc]) => eval(scrollerSrc)(document.querySelector(ls)).scrollHeight, [listSel, SCROLLER]);
        await page.screenshot({path: path.join(outDir, `${key}-queued-bottom.png`)});
        const geoBottom = await page.evaluate(([ls, rs, src]) => eval(src)(ls, rs), [listSel, rowSel, GEOMETRY]);
        const lastVisible = await page.evaluate(rs => {
            const rows = document.querySelectorAll(rs);
            const last = rows[rows.length - 1];
            const r = last.getBoundingClientRect();
            return {inViewport: r.bottom > 0 && r.top < innerHeight, height: Math.round(r.height * 10) / 10};
        }, rowSel);
        results[key] = {scroll, lastVisible, errors, geoTop, geoBottom};

        if (v.name === 'wide') {
            // 滚动高度稳定性：逐屏滚动并记录出现过的不同 scrollHeight
            results[key].scrollSteps = await page.evaluate(async ([ls, scrollerSrc]) => {
                const s = eval(scrollerSrc)(document.querySelector(ls));
                s.scrollTop = 0;
                const frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                await frame();
                const heights = new Set([s.scrollHeight]);
                let maxJump = 0, steps = 0;
                while (s.scrollTop + s.clientHeight < s.scrollHeight - 1 && steps < 4000) {
                    const target = s.scrollTop + s.clientHeight;
                    s.scrollTop = target;
                    await frame();
                    maxJump = Math.max(maxJump, Math.abs(s.scrollTop - target));
                    heights.add(s.scrollHeight);
                    steps++;
                }
                return {steps, distinctHeights: heights.size, minH: Math.min(...heights), maxH: Math.max(...heights), maxPositionJump: Math.round(maxJump)};
            }, [listSel, SCROLLER]);

            // 以脚本聚焦列表靠后的一行
            results[key].focus = await page.evaluate(async ([ls, rs, scrollerSrc, target]) => {
                const s = eval(scrollerSrc)(document.querySelector(ls));
                s.scrollTop = 0;
                const frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                await frame();
                const row = document.querySelectorAll(rs)[target];
                const btn = row.querySelector('button');
                const before = btn.checkVisibility({contentVisibilityAuto: true});
                btn.focus();
                await frame();
                const r = row.getBoundingClientRect(), sr = s === document.scrollingElement ? {top: 0, bottom: innerHeight} : s.getBoundingClientRect();
                return {skippedBefore: !before, focused: document.activeElement === btn,
                    renderedAfter: btn.checkVisibility({contentVisibilityAuto: true}), inView: r.bottom > sr.top && r.top < sr.bottom};
            }, [listSel, rowSel, SCROLLER, FOCUS_ROW]);

            // 从可见区域末端附近开始按 Tab 穿过各行
            await page.evaluate(([ls, scrollerSrc]) => { eval(scrollerSrc)(document.querySelector(ls)).scrollTop = 0; }, [listSel, SCROLLER]);
            await raf(page);
            await page.evaluate(rs => document.querySelectorAll(rs)[2].querySelector('button').focus(), rowSel);
            const tabTrail = [];
            for (let i = 0; i < 24; i++) {
                await page.keyboard.press('Tab');
                await raf(page);
                tabTrail.push(await page.evaluate(([ls, rs, scrollerSrc]) => {
                    const a = document.activeElement;
                    const rows = Array.from(document.querySelectorAll(rs));
                    const idx = rows.findIndex(r => r.contains(a));
                    const s = eval(scrollerSrc)(document.querySelector(ls));
                    const rr = a.getBoundingClientRect();
                    const sr = s === document.scrollingElement ? {top: 0, bottom: innerHeight} : s.getBoundingClientRect();
                    return {idx, tag: a.tagName, inView: rr.bottom > sr.top && rr.top < sr.bottom};
                }, [listSel, rowSel, SCROLLER]));
            }
            results[key].tab = {rows: tabTrail.map(t => t.idx), allInView: tabTrail.every(t => t.idx < 0 || t.inView)};

            // 用 window.find 查找列表靠后的一行
            results[key].find = await page.evaluate(async ([ls, rs, scrollerSrc, targetId]) => {
                const s = eval(scrollerSrc)(document.querySelector(ls));
                s.scrollTop = 0;
                document.activeElement?.blur?.();
                document.querySelectorAll('textarea').forEach(t => { t.value = ''; });
                getSelection().removeAllRanges();
                const frame = () => new Promise(r => requestAnimationFrame(() => requestAnimationFrame(r)));
                await frame();
                const found = window.find(String(targetId));
                await frame();
                const node = getSelection().anchorNode;
                const rows = Array.from(document.querySelectorAll(rs));
                const idx = rows.findIndex(r => node && r.contains(node));
                const r = idx >= 0 ? rows[idx].getBoundingClientRect() : null;
                const sr = s === document.scrollingElement ? {top: 0, bottom: innerHeight} : s.getBoundingClientRect();
                return {found, rowIndex: idx, inView: !!r && r.bottom > sr.top && r.top < sr.bottom};
            }, [listSel, rowSel, SCROLLER, ID_BASE + FIND_ROW]);
            results[key].targets = {items: N, focusRow: FOCUS_ROW, findRow: FIND_ROW};

            if (alt) {
                results[key].hintMargins = await page.evaluate(() => {
                    const hint = document.createElement('div');
                    hint.className = 'ab-setting-hint';
                    hint.setAttribute('popover', 'manual');
                    hint.append(document.createElement('p'), document.createElement('div'), document.createElement('small'),
                        Object.assign(document.createElement('div'), {className: 'setting-item'}), document.createElement('span'), 'text');
                    hint.querySelectorAll('p, small, div, span').forEach(e => { e.textContent = 'note'; });
                    document.body.appendChild(hint);
                    hint.showPopover();
                    const m = Array.from(hint.children).map(c => getComputedStyle(c).marginTop);
                    hint.remove();
                    return m;
                });
            }
        }
        results[key].errors = errors;
        await context.close();
    }
}
await browser.close();
fs.writeFileSync(path.join(outDir, 'results.json'), JSON.stringify(results, null, 1));
console.log(JSON.stringify(results, null, 1));
