import { execFileSync, spawn } from 'node:child_process';
import { once } from 'node:events';
import { mkdir, rename, writeFile } from 'node:fs/promises';
import { basename, dirname, resolve } from 'node:path';
import { createInterface } from 'node:readline';
import { pathToFileURL } from 'node:url';
import { setTimeout as sleep } from 'node:timers/promises';
import { MAINTAINER_REQUEST_TIMEOUT_MS, maintainerRequest } from './maintainer-request.mjs';

const API_BASE = 'https://api.github.com';
const DEFAULT_REPOSITORY = 'Sywyar/PixivDownloader';
const MAX_AVATAR_BYTES = 1024 * 1024;
const MAX_API_ATTEMPTS = 3;
const MAX_RETRY_WAIT_MS = 180_000;
const MAX_API_RESPONSE_BYTES = 64 * 1024;
const LOGIN_PATTERN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/;

// GitHub also attributes Claude commits to a regular User account.
const CLAUDE_ACCOUNT_ID = 81847;

function isAutomated(user) {
  return user.id === CLAUDE_ACCOUNT_ID || user.type === 'Bot' || /\[bot\]$/i.test(user.login);
}

export async function attributionCommits(lines) {
  const direct = new Set();
  const collaborators = new Set();
  const commits = [];
  for await (const line of lines) {
    const [oid, author, ...trailers] = line.split('\0');
    if (!/^[a-f0-9]{40}$/.test(oid)) continue;
    let needed = false;
    const authorEmail = author?.toLowerCase();
    if (authorEmail && !direct.has(authorEmail)) {
      direct.add(authorEmail);
      needed = true;
    }
    for (const trailer of trailers) {
      const email = trailer.match(/<([^<>]+)>\s*$/)?.[1]?.trim().toLowerCase();
      if (!email || collaborators.has(email)) continue;
      collaborators.add(email);
      needed = true;
    }
    if (needed) commits.push(oid);
  }
  return commits;
}

async function checkoutAttributionCommits(repoRoot) {
  const shallow = execFileSync('git', ['-C', repoRoot, 'rev-parse', '--is-shallow-repository'], {
    encoding: 'utf8', stdio: ['ignore', 'pipe', 'ignore'],
  });
  if (shallow.trim() !== 'false') throw new Error('Git history is shallow; fetch the complete checkout');
  const git = spawn('git', [
    '-C', repoRoot, 'log', '--reverse',
    '--format=%H%x00%ae%x00%(trailers:key=Co-authored-by,valueonly,unfold=true,separator=%x00)',
  ], { stdio: ['ignore', 'pipe', 'ignore'], windowsHide: true });
  const finished = once(git, 'close').then(([status]) => status, () => null);
  const commits = await attributionCommits(createInterface({ input: git.stdout, crlfDelay: Infinity }));
  if (await finished !== 0 || commits.length === 0) throw new Error('Cannot read Git authorship history');
  return commits;
}

function headers() {
  const result = {
    Accept: 'application/vnd.github+json',
    'User-Agent': 'PixivDownloader-maintainer-catalog',
    'X-GitHub-Api-Version': '2022-11-28',
  };
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
  if (token) result.Authorization = `Bearer ${token}`;
  return result;
}

function safe(value) {
  let text = String(value ?? 'unavailable');
  for (const token of [process.env.GITHUB_TOKEN, process.env.GH_TOKEN]) {
    if (token) text = text.replaceAll(token, '[REDACTED]');
  }
  return text.replace(/[\r\n\t\x00-\x1f\x7f]/g, ' ').slice(0, 1024);
}

