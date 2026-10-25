const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/userscripts/pixiv-batch-downloader-import.user.js'), 'utf8');
const flush = async () => { for (let i=0;i<20;i++) await Promise.resolve(); };
function browser(storage = new Map()) {
    const listeners = {}, calls = [], alerts = [], timers = new Map();
    let offline = false, deferred = false, nextTimer = 0, aborts = 0, now = Date.now();
    class Clock extends Date { static now() { return now; } }
    const respond = (options, body = options.method === 'GET' ? {token:'once'} : {code:'IMPORTED'}) =>
        options.onload({status:200, finalUrl:options.url, responseText:JSON.stringify(body)});
    const context = vm.createContext({URL, TextEncoder, Date:Clock, navigator:{language:'en-US'}, crypto:{randomUUID:()=> 'session'},
        window:{location:{origin:'https://www.pixiv.net'},addEventListener:(name,callback)=>listeners[name]=callback},
        GM_getValue:(key,fallback)=>storage.has(key)?storage.get(key):fallback,
        GM_setValue:(key,value)=>storage.set(key,value), GM_deleteValue:key=>storage.delete(key), GM_listValues:()=>[...storage.keys()],
        GM_registerMenuCommand:()=>{}, alert:text=>alerts.push(text), prompt:()=>null,
        setTimeout:fn=>{ const id = ++nextTimer; timers.set(id, fn); return id; },
        clearTimeout:id=>timers.delete(id),
        GM_xmlhttpRequest:options=>{
            calls.push(options);
            if(offline) options.onerror();
            else if(!deferred) respond(options);
            return {abort:()=>{ aborts++; options.onabort(); }};
        }
    });
    vm.runInContext(source,context);
    return {storage, calls, alerts, timers, respond, aborts:()=>aborts,
        offline:()=>offline=true, online:()=>offline=false, defer:()=>deferred=true,
        advance:milliseconds=>now+=milliseconds,
        tick:async()=>{ const [id, callback]=timers.entries().next().value; timers.delete(id); await callback(); await flush(); },
        event:(name,data)=>listeners[name]?.({detail:{data}})};
}
const meta = (id=42,type=0,pages=1)=>({idNum:id,type,title:'Title',pageCount:pages,xRestrict:0,aiType:1,
    userId:'84',user:'Author',tags:['tag'],novelMeta:{content:'Novel content'}});
// 上游成功事件只携带作品、标签页和实际路径，不回传下载请求的 taskBatch。
const success = (id='42_p0',name='/downloads/custom.png')=>({id,tabId:2,blobURLFront:'blob:https://www.pixiv.net/download',browserSetFilename:name});
test('all four types upload complete events automatically without copy or confirmation', async()=>{
    const b=browser(); await flush();
    for(let type=0;type<4;type++) {
        const id=42+type;
        b.event('addResult',meta(id,type));
        b.event('downloadStart',{});
        b.event('downloadSuccess',success(type<2?`${id}_p0`:String(id)));
        await flush();
    }
    const posts=b.calls.filter(x=>x.method==='POST');
    assert.equal(posts.length,4);
    assert.deepEqual(posts.map(x=>JSON.parse(x.data).metadata.type),[0,1,2,3]);
    assert.equal(posts[0].anonymous,true);
    assert.equal(posts[0].headers['X-Import-Token'],'once');
    assert.ok(posts.every(call=>new URL(call.url).pathname==='/api/pixiv-batch-downloader-import'));
    assert.ok(b.calls.filter(call=>call.method==='GET').every(call=>
        new URL(call.url).pathname==='/api/pixiv-batch-downloader-import/token'));
    assert.ok(b.calls.every(call=>call.headers.Origin==='https://www.pixiv.net' && call.anonymous));
    assert.equal(b.alerts.length,0);
    assert.equal(b.storage.size,0);
});
test('partial, skipped and unconfirmed downloads do not become completed gallery works',async()=>{
    const b=browser(); await flush();
    b.event('addResult',meta(42,1,2));
    b.event('downloadStart',{});
    const first=success();
    b.event('downloadSuccess',first); await flush();
    b.event('skipDownload',{id:'42_p1'});
    b.event('downloadComplete',{}); await flush();
    assert.equal(b.calls.length,0);
    b.event('downloadSuccess',{...first,id:'42_p1',noReply:true});await flush();
    assert.equal(b.calls.length,0);
    b.event('downloadSuccess',{...first,id:'42_p1',browserSetFilename:'/downloads/other.png'});await flush();
    assert.equal(b.calls.filter(x=>x.method==='POST').length,1);
    assert.equal(JSON.parse(b.calls.find(x=>x.method==='POST').data).files.length,2);
});
test('offline pending work survives page reload and retries automatically',async()=>{
    const b=browser();await flush();b.offline();
    b.event('addResult',meta()); b.event('downloadStart',{}); b.event('downloadSuccess',success());await flush();
    assert.equal(b.storage.size,1);
    const restored=browser(b.storage);await flush();
    assert.equal(restored.calls.filter(x=>x.method==='POST').length,1);
    assert.equal(restored.storage.size,0);
});
test('duplicate success is deduplicated and conflicting paths remain blocked',async()=>{
    const b=browser();await flush();b.offline();
    b.event('addResult',meta(42,1,2));
    b.event('downloadStart',{});
    const first=success();
    b.event('downloadSuccess',first); b.event('downloadSuccess',first);await flush();
    assert.equal(JSON.parse([...b.storage.values()][0]).files.length,1);
    b.event('downloadSuccess',{...first,browserSetFilename:'/downloads/conflict.png'});
    b.online(); await b.timers.values().next().value();await flush();
    assert.equal(b.calls.filter(x=>x.method==='POST').length,0);
    assert.equal(JSON.parse([...b.storage.values()][0]).error,'AMBIGUOUS_PAGE');
});

