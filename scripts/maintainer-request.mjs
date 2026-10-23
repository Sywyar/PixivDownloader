import { execFile } from 'node:child_process';
import { Agent, request } from 'node:https';
import { Readable } from 'node:stream';
import { promisify } from 'node:util';

const execute = promisify(execFile);
export const MAINTAINER_REQUEST_TIMEOUT_MS = 30_000;

async function proxyEnvironment(url, signal) {
  const env = process.env;
  const explicit = env.https_proxy ?? env.HTTPS_PROXY ?? env.all_proxy ?? env.ALL_PROXY;
  const bypass = env.no_proxy ?? env.NO_PROXY;
  if (explicit !== undefined || bypass === '*' || process.platform !== 'win32') {
    return { HTTPS_PROXY: explicit ?? '', NO_PROXY: bypass };
  }
  const childEnv = { ...env, PIXIV_MAINTAINER_REQUEST_URI: url.href };
  delete childEnv.GITHUB_TOKEN;
  delete childEnv.GH_TOKEN;
  try {
    const { stdout } = await execute('powershell.exe', ['-NoLogo', '-NoProfile', '-NonInteractive', '-Command', `
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$uri = [Uri]$env:PIXIV_MAINTAINER_REQUEST_URI
$proxy = [System.Net.WebRequest]::GetSystemWebProxy().GetProxy($uri)
if ($null -ne $proxy -and $proxy.AbsoluteUri -ne $uri.AbsoluteUri) { [Console]::Write($proxy.AbsoluteUri) }
`], { env: childEnv, encoding: 'utf8', windowsHide: true, maxBuffer: 64 * 1024, signal });
    return { HTTPS_PROXY: stdout.trim(), NO_PROXY: bypass };
  } catch {
    signal.throwIfAborted();
    throw Object.assign(new Error('Cannot resolve the system proxy for the maintainer catalog'), { code: 'ERR_SYSTEM_PROXY' });
  }
}

export async function maintainerRequest(input, { headers, signal = AbortSignal.timeout(MAINTAINER_REQUEST_TIMEOUT_MS) } = {}) {
  const [major, minor] = process.versions.node.split('.').map(Number);
  if (major < 24 || (major === 24 && minor < 5)) {
    throw Object.assign(new Error('Maintainer catalog requests require Node.js 24.5 or later'), { code: 'ERR_NODE_VERSION' });
  }
  const url = new URL(input);
  if (url.protocol !== 'https:' || url.username || url.password) throw new Error('Maintainer requests require HTTPS without URL credentials');
  const proxyEnv = await proxyEnvironment(url, signal);
  signal.throwIfAborted();
  let agent;
  try {
    if (proxyEnv.HTTPS_PROXY && !['http:', 'https:'].includes(new URL(proxyEnv.HTTPS_PROXY).protocol)) {
      throw new Error('Unsupported proxy protocol');
    }
    agent = new Agent({ proxyEnv });
  } catch {
    throw Object.assign(new Error('The maintainer proxy configuration is invalid'), { code: 'ERR_MAINTAINER_PROXY_CONFIG' });
  }
  return new Promise((resolve, reject) => {
    const fail = (error) => {
      agent.destroy();
      reject(signal.aborted ? signal.reason : new TypeError('Maintainer request failed', { cause: error }));
    };
    const outgoing = request(url, { headers: { 'Accept-Encoding': 'identity', ...headers }, signal, agent }, (incoming) => {
      incoming.once('close', () => agent.destroy());
      if ([301, 302, 303, 307, 308].includes(incoming.statusCode)) {
        incoming.destroy();
        fail(Object.assign(new Error('Redirects are not allowed'), { code: 'ERR_REDIRECT' }));
        return;
      }
      try {
        const empty = [204, 205, 304].includes(incoming.statusCode);
        const response = new Response(empty ? null : Readable.toWeb(incoming), {
          status: incoming.statusCode, headers: incoming.headers,
        });
        if (empty) incoming.resume();
        resolve(response);
      } catch (error) {
        incoming.destroy();
        fail(error);
      }
    });
    outgoing.once('error', fail);
    outgoing.end();
  });
}