export async function fetchGitHub(path, { fetchImpl = maintainerRequest, wait = sleep, now = Date.now, warn = console.warn, body } = {}) {
  const url = new URL(path, API_BASE);
  if (url.origin !== API_BASE || url.username || url.password) throw new Error('GitHub request left api.github.com');
  const requestHeaders = headers();
  if (body !== undefined) {
    requestHeaders['Content-Type'] = 'application/json';
    requestHeaders['Content-Length'] = String(Buffer.byteLength(body));
  }
  let waited = 0;
  for (let attempt = 1; attempt <= MAX_API_ATTEMPTS; attempt++) {
    let response;
    let failure;
    let delay = 1000 * 2 ** (attempt - 1);
    try {
      response = await fetchImpl(url, {
        headers: requestHeaders, method: body === undefined ? 'GET' : 'POST', body,
        redirect: 'error', signal: AbortSignal.timeout(MAINTAINER_REQUEST_TIMEOUT_MS),
      });
    } catch (error) {
      failure = new Error(`GitHub API transport failure: ${url.pathname}; authenticated=${Boolean(requestHeaders.Authorization)}; ${safe(error.cause?.code ?? error.code ?? error.name)}`);
      if (!(error instanceof TypeError) && error.name !== 'TimeoutError') throw failure;
    }
    if (response?.ok) return response;
    if (response) {
      let message;
      try {
        message = JSON.parse((await readBounded(response, MAX_API_RESPONSE_BYTES)).toString('utf8')).message;
      } catch {
        // 诊断正文缺失或过大时，仍保留状态码与限流头；不输出原始响应。
      }
      const remaining = response.headers.get('x-ratelimit-remaining');
      const reset = response.headers.get('x-ratelimit-reset');
      const retryAfter = response.headers.get('retry-after');
      const diagnostic = ['x-github-request-id', 'x-ratelimit-remaining', 'x-ratelimit-reset', 'retry-after']
        .map((name) => `${name}=${safe(response.headers.get(name))}`).join('; ');
      failure = new Error(`GitHub API ${response.status}: ${url.pathname}; authenticated=${Boolean(requestHeaders.Authorization)}; ${diagnostic}; message=${safe(message)}`);
      const rateLimited = response.status === 429 || (response.status === 403
        && (remaining === '0' || retryAfter !== null || /(?:secondary |API )rate limit/i.test(message ?? '')));
      if (!rateLimited && ![500, 502, 503, 504].includes(response.status)) throw failure;
      if (rateLimited) delay = 60_000 * 2 ** (attempt - 1);
      if (retryAfter !== null) {
        const seconds = /^\d+(?:\.\d+)?$/.test(retryAfter) ? Number(retryAfter) * 1000 : Date.parse(retryAfter) - now();
        if (Number.isFinite(seconds)) delay = Math.max(1000, seconds);
      }
      if (remaining === '0' && reset !== null && /^\d+$/.test(reset)) {
        delay = Math.max(delay, Number(reset) * 1000 - now() + 1000);
      }
    }
    if (attempt === MAX_API_ATTEMPTS || waited + delay > MAX_RETRY_WAIT_MS) {
      throw new Error(`${failure.message}; attempts=${attempt}; retry wait budget=${MAX_RETRY_WAIT_MS}ms`);
    }
    warn(`${failure.message}; retry ${attempt + 1}/${MAX_API_ATTEMPTS} after ${delay}ms`);
    await wait(delay);
    waited += delay;
  }
}

async function fetchJson(path, options) {
  return JSON.parse((await readBounded(await fetchGitHub(path, options), MAX_API_RESPONSE_BYTES)).toString('utf8'));
}

const COMMIT_AUTHORS = `query($owner: String!, $name: String!, $oid: GitObjectID!, $cursor: String) {
  repository(owner: $owner, name: $name) {
    object(oid: $oid) {
      ... on Commit {
        authors(first: 100, after: $cursor) {
          nodes { user { databaseId login } }
          pageInfo { hasNextPage endCursor }
        }
      }
    }
  }
}`;

function githubUser(user) {
  if (!Number.isSafeInteger(user?.id) || user.id <= 0 || typeof user.login !== 'string'
      || typeof user.type !== 'string') throw new Error('GitHub returned an invalid account');
  return {
    id: user.id,
    login: user.login,
    type: user.type,
    avatarUrl: user.avatar_url,
    profileUrl: user.html_url,
  };
}

async function fetchMaintainers(repository, repoRoot) {
  const [ownerLogin, repoName] = repository.split('/');
  if (!LOGIN_PATTERN.test(ownerLogin) || !/^[A-Za-z0-9_.-]+$/.test(repoName ?? '')
      || repository !== `${ownerLogin}/${repoName}`) throw new Error('Invalid GitHub repository');
  const commits = await checkoutAttributionCommits(repoRoot);
  const owner = githubUser((await fetchJson(`/repos/${repository}`)).owner);
  const roles = new Map();
  if (owner.type === 'User' && !isAutomated(owner)) roles.set(owner.id, 'author-core');
  for (const oid of commits) {
    let cursor = null;
    do {
      const { data, errors } = await fetchJson('/graphql', { body: JSON.stringify({
        query: COMMIT_AUTHORS, variables: { owner: ownerLogin, name: repoName, oid, cursor },
      }) });
      if (errors?.length) {
        const types = errors.map(({ type }) => type).filter(Boolean).join(', ');
        throw new Error(`GitHub GraphQL author lookup failed for ${oid}: ${safe(types || 'unknown error')}`);
      }
      const authors = data?.repository?.object?.authors;
      if (!Array.isArray(authors?.nodes) || typeof authors.pageInfo?.hasNextPage !== 'boolean') {
        throw new Error(`GitHub did not return commit authors for ${oid}`);
      }
      for (const [index, actor] of authors.nodes.entries()) {
        if (!actor || !Object.hasOwn(actor, 'user')) throw new Error(`GitHub returned an invalid commit actor for ${oid}`);
        if (actor.user === null) continue;
        const { databaseId: id, login } = actor.user;
        if (!Number.isSafeInteger(id) || id <= 0 || typeof login !== 'string') {
          throw new Error(`GitHub returned an invalid commit author for ${oid}`);
        }
        if (isAutomated({ id, login }) || id === owner.id) continue;
        if (cursor === null && index === 0) roles.set(id, 'commit-contributor');
        else if (!roles.has(id)) roles.set(id, 'commit-collaborator');
      }
      if (!authors.pageInfo.hasNextPage) break;
      const next = authors.pageInfo.endCursor;
      if (typeof next !== 'string' || !next || next === cursor) {
        throw new Error(`GitHub returned an invalid author cursor for ${oid}`);
      }
      cursor = next;
    } while (true);
  }

  const maintainers = [];
  for (const [id, role] of roles) {
    const user = id === owner.id ? owner : githubUser(await fetchJson(`/user/${id}`));
    if (user.id !== id) throw new Error('GitHub account ID does not match the commit author');
    if (user.type === 'User' && !isAutomated(user)) maintainers.push({ ...user, role });
  }
  if (maintainers.length === 0) throw new Error('No eligible GitHub accounts found in the commit history');
  return maintainers;
}