test('success before download start and after a new crawl cannot reuse prior metadata',async()=>{
    const b=browser();await flush();
    b.event('addResult',meta());b.event('downloadSuccess',success());await flush();
    assert.equal(b.calls.length,0);
    b.event('downloadStart',{});b.event('crawlStart',{});
    b.event('downloadSuccess',success());await flush();
    assert.equal(b.calls.length,0);
});

test('pause and resume complete the same manga while a new crawl keeps its pages separate',async()=>{
    const b=browser();await flush();
    b.event('addResult',meta(42,1,2));b.event('downloadStart',{});
    b.event('downloadSuccess',success());await flush();
    b.event('downloadPause',{});b.event('downloadStart',{});
    b.event('downloadSuccess',success('42_p1','/downloads/page-1.png'));await flush();
    assert.equal(b.calls.filter(x=>x.method==='POST').length,1);
    const completed=JSON.parse(b.calls.find(x=>x.method==='POST').data);
    assert.ok(Number.isSafeInteger(completed.taskBatch) && completed.taskBatch>0);
    b.event('crawlStart',{});b.event('addResult',meta(42,1,2));b.event('downloadStart',{});
    b.event('downloadSuccess',success('42_p1','/downloads/new-page-1.png'));await flush();
    assert.equal(b.calls.filter(x=>x.method==='POST').length,1);
    assert.equal(JSON.parse([...b.storage.values()][0]).files.length,1);
});

test('离页取消领票，返回后恢复且旧响应不能继续导入', async()=>{
    const b=browser(); await flush(); b.defer();
    b.event('addResult',meta()); b.event('downloadStart',{}); b.event('downloadSuccess',success());
    await flush();
    const oldToken=b.calls[0];
    b.event('pagehide',{}); await flush();
    assert.equal(b.aborts(),1);
    assert.equal(b.timers.size,0);
    b.respond(oldToken); await flush();
    assert.equal(b.calls.length,1);
    assert.equal(b.storage.size,1);
    b.event('pageshow',{}); await flush();
    assert.equal(b.calls.length,2);
    b.respond(b.calls[1]); await flush();
    assert.equal(b.calls[2].method,'POST');
    b.respond(b.calls[2]); await flush();
    assert.equal(b.storage.size,0);
    assert.equal(b.timers.size,1);
});

test('离页后的旧导入确认不能删除新一轮待确认记录', async()=>{
    const b=browser(); await flush(); b.defer();
    b.event('addResult',meta()); b.event('downloadStart',{}); b.event('downloadSuccess',success());
    b.respond(b.calls[0]); await flush();
    const oldPost=b.calls[1];
    b.event('pagehide',{}); await flush();
    b.event('pageshow',{}); await flush();
    assert.equal(b.calls.length,3);
    b.respond(oldPost); await flush();
    assert.equal(b.storage.size,1);
    b.respond(b.calls[2]); await flush();
    assert.equal(b.calls[3].method,'POST');
    b.respond(b.calls[3],{code:'ALREADY_RECORDED'}); await flush();
    assert.equal(b.storage.size,0);
    assert.equal(b.timers.size,1);
});

test('空闲轮询随页面释放并只恢复一次', async()=>{
    const b=browser(); await flush();
    assert.equal(b.timers.size,1);
    b.event('pagehide',{}); await flush();
    assert.equal(b.timers.size,0);
    b.event('pageshow',{}); b.event('pageshow',{}); await flush();
    assert.equal(b.timers.size,1);
    b.event('addResult',meta()); b.event('downloadStart',{}); b.event('downloadSuccess',success());
    await flush();
    assert.equal(b.calls.filter(call=>call.method==='POST').length,1);
    assert.equal(b.storage.size,0);
    assert.equal(b.timers.size,1);
});

test('请求失败保留队列和退避，成功事件不能绕过等待', async()=>{
    const b=browser(); await flush(); b.defer();
    b.event('addResult',meta()); b.event('downloadStart',{}); b.event('downloadSuccess',success());
    b.calls[0].ontimeout(); await flush();
    b.event('downloadSuccess',success()); await flush();
    assert.equal(b.calls.length,1);
    assert.equal(b.storage.size,1);
    assert.equal(b.timers.size,1);
    b.advance(2000); await b.tick();
    assert.equal(b.calls.length,2);
    b.calls[1].onload({status:200,finalUrl:'http://[',responseText:'{}'}); await flush();
    assert.equal(b.storage.size,1);
    assert.equal(b.timers.size,1);
    b.advance(4000); await b.tick();
    b.respond(b.calls[2]); await flush();
    b.respond(b.calls[3]); await flush();
    assert.equal(b.storage.size,0);
    assert.equal(b.timers.size,1);
});

for (const duringPost of [false,true]) {
    test(`等待${duringPost?'导入确认':'领票'}期间的新冲突记录不会被旧请求覆盖`, async()=>{
        const b=browser(); await flush(); b.defer();
        b.event('addResult',meta()); b.event('downloadStart',{}); b.event('downloadSuccess',success());
        if (duringPost) { b.respond(b.calls[0]); await flush(); }
        b.event('downloadSuccess',success('42_p0','/downloads/conflict.png')); await flush();
        const pending=b.calls[b.calls.length-1];
        b.respond(pending); await flush();
        assert.equal(b.calls.filter(call=>call.method==='POST').length,duringPost?1:0);
        assert.equal(b.storage.size,1);
        assert.equal(JSON.parse([...b.storage.values()][0]).error,'AMBIGUOUS_PAGE');
        assert.equal(b.timers.size,1);
    });
}
