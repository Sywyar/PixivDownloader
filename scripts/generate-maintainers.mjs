import { execFileSync } from 'node:child_process';
import { readFile, mkdir, rename, writeFile } from 'node:fs/promises';
import { basename, dirname, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const API_BASE = 'https://api.github.com';
const DEFAULT_REPOSITORY = 'Sywyar/PixivDownloader';
const MAX_AVATAR_BYTES = 1024 * 1024;
const LOGIN_PATTERN = /^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$/;
const ROLES = new Set(['author-core', 'commit-collaborator']);

function isBot(user) {
  return user?.type === 'Bot' || /\[bot\]$/i.test(user?.login ?? '');
}

export function gitContributorIdentities(log) {
  const identities = new Set();
  for (const line of log.split(/\r?\n/)) {
    const match = line.trim().match(/^(.+?)\s*<([^>]+)>$/);
    if (!match) continue;
    identities.add(match[1].trim().toLowerCase());
    const noreply = match[2].match(/^(?:\d+\+)?([^@]+)@users\.noreply\.github\.com$/i);
    if (noreply) identities.add(noreply[1].toLowerCase());
  }
  return identities;
}

export async function selectMaintainers({ owner, contributors, identities, allowlist, loadUser }) {
  const candidatesById = new Map();
  const candidatesByLogin = new Map();
  for (const user of [owner, ...contributors]) {
    if (!user || isBot(user)) continue;
    candidatesById.set(user.id, user);
    candidatesByLogin.set(user.login.toLowerCase(), user);
  }

  const selected = [];
  for (const allowed of allowlist) {
    let user = candidatesById.get(allowed.id) ?? candidatesByLogin.get(allowed.login.toLowerCase());
    if (!user && identities.has(allowed.login.toLowerCase())) user = await loadUser(allowed.login);
    if (!user || isBot(user) || user.id !== allowed.id
        || user.login.toLowerCase() !== allowed.login.toLowerCase()) continue;
    selected.push({ ...user, role: allowed.role });
  }
  return selected;
}

function headers(withToken = true) {
  const result = {
    Accept: 'application/vnd.github+json',
    'User-Agent': 'PixivDownloader-maintainer-catalog',
    'X-GitHub-Api-Version': '2022-11-28',
  };
  const token = process.env.GITHUB_TOKEN || process.env.GH_TOKEN;
  if (withToken && token) result.Authorization = `Bearer ${token}`;
  return result;
}

async function fetchJson(path) {
  const response = await fetch(`${API_BASE}${path}`, { headers: headers() });
  if (!response.ok) throw new Error(`GitHub API ${response.status}: ${path}`);
  return response.json();
}

async function fetchContributors(repository) {
  const contributors = [];
  let url = `${API_BASE}/repos/${repository}/contributors?per_page=100&anon=false`;
  while (url) {
    const response = await fetch(url, { headers: headers() });
    if (!response.ok) throw new Error(`GitHub API ${response.status}: contributors`);
    contributors.push(...await response.json());
    const next = response.headers.get('link')?.match(/<([^>]+)>; rel="next"/)?.[1];
    if (next) {
      const parsed = new URL(next);
      if (parsed.origin !== API_BASE) throw new Error('GitHub pagination left api.github.com');
      url = parsed.href;
    } else {
      url = '';
    }
  }
  return contributors;
}

function githubUser(user) {
  return {
    id: user.id,
    login: user.login,
    type: user.type,
    avatarUrl: user.avatar_url,
    profileUrl: user.html_url,
  };
}

async function materializeAvatar(maintainer) {
  const url = new URL(maintainer.avatarUrl);
  if (url.protocol !== 'https:' || url.hostname !== 'avatars.githubusercontent.com'
      || url.username || url.password || url.port) {
    throw new Error(`Avatar URL is not approved: ${maintainer.login}`);
  }
  url.searchParams.set('s', '128');
  const response = await fetch(url, {
    headers: { Accept: 'image/*', 'User-Agent': 'PixivDownloader-maintainer-catalog' },
    redirect: 'error',
  });
  if (!response.ok) throw new Error(`Avatar download ${response.status}: ${maintainer.login}`);
  const mediaType = response.headers.get('content-type')?.split(';', 1)[0]?.trim().toLowerCase() ?? '';
  if (!mediaType.startsWith('image/')) throw new Error(`Avatar is not an image: ${maintainer.login}`);
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
  if (!Number.isSafeInteger(maximumBytes) || maximumBytes <= 0) throw new Error('Maximum response size is invalid');
  const declared = Number(response.headers.get('content-length'));
  if (Number.isFinite(declared) && declared > maximumBytes) throw new Error('Response is too large');
  if (!response.body) throw new Error('Response body is missing');
  const reader = response.body.getReader();
  const chunks = [];
  let total = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > maximumBytes) {
      await reader.cancel();
      throw new Error('Response is too large');
    }
    chunks.push(Buffer.from(value));
  }
  return Buffer.concat(chunks, total);
}

function validateAllowlist(allowlist) {
  if (!Array.isArray(allowlist) || allowlist.length === 0) throw new Error('Maintainer allowlist is empty');
  const ids = new Set();
  const logins = new Set();
  for (const entry of allowlist) {
    const login = typeof entry?.login === 'string' ? entry.login.toLowerCase() : '';
    if (!Number.isSafeInteger(entry?.id) || entry.id <= 0 || !LOGIN_PATTERN.test(login)
        || isBot(entry) || !ROLES.has(entry.role) || ids.has(entry.id) || logins.has(login)) {
      throw new Error('Maintainer allowlist is invalid');
    }
    ids.add(entry.id);
    logins.add(login);
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
  const allowlistPath = resolve(repoRoot, 'scripts/maintainers-allowlist.json');
  const allowlist = JSON.parse(await readFile(allowlistPath, 'utf8')).maintainers;
  validateAllowlist(allowlist);
  const repositoryData = await fetchJson(`/repos/${repository}`);
  const contributors = (await fetchContributors(repository)).map(githubUser);
  const log = execFileSync('git', [
    '-C', repoRoot,
    'log', '--format=%aN <%aE>%n%(trailers:key=Co-authored-by,valueonly,separator=%x0a)',
  ], { encoding: 'utf8' });
  const selected = await selectMaintainers({
    owner: githubUser(repositoryData.owner),
    contributors,
    identities: gitContributorIdentities(log),
    allowlist,
    loadUser: async (login) => githubUser(await fetchJson(`/users/${login}`)),
  });
  if (selected.length !== allowlist.length) {
    throw new Error(`Expected ${allowlist.length} approved maintainers, found ${selected.length}`);
  }

  const catalog = { maintainers: await Promise.all(selected.map(materializeAvatar)) };
  await mkdir(dirname(output), { recursive: true });
  const temporary = `${output}.${process.pid}.tmp`;
  await writeFile(temporary, `${JSON.stringify(catalog, null, 2)}\n`, 'utf8');
  await rename(temporary, output);
  process.stdout.write(`Generated ${catalog.maintainers.length} maintainers in ${basename(output)}\n`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  });
}
