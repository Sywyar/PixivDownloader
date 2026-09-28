'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {MiniElement, MiniEventTarget} = require('./pixiv-layout-feedback-test-dom');
const repo = path.resolve(__dirname, '../../../..');
const staticRoot = path.join(__dirname, '../../main/resources/static');

// 使用真实 Vue 响应式和 renderer，仅用内存节点代替浏览器 DOM。
function node(tag, text = '') { return {tag, text, props: {}, children: [], parent: null}; }
function remove(child) {
    if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1);
    child.parent = null;
}
function all(root, predicate) {
    return (predicate(root) ? [root] : []).concat(root.children.flatMap(child => all(child, predicate)));
}
function byClass(root, cls) { return all(root, n => String(n.props.class || '').split(' ').includes(cls)); }
function textOf(n) { return n.text + n.children.map(textOf).join(''); }

async function fixture(alt, size = 300, production = false) {
    const body = node('body');
    const stats = node('div'), current = node('div'), list = node('div');
    body.children.push(stats, current, list);
    body.children.forEach(n => { n.parent = body; });
    const statsClass = alt ? 'ab-dock-stats' : 'dash-stats';
    stats.props.class = statsClass;
    current.props.id = alt ? 'abCurrentCard' : 'current-card';
    list.props.id = alt ? 'abQueueList' : 'queue-list';
    const get = selector => all(body, n => selector[0] === '#'
        ? n.props.id === selector.slice(1) : n.props.class === selector.slice(1))[0] || null;
    body.querySelector = get;
    const raf = [], errors = [], builds = [], removed = [], cancelled = [];
    let clock = 0, language = 'en';
    const metrics = {rowVnodes: 0, detachedElements: 0};
    const state = {queue: Array.from({length:size}, (_,i) => ({id:String(i), kind:'illust', status:i ? 'pending':'downloading', title:'<unsafe '+i+'>', downloadedCount:0, totalImages:100})), stats:{}, isPaused:false};
    const sandbox = {
        state, QUICK_FETCH_MODE: 'quick-fetch', SINGLE_IMPORT_MODE: 'single-import',
        document: Object.assign(new MiniEventTarget(), {body,
            createElement: tag => { metrics.detachedElements++; return new MiniElement(tag); },
            createElementNS: (ns, tag) => { metrics.detachedElements++; return new MiniElement(tag); },
            createTextNode: text => Object.assign(new MiniElement('#text'), {textContent: text}), querySelector:get,
            getElementById:id => id === 'abDockBody' ? body : get('#'+id), contains:n => n === body || !!n.parent}),
        console:{warn:(...args)=>errors.push(args),error:(...args)=>errors.push(args)},
        requestAnimationFrame:cb=>raf.push(cb), setTimeout, clearTimeout, performance:{now:()=>clock},
        bt:(key,fallback)=>language+':'+key, abIcon:()=>'<svg></svg>',
        esc:value=>String(value == null ? '' : value).replace(/[&<>"']/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])),
        formatBytes:value=>String(value), formatDurationMs:value=>String(value),
        queueDataSourceText:()=> 'source', queueSourceText:()=> 'mode',
        queueItemDisplayTitle:q=>{if(alt)builds.push(q.id);return q.title;}, queueItemCanonicalUrl:q=>'https://example.com/'+q.id,
        queueItemMessage:q=>String(q.downloadedCount), pct:q=>q.downloadedCount,
        progressExtrasModel:()=>[],
        currentFrontItem:items=>items.find(q=>q.status==='downloading')||null,
        currentCardModel:q=>q?{title:q.title,percent:q.downloadedCount,detail:'current:'+q.downloadedCount}:null,
        currentRemainingCounts:()=>({downloading:0,queued:0}),currentRemainingLineText:()=>'',
        buildQueueItemHtml:q=>{builds.push(q.id);return '<div>'+q.id+':'+q.downloadedCount+'</div>';},
        computeCurrentCardHtml:items=>items[0] ? 'current:'+items[0].downloadedCount : 'empty',
        formatCurrentCardHtml:q=>q ? q.title : '',
        removeFromQueue:id=>{removed.push(id);return true;}, requestQueueItemCancel:id=>cancelled.push(id), abToast() {},
        updateStats(){}, renderQueue(){}, renderCurrent(){}, setCurrent(){}
    };
    sandbox.window = sandbox;
    sandbox.PixivBatch = {state:{state}, queueTypes:{canCancel:q=>q.status==='downloading',queueTags:()=>[],
        get:()=>null,dataSourceForType:()=>null}};
    sandbox.PixivBatchAlt = {};
    vm.createContext(sandbox, {codeGeneration:{strings:false,wasm:false}});
    if (production || !alt) {
        const facades = Object.fromEntries(['updateStats','renderQueue','renderCurrent','setCurrent'].map(key => [key, sandbox[key]]));
        for (const file of alt ? ['alt-core.js','alt-queue.js'] : ['batch-queue-model.js','batch-queue-view.js']) {
            vm.runInContext(fs.readFileSync(path.join(staticRoot,alt?'pixiv-batch-alt':'pixiv-batch',file),'utf8'), sandbox);
        }
        Object.assign(sandbox, facades);
        if (alt) vm.runInContext("pageI18n = {t: (key, fallback, vars) => key + ':' + JSON.stringify(vars || {})}", sandbox);
        else {
            const title = sandbox.queueItemDisplayTitle;
            sandbox.queueItemDisplayTitle = q => {builds.push(q.id);return title(q);};
            sandbox.bt = (key,fallback,vars) => language + ':' + key + (vars ? ':' + JSON.stringify(vars) : '');
        }
    }
    assert.throws(()=>vm.runInContext('new Function("return 1")',sandbox), /Code generation/);
    vm.runInContext(fs.readFileSync(path.join(repo,'pixivdownload-app/src/main/resources/static/vendor/vue/vue.global.prod.js'),'utf8'),sandbox);
    const Vue = sandbox.Vue;
    const originalH = Vue.h;
    Vue.h = (type, props, ...children) => {
        if (typeof type === 'object' && props && Object.hasOwn(props, 'index')) metrics.rowVnodes++;
        return originalH(type, props, ...children);
    };
    const renderer = Vue.createRenderer({
        createElement:tag=>node(tag),createText:text=>node('#text',text),createComment:()=>node('#comment'),
        setText(n,text){n.text=String(text);},setElementText(n,text){n.text=String(text);n.children=[];},
        parentNode:n=>n.parent,nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]||null,remove,
        insert(child,parent,anchor=null){remove(child);const i=anchor?parent.children.indexOf(anchor):parent.children.length;assert.ok(i>=0);parent.children.splice(i,0,child);child.parent=parent;},
        patchProp(n,key,old,value){n.props[key]=value;}
    });
    sandbox.PixivVue = {ensure:async()=>Vue,mountOn:async(el,component)=>{
        const app=renderer.createApp(component);app.mount(el);return {app,el};
    }};
    vm.runInContext(fs.readFileSync(path.join(staticRoot,alt?'pixiv-batch-alt/alt-queue-vue.js':'pixiv-batch/batch-queue-vue.js'),'utf8'),sandbox);
    const api = alt ? sandbox.PixivBatchAlt.queueVue : sandbox.PixivBatch.queueVue;
    assert.equal(await (alt?api.ensure():api.mountDownloadQueue()),true);
    async function flush(){for(let i=0;i<20;i++){clock+=1000;raf.splice(0).forEach(cb=>cb());await Vue.nextTick();if(!raf.length)break;}assert.equal(raf.length,0);}
    const sync=item=>alt?api.syncList(item):api.syncDownloadList(item);
    sync();await flush();
    state.sseListeners = {};
    sandbox.setInterval = () => 1;
    sandbox.clearInterval = () => {};
    sandbox.setTimeout = () => 1;
    sandbox.clearTimeout = () => {};
    sandbox.mergeUgoiraProgress = (previous, incoming) => ({...previous, ...incoming});
    sandbox.renderQueue = sync;
    sandbox.setCurrent = sandbox.renderCurrent = sync;
    vm.runInContext(fs.readFileSync(path.join(staticRoot, alt
        ? 'pixiv-batch-alt/alt-engine-stream.js' : 'pixiv-batch/batch-sse.js'), 'utf8'), sandbox);
    return {api,Vue,state,body,stats,current,list,builds,errors,removed,cancelled,sync,flush,metrics,sandbox,
        wait: id => sandbox.waitForFinalStatusBySSE(id, 10000),
        emit: (id, event) => state.sseListeners[id].slice().forEach(listener => listener(event)),
        setLanguage(value){language=value;},rowClass:alt?'ab-queue-item':'q-item-host'};
}

for(const alt of [false,true]) {
    test((alt?'侧栏':'经典')+'下载队列在禁止动态编译时挂载，进度仅重新计算变动行',async()=>{
        const f=await fixture(alt);
        const rows=()=>byClass(f.list,f.rowClass);
        assert.equal(rows().length,300);
        const stable=rows()[299];
        const counts={pending:299,success:2,failed:3,active:1,skipped:4};
        (alt?f.api.syncStats:f.api.syncDownloadStats)(counts);
        (alt?f.api.syncSpeed:f.api.syncDownloadSpeed)('8','MiB/s');
        await f.flush();
        const id=value=>all(f.stats,n=>n.props.id===value)[0];
        assert.equal(textOf(id(alt?'abStatPending':'stat-count-pending')),'299');
        assert.equal(textOf(id(alt?'abStatSpeedUnit':'stat-speed-unit')),'MiB/s');
        assert.match(textOf(f.stats),/en:.*queued/);
        if(alt) {
            const click={stopPropagation(){}};
            all(rows()[0],n=>n.tag==='button')[0].props.onClick(click);
            all(rows()[1],n=>n.tag==='button')[0].props.onClick(click);
            assert.deepEqual(f.cancelled,['0']);assert.deepEqual(f.removed,['1']);
            assert.equal(textOf(byClass(rows()[0],'ab-queue-name')[0]),'<unsafe 0>');
        }
        f.builds.length=0;
        const completed = f.wait('0');
        for(let i=0;i<60;i++) f.emit('0', {downloadedCount:i+1});
        await f.flush();
        assert.deepEqual(f.builds,['0']);
        assert.equal(rows()[299],stable);
        assert.match(textOf(rows()[0]),/60/);
        assert.match(textOf(f.current),alt?/current:60/:/"downloaded":60/);
        f.emit('0', {completed:true});
        assert.equal((await completed).completed, true);
        assert.equal(Object.keys(f.state.sseListeners).length, 0);
        // 同一批中的不同作品都必须更新，不能因按帧合并而丢掉前一个作品。
        f.builds.length=0;
        f.state.queue[0].downloadedCount=70;f.sync(f.state.queue[0]);
        f.state.queue[1].downloadedCount=80;f.sync(f.state.queue[1]);
        await f.flush();
        assert.deepEqual(f.builds.sort(),['0','1']);
        f.state.queue.reverse();f.sync();await f.flush();
        assert.equal(rows()[0],stable);
        f.state.queue=[];f.sync();await f.flush();
        assert.equal(rows().length,0);
        assert.match(textOf(f.list),/queue-empty/);
        assert.deepEqual(f.errors,[]);
    });

    test((alt?'侧栏':'经典')+'的单行刷新不构造其余行虚拟节点，替换与删除仍更新结构',async()=>{
        for (const size of [50,500]) {
            const f=await fixture(alt,size);
            const stable=byClass(f.list,f.rowClass)[size-1];
            f.metrics.rowVnodes=0;f.builds.length=0;
            for(let i=0;i<100;i++) {
                f.state.queue[0].downloadedCount=i;f.sync(f.state.queue[0]);await f.flush();
            }
            assert.equal(f.metrics.rowVnodes,0);
            assert.equal(f.builds.length,100);
            assert.equal(byClass(f.list,f.rowClass)[size-1],stable);
            const stale=f.state.queue[0];
            f.state.queue.splice(0,1,{id:stale.id,kind:'novel',status:'pending',title:'replacement',downloadedCount:8});
            f.sync(stale);await f.flush();
            assert.equal(byClass(f.list,f.rowClass).length,size);
            assert.match(textOf(byClass(f.list,f.rowClass)[0]),/replacement/);
            f.state.queue.splice(0,1);f.sync();await f.flush();
            assert.equal(byClass(f.list,f.rowClass).length,size-1);
            f.state.queue=[];f.sync();await f.flush();
            assert.equal(byClass(f.list,f.rowClass).length,0);
            assert.deepEqual(f.errors,[]);
        }
    });

    test((alt?'侧栏':'经典')+'计划详情在禁止动态编译时响应状态与空列表',async()=>{
        const f=await fixture(alt),box=node('section');box.parent=f.body;f.body.children.push(box);
        box.querySelector=()=>null;
        let model=alt?{startedText:'start',statsText:'one',empty:false,truncated:true,truncatedText:'more',currentHtml:'current',rows:[{key:'a',status:'pending',html:'<div>&lt;unsafe&gt; · AI · waiting</div>'}]}
            :{statusText:'start',statsText:'one',current:{title:'current'},items:[{id:'a',kind:'illust',downloadedCount:1}]};
        f.api.ensureScheduleQueue(7,alt?{boxEl:box,read:()=>model}:{bodyEl:box,read:()=>model});
        await new Promise(resolve=>setImmediate(resolve));await f.flush();
        assert.equal(f.api.isScheduleActive(7),true);
        assert.match(textOf(box),/start/);
        if(alt){
            const rows=all(box,n=>n.props['data-queue-key']==='a');
            assert.equal(rows.length,1);
            assert.equal(rows[0].props.innerHTML,model.rows[0].html);
            assert.equal(rows[0].props['data-status'],'pending');
            assert.equal(byClass(box,'ab-round-current')[0].props.innerHTML,'current');
            model.rows=[{key:'a',status:'completed',html:'<div>completed</div>'}];
            f.api.syncScheduleQueue(7);await f.flush();
            assert.equal(rows[0].props['data-status'],'completed');
            assert.equal(rows[0].props.innerHTML,'<div>completed</div>');
        }
        else assert.equal(byClass(box,'q-item-host').length,1);
        model=alt?{empty:true,emptyText:'empty',rows:[]}:{items:[]};
        f.api.syncScheduleQueue(7);await f.flush();
        assert.equal(alt?all(box,n=>n.props['data-queue-key']==='a').length:byClass(box,'q-item-host').length,0);
        f.api.unmountScheduleQueue(7);
        assert.equal(f.api.isScheduleActive(7),false);
        assert.deepEqual(f.errors,[]);
    });
}

test('新版真实展示函数保留进度节点，覆盖图片、小说、动图与暂停收尾',async()=>{
    const f=await fixture(true,2,true), q=f.state.queue[0];
    const head=byClass(f.current,'ab-current-head')[0];
    const ring=byClass(f.current,'ab-ring-fill')[0];
    q.imageProgress={downloadedBytes:1024,totalBytes:4096,progress:25};
    f.sync(q);await f.flush();
    const extras=byClass(f.current,'ab-progress-extras')[0];
    const fill=byClass(extras,'ab-mini-prog-fill')[0];
    f.metrics.detachedElements=0;
    for(let i=1;i<=100;i++) {
        q.downloadedCount=i;q.imageProgress.progress=i;
        f.sync(q);await f.flush();
    }
    assert.equal(f.metrics.detachedElements,0,'Vue 更新不创建中间 DOM');
    assert.equal(byClass(f.current,'ab-current-head')[0],head);
    assert.equal(byClass(f.current,'ab-ring-fill')[0],ring);
    assert.equal(byClass(f.current,'ab-progress-extras')[0],extras);
    assert.equal(byClass(extras,'ab-mini-prog-fill')[0],fill);
    assert.equal(fill.props.style.width,'100%');
    assert.equal(ring.props.style.strokeDashoffset,'0');
    q.kind='novel';delete q.imageProgress;
    q.novelText={done:3,total:0};q.novelCover={done:4,total:8};q.novelEmbedded={done:2,total:4};
    q.translatePhase='TRANSLATING';q.translateElapsed=12;
    f.sync(q);await f.flush();
    assert.equal(byClass(f.current,'is-indeterminate').length,1);
    assert.equal(byClass(f.current,'ab-mini-badge--ai').length,1);
    assert.match(textOf(f.current),/"sec":12/);
    q.kind='illust';delete q.translatePhase;
    q.ugoiraProgress={phase:'extract',zipProgress:100,totalFrames:10,extractedFrames:4};
    f.sync(q);await f.flush();
    assert.match(textOf(f.current),/"current":4,"total":10/);
    q.ugoiraProgress={phase:'ffmpeg',ffmpegProgress:50,ffmpegOutTimeMs:1000,ffmpegDurationMs:2000};
    f.sync(q);await f.flush();
    assert.equal(byClass(f.current,'is-ffmpeg')[0].props.style.width,'50%');
    q.ugoiraProgress.status='failed';f.sync(q);await f.flush();
    assert.equal(byClass(f.current,'ab-progress-note--error').length,1);
    f.api.syncPaused(true);await f.flush();
    assert.equal(byClass(f.current,'ab-current-idle').length,0);
    q.status='completed';f.sync(q);await f.flush();
    assert.equal(byClass(f.current,'ab-current-idle').length,1);
    assert.equal(byClass(f.current,'ab-progress-extras').length,0);
    assert.deepEqual(f.errors,[]);
});

test('经典真实展示保留标题、按钮和进度节点，并更新标签、动图、小说及暂停收尾',async()=>{
    const f=await fixture(false,2), q=f.state.queue[0];
    const title=byClass(f.list,'q-title-main')[0];
    const cancel=byClass(f.list,'queue-cancel-btn')[0];
    const currentLabel=all(f.current,n=>n.tag==='strong')[0];
    const fill=byClass(f.current,'prog-fill')[0];
    assert.equal(textOf(title),'<unsafe 0>');
    assert.equal(cancel.props['data-queue-cancel-id'],'0');
    assert.equal(byClass(f.list,'queue-remove-btn')[0].props['data-queue-remove-id'],'1');
    q.imageProgress={downloadedBytes:1024,totalBytes:4096,progress:25};
    f.sync(q);await f.flush();
    const imageFill=byClass(f.current,'prog-fill')[1];
    for(let i=1;i<=100;i++) {
        q.downloadedCount=i;q.imageProgress.progress=i;f.sync(q);await f.flush();
    }
    assert.equal(byClass(f.list,'q-title-main')[0],title);
    assert.equal(byClass(f.list,'queue-cancel-btn')[0],cancel);
    assert.equal(all(f.current,n=>n.tag==='strong')[0],currentLabel);
    assert.equal(byClass(f.current,'prog-fill')[0],fill);
    assert.equal(byClass(f.current,'prog-fill')[1],imageFill);
    assert.equal(imageFill.props.style.width,'100%');
    assert.equal(all(f.body,n=>Object.hasOwn(n.props,'innerHTML')).length,0);
    q.kind='novel';q.novelId='42';delete q.imageProgress;
    q.novelText={done:3,total:0};q.novelCover={done:4,total:8};q.novelEmbedded={done:2,total:4};
    q.lastMessageParts=[{text:'<unsafe status>',tone:'error'}];
    f.sandbox.PixivBatch.queueTypes.queueTags=()=>[{id:'fixture',label:'<tag>'}];
    f.sandbox.PixivBatch.queueTypes.queueLiveStatus=()=>({tone:'info',label:'AI',message:'<phase>'});
    f.sync(q);await f.flush();
    assert.match(textOf(f.list),/42 \(Novel\).*<unsafe status>/);
    assert.match(textOf(f.list),/queue.novel-text.label/);
    assert.match(textOf(f.list),/"done":2,"total":4/);
    assert.equal(textOf(byClass(f.list,'queue-tag--plugin')[0]),'<tag>');
    assert.equal(textOf(byClass(f.list,'q-live-status')[0]),'AI<phase>');
    const html=f.sandbox.buildQueueItemHtml(q,{removable:false});
    assert.match(html,/&lt;unsafe status&gt;/);
    assert.match(html,/&lt;tag&gt;/);
    assert.doesNotMatch(html,/queue-(remove|cancel)-btn/);
    q.ugoiraProgress={phase:'extract',zipProgress:100,totalFrames:10,extractedFrames:4};
    f.sync(q);await f.flush();
    assert.match(textOf(f.current),/"current":4,"total":10/);
    q.ugoiraProgress={phase:'ffmpeg',ffmpegProgress:50,status:'failed'};
    f.sync(q);await f.flush();
    assert.equal(byClass(f.current,'queue-detail-note--error').length,1);
    f.setLanguage('fr');f.sync();await f.flush();
    assert.match(textOf(f.current),/fr:label.current/);
    assert.match(byClass(f.list,'queue-remove-btn')[0].props.title,/fr:queue.remove/);
    f.api.syncDownloadPaused(true);await f.flush();
    assert.match(textOf(f.current),/ID: 0/);
    q.status='completed';f.sync(q);await f.flush();
    assert.match(textOf(f.current),/status.current-idle/);
    assert.equal(byClass(f.current,'prog-fill').length,0);
    f.api.syncDownloadPaused(false);await f.flush();
    assert.match(textOf(f.current),/ID: 1/);
    assert.equal(byClass(f.current,'current-remaining').length,0);
    const opaque=' work /<img src=x> "\' ';
    f.state.queue=[{id:opaque,kind:'illust',title:'A'},{id:opaque,kind:'novel',title:'B'},{id:opaque.trim(),kind:'illust',title:'C'}];
    f.sync();await f.flush();
    const nodes=byClass(f.list,'q-title-main');
    f.state.queue.reverse();f.sync();await f.flush();
    assert.deepEqual(byClass(f.list,'q-title-main'),nodes.slice().reverse());
    assert.deepEqual(f.errors,[]);
});
