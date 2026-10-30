import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
const module = await import(pathToFileURL(path.resolve(process.argv[2])).href);
const encoded = await import(pathToFileURL(path.join(path.dirname(path.resolve(process.argv[2])), 'acceptance.remotecontract.encoded.RemoteLedger.mjs')).href);
const origin = process.env.REMOTE_ORIGIN;
const cookie = process.env.REMOTE_COOKIE;
const guest = process.env.REMOTE_GUEST;
const available = process.argv[3] === 'active';
const endpoint = '/api/remote-ledger/record';
const input = {sequence:9007199254740991,text:'😀中文',numbers:[-2147483648,2147483647],labels:{'private-map-key':'值'},mode:'NORMAL'};
const fetcher = (url, init) => fetch(origin + url, {...init,headers:{...init.headers,Cookie:cookie,Origin:origin}});
const client = module.createClient(fetcher);
const valuesClient = encoded.createClient(fetcher), w = encoded.wireValues;
const values = {sequence:w.int64(9223372036854775807n),id:w.uuid('12345678-1234-5678-1234-567812345678'),
  instant:w.instant(31556889864403199n,999999999),duration:w.duration(-9223372036854775808n,1),score:w.float32(-0),ratio:w.float64(Number.MIN_VALUE)};
const raw = (body, headers = {}, url = endpoint) => fetch(origin + url, {method:'POST',
  headers:{'Content-Type':'application/json','X-Pixiv-Contract':module.contractIdentity,Cookie:cookie,Origin:origin,...headers},body});
async function rejected(response, status) {
  assert.equal(response.status,status);
  const text=await response.text();
  assert.ok(!text.includes('private-map-key') && !text.includes('private sample') && !text.includes('private cause'));
  return JSON.parse(text);
}
if (!available) {
  await assert.rejects(client.record(input), error => error.code === 'REMOTE_UNAVAILABLE');
  await assert.rejects(valuesClient.roundTrip(values), error => error.code === 'REMOTE_UNAVAILABLE');
} else {
  const roundTrip = await valuesClient.roundTrip(values);
  assert.deepEqual(roundTrip,values);
  assert.ok(Object.is(w.number32(roundTrip.score),-0)); assert.equal(w.number64(roundTrip.ratio),Number.MIN_VALUE);
  for (const changes of [{sequence:'9223372036854775808'},{score:'7f800000'},{ratio:'7ff0000000000000'},
      {instant:'31556889864403200:000000000'},{duration:'-9223372036854775809:000000000'},{id:'1-1-1-1-1'}]) {
    await assert.rejects(valuesClient.roundTrip({...values,...changes}), {code:'REMOTE_VALUE_INVALID'});
    const response = await raw(JSON.stringify({...values,...changes}), {'X-Pixiv-Contract':encoded.contractIdentity}, '/api/remote-values/round-trip');
    assert.equal((await rejected(response,400)).code,'REMOTE_VALUE_INVALID');
  }
  assert.deepEqual(await client.record(input), {...input,note:null});
  const named = JSON.parse('{"__proto__":"😀中文"}');
  for (const operation of ['__proto__', 'routes']) {
    assert.equal(Object.hasOwn(client, operation), true);
    const result = await client[operation](named);
    assert.deepEqual(result, named);
    assert.deepEqual(Object.keys(result), ['__proto__']);
  }
  assert.equal((await rejected(await raw(JSON.stringify('localOnly')),400)).code,'REMOTE_VALUE_INVALID');
  await assert.rejects(client.record({...input,sequence:9007199254740992}), error => error.code==='REMOTE_VALUE_INVALID');
  await assert.rejects(client.record({...input,numbers:['bad']}), error => error.code==='REMOTE_VALUE_INVALID');
  for (const bad of [{...input,sequence:9007199254740992},{...input,text:null},{...input,extra:true},
    {...input,mode:'UNKNOWN'},{...input,numbers:[2147483648]},{...input,text:String.fromCharCode(0xd800)},{}]) {
    const error=await rejected(await raw(JSON.stringify(bad)),400);
    assert.equal(error.code,'REMOTE_VALUE_INVALID');
  }
  const failureBody=JSON.stringify({...input,mode:'FAIL'});
  const english=await rejected(await raw(failureBody,{'Accept-Language':'en-US'}),500);
  const chinese=await rejected(await raw(failureBody,{'Accept-Language':'zh-CN'}),500);
  assert.equal(english.code,'REMOTE_FAILED'); assert.equal(chinese.code,english.code);
  assert.equal(typeof english.error,'string'); assert.notEqual(english.error,chinese.error);
  await rejected(await raw(JSON.stringify(input),{'X-Pixiv-Contract':'0'.repeat(64)}),409);
}
const anonymous=await raw(JSON.stringify(input),{Cookie:''});
assert.ok([401,403].includes(anonymous.status));
if (guest) await rejected(await raw(JSON.stringify(input),{Cookie:guest}),403);
await rejected(await raw(JSON.stringify(input),{Origin:'https://foreign.invalid'}),403);
await rejected(await raw(JSON.stringify(input),{Origin:''}),403);
assert.equal((await raw(JSON.stringify(input),{},'/api/remote-ledger/undeclared')).status,404);
console.log('REMOTE_HTTP_' + (available?'ACTIVE':'ABSENT') + '_PASSED');