async function materializeAvatar(maintainer) {
  const url = new URL(maintainer.avatarUrl);
  if (url.protocol !== 'https:' || url.hostname !== 'avatars.githubusercontent.com'
      || url.username || url.password || url.port) {
    throw new Error(`Avatar URL is not approved: ${maintainer.login}`);
  }
  url.searchParams.set('s', '128');
  const response = await maintainerRequest(url, {
    headers: { Accept: 'image/*', 'User-Agent': 'PixivDownloader-maintainer-catalog' },
  });
  const mediaType = response.headers.get('content-type')?.split(';', 1)[0]?.trim().toLowerCase() ?? '';
  if (!response.ok || !mediaType.startsWith('image/')) {
    await response.body?.cancel().catch(() => {});
    throw new Error(!response.ok ? `Avatar download ${response.status}: ${maintainer.login}`
      : `Avatar is not an image: ${maintainer.login}`);
  }
  const bytes = await readBounded(response, MAX_AVATAR_BYTES);
  if (bytes.length === 0) throw new Error(`Avatar is empty: ${maintainer.login}`);
  return {
    id: maintainer.id,
    login: maintainer.login,
    role: maintainer.role,
    avatarUrl: maintainer.avatarUrl,
    profileUrl: maintainer.profileUrl,
    avatarMediaType: mediaType,
    avatarBase64: bytes.toString('base64'),
  };
}

export async function readBounded(response, maximumBytes) {
  const reader = response.body?.getReader();
  try {
    if (!Number.isSafeInteger(maximumBytes) || maximumBytes <= 0) throw new Error('Maximum response size is invalid');
    const declared = Number(response.headers.get('content-length'));
    if (Number.isFinite(declared) && declared > maximumBytes) throw new Error('Response is too large');
    if (!reader) throw new Error('Response body is missing');
    const chunks = [];
    let total = 0;
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      total += value.byteLength;
      if (total > maximumBytes) throw new Error('Response is too large');
      chunks.push(Buffer.from(value));
    }
    return Buffer.concat(chunks, total);
  } finally {
    await reader?.cancel().catch(() => {});
    reader?.releaseLock();
  }
}

function option(name, fallback) {
  const index = process.argv.indexOf(name);
  return index < 0 ? fallback : process.argv[index + 1];
}

async function main() {
  const repository = option('--repository', DEFAULT_REPOSITORY);
  const repoRoot = resolve(option('--repo-root', process.cwd()));
  const output = resolve(option('--output', 'pixivdownload-app/target/classes/pixivdownload/maintainers.json'));
  let catalog = { maintainers: [], unavailable: true };
  if (process.argv.includes('--offline=true')) {
    console.warn('Maintainer catalog unavailable: Maven offline mode; continuing without the list.');
  } else if (!headers().Authorization) {
    console.warn('Maintainer catalog unavailable: GitHub GraphQL requires GITHUB_TOKEN or GH_TOKEN.');
  } else {
    try {
      const selected = await fetchMaintainers(repository, repoRoot);
      const maintainers = [];
      for (const person of selected) maintainers.push(await materializeAvatar(person));
      catalog = { maintainers };
    } catch (error) {
      console.warn(`Maintainer catalog unavailable; continuing without the list: ${safe(error.message)}`);
    }
  }

  await mkdir(dirname(output), { recursive: true });
  const temporary = `${output}.${process.pid}.tmp`;
  await writeFile(temporary, `${JSON.stringify(catalog, null, 2)}\n`, 'utf8');
  await rename(temporary, output);
  process.stdout.write(`Generated ${catalog.unavailable ? 'unavailable catalog' : `${catalog.maintainers.length} maintainers`} in ${basename(output)}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
