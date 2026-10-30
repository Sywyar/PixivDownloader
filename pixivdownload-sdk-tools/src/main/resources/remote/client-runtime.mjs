// 生成客户端的运行期纯值边界；声明文件不承担输入校验。
const MAX_BYTES = 1024 * 1024;
export const contractIdentity = contract.identity;
export class RemoteError extends Error {
  constructor(code) { super(code); this.name = 'RemoteError'; this.code = code; }
}
function invalid() { throw new RemoteError('REMOTE_VALUE_INVALID'); }
function text(value, budget) {
  if (typeof value !== 'string') invalid();
  if (value.length > MAX_BYTES || (budget.bytes += new TextEncoder().encode(value).length) > MAX_BYTES)
    throw new RemoteError('REMOTE_BUDGET');
  if (/[\uD800-\uDFFF]/u.test(value)) invalid();
}
function validate(root, input) {
  const budget = { nodes: 0, bytes: 0 };
  function value(schema, data, depth) {
    if (++budget.nodes > 16384 || depth > 32) throw new RemoteError('REMOTE_BUDGET');
    if (schema.anyOf) { if (data === null) return; return value(schema.anyOf[0], data, depth + 1); }
    if (schema.$ref) return value(root.$defs[schema.$ref.slice(8)], data, depth + 1);
    switch (schema.type) {
      case 'boolean': if (typeof data !== 'boolean') invalid(); break;
      case 'integer':
        if (!Number.isSafeInteger(data) || data < schema.minimum || data > schema.maximum) invalid();
        break;
      case 'string': {
        text(data, budget);
        if (schema['x-encoding']) {
          try { validateWireValue(schema, data); } catch { invalid(); }
        }
        let length = 0;
        for (const ignored of data) length++;
        if (schema.enum && !schema.enum.includes(data) || length < (schema.minLength ?? 0)
            || length > (schema.maxLength ?? Infinity)) invalid();
        break;
      }
      case 'array':
        if (Array.isArray(data) && data.length > 16384) throw new RemoteError('REMOTE_BUDGET');
        if (!Array.isArray(data) || data.length < (schema.minItems ?? 0) || data.length > (schema.maxItems ?? Infinity)
            || Reflect.ownKeys(data).length !== data.length + 1) invalid();
        for (let i = 0; i < data.length; i++) {
          const property = Object.getOwnPropertyDescriptor(data, String(i));
          if (!property || !Object.hasOwn(property, 'value')) invalid();
          value(schema.items, property.value, depth + 1);
        }
        break;
      case 'object': {
        if (data === null || typeof data !== 'object' || Array.isArray(data)
            || ![Object.prototype, null].includes(Object.getPrototypeOf(data))) invalid();
        const keys = Reflect.ownKeys(data);
        if (keys.length > 16384) throw new RemoteError('REMOTE_BUDGET');
        for (const key of keys) {
          text(key, budget);
          const property = Object.getOwnPropertyDescriptor(data, key);
          if (!property.enumerable || !Object.hasOwn(property, 'value')) invalid();
          if (schema.properties) {
            if (!Object.hasOwn(schema.properties, key)) invalid();
            value(schema.properties[key], property.value, depth + 1);
          } else {
            value(schema.additionalProperties, property.value, depth + 1);
          }
        }
        for (const key of schema.required ?? []) if (!Object.hasOwn(data, key)) invalid();
        break;
      }
      default: invalid();
    }
  }
  value(root, input, 0);
}
async function body(response) {
  if (Number(response.headers.get('content-length')) > MAX_BYTES) throw new RemoteError('REMOTE_BUDGET');
  const reader = response.body?.getReader();
  if (!reader) throw new RemoteError('REMOTE_RESPONSE_INVALID');
  const chunks = [];
  let size = 0;
  try {
    for (;;) {
      const next = await reader.read();
      if (next.done) break;
      size += next.value.length;
      if (size > MAX_BYTES) { await reader.cancel(); throw new RemoteError('REMOTE_BUDGET'); }
      chunks.push(next.value);
    }
  } finally { reader.releaseLock(); }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { bytes.set(chunk, offset); offset += chunk.length; }
  try { return JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes)); }
  catch { throw new RemoteError('REMOTE_RESPONSE_INVALID'); }
}
export function createClient(fetcher = globalThis.fetch) {
  const client = Object.create(null);
  for (const [name, operation] of Object.entries(contract.operations)) {
    client[name] = async (request, { signal } = {}) => {
      validate(operation.request, request);
      const json = JSON.stringify(request);
      if (new TextEncoder().encode(json).length > MAX_BYTES) throw new RemoteError('REMOTE_BUDGET');
      let response;
      try {
        response = await fetcher(operation.path, { method: operation.method, credentials: 'same-origin',
          headers: { 'Content-Type': 'application/json', 'X-Pixiv-Contract': contractIdentity },
          body: json, signal });
      } catch { throw new RemoteError(signal?.aborted ? 'REMOTE_CANCELLED' : 'REMOTE_TRANSPORT'); }
      let result;
      try { result = await body(response); }
      catch (error) {
        if (error instanceof RemoteError) throw error;
        throw new RemoteError(signal?.aborted ? 'REMOTE_CANCELLED' : 'REMOTE_TRANSPORT');
      }
      if (!response.ok) {
        const codes = new Set(['REMOTE_VALUE_INVALID', 'REMOTE_BUDGET', 'REMOTE_CONTRACT_MISMATCH',
          'REMOTE_UNAVAILABLE', 'REMOTE_FAILED', 'REMOTE_FORBIDDEN', 'REMOTE_UNDECLARED']);
        throw new RemoteError(codes.has(result?.code) ? result.code : 'REMOTE_REJECTED');
      }
      if (response.headers.get('X-Pixiv-Contract') !== contractIdentity) throw new RemoteError('REMOTE_CONTRACT_MISMATCH');
      validate(operation.response, result);
      return result;
    };
  }
  return Object.freeze(client);
}
