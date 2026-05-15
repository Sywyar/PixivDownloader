'use strict';

let pageI18n = null;
let activeLoginError = null;
let activeInviteError = null;

function interpolate(template, vars) {
  if (!vars) return String(template);
  return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (match, name) => (
    Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : match
  ));
}

function lt(key, fallback, vars) {
  if (pageI18n) {
    return pageI18n.t(key.includes(':') ? key : 'login:' + key, fallback, vars);
  }
  return interpolate(fallback != null ? fallback : key, vars);
}

function setText(id, msg) {
  document.getElementById(id).textContent = msg || '';
}

function showLoginErrorText(msg) {
  activeLoginError = msg ? {text: msg} : null;
  setText('error-msg', msg);
}

function showLoginErrorKey(key, fallback, vars) {
  activeLoginError = {key, fallback, vars};
  setText('error-msg', lt(key, fallback, vars));
}

function showInviteErrorText(msg) {
  activeInviteError = msg ? {text: msg} : null;
  setText('invite-error', msg);
}

function showInviteErrorKey(key, fallback, vars) {
  activeInviteError = {key, fallback, vars};
  setText('invite-error', lt(key, fallback, vars));
}

function renderError(targetId, state) {
  if (!state) {
    setText(targetId, '');
  } else if (state.key) {
    setText(targetId, lt(state.key, state.fallback, state.vars));
  } else {
    setText(targetId, state.text);
  }
}

function syncErrorMessages() {
  renderError('error-msg', activeLoginError);
  renderError('invite-error', activeInviteError);
}

function applyStaticPageTranslations() {
  document.title = lt('page.title', 'Pixiv 批量下载器 — 登录');
  if (pageI18n) pageI18n.apply(document.body);
  syncErrorMessages();
}

async function initPageI18n() {
  if (!window.PixivI18n) return;
  try {
    pageI18n = await PixivI18n.create({namespaces: ['login', 'common']});
    const headerActions = document.getElementById('headerActions');
    if (headerActions && window.PixivLangSwitcher) {
      await PixivLangSwitcher.mount({
        mountPoint: headerActions,
        i18n: pageI18n,
        variant: 'login',
        onChange: function (nextClient) {
          pageI18n = nextClient;
          applyStaticPageTranslations();
        }
      });
    }
    applyStaticPageTranslations();
  } catch (_) {
    // Keep the inline Chinese fallback if i18n cannot be loaded.
  }
}

function getRedirect() {
  const params = new URLSearchParams(window.location.search);
  const r = params.get('redirect');
  if (r && r.startsWith('/') && !r.startsWith('//')) return r;
  return '/pixiv-batch.html';
}

function showError(msg) {
  showLoginErrorText(msg);
}

async function doLogin() {
  const username  = document.getElementById('username').value.trim();
  const password  = document.getElementById('password').value;
  const rememberMe = document.getElementById('remember').checked;

  if (!username || !password) {
    showLoginErrorKey('validation.credentials-required', '请输入用户名和密码');
    return;
  }

  const btn = document.getElementById('login-btn');
  btn.disabled = true;
  showError('');

  try {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, rememberMe }),
      credentials: 'same-origin'
    });
    const data = await res.json();
    if (!res.ok) {
      if (data.error) {
        showLoginErrorText(data.error);
      } else {
        showLoginErrorKey('error.login-failed', '登录失败');
      }
      btn.disabled = false;
      return;
    }
    window.location.href = getRedirect();
  } catch (e) {
    showLoginErrorKey('error.network', '网络错误：{message}', {message: e.message});
    btn.disabled = false;
  }
}

function showInviteError(msg) {
  showInviteErrorText(msg);
}

async function doInviteRedeem() {
  const code = document.getElementById('inviteCodeInput').value.trim();
  if (!code) {
    showInviteErrorKey('validation.invite-required', '请输入邀请码');
    return;
  }
  const btn = document.getElementById('invite-btn');
  btn.disabled = true;
  showInviteError('');
  try {
    const res = await fetch('/api/auth/invite-redeem', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ code }),
      credentials: 'same-origin',
    });
    const data = await res.json();
    if (!res.ok) {
      if (data.error) {
        showInviteErrorText(data.error);
      } else {
        showInviteErrorKey('error.invite-invalid', '邀请码无效');
      }
      btn.disabled = false;
      return;
    }
    window.location.href = data.redirect || '/pixiv-gallery.html?view=all';
  } catch (e) {
    showInviteErrorKey('error.network', '网络错误：{message}', {message: e.message});
    btn.disabled = false;
  }
}

document.getElementById('useInviteCode').addEventListener('click', () => {
  document.getElementById('loginCard').classList.add('hidden');
  document.getElementById('inviteCard').classList.remove('hidden');
  document.getElementById('inviteCodeInput').focus();
});
document.getElementById('backToLogin').addEventListener('click', () => {
  document.getElementById('inviteCard').classList.add('hidden');
  document.getElementById('loginCard').classList.remove('hidden');
});

function showInvitePanel() {
  document.getElementById('loginCard').classList.add('hidden');
  document.getElementById('inviteCard').classList.remove('hidden');
}

document.addEventListener('keydown', e => {
  if (e.key === 'Enter') {
    if (!document.getElementById('inviteCard').classList.contains('hidden')) {
      doInviteRedeem();
    } else {
      doLogin();
    }
  }
});

async function redirectIfLoggedIn() {
  try {
    const res = await fetch('/api/auth/check', { credentials: 'same-origin' });
    const data = await res.json();
    if (data.valid) window.location.href = getRedirect();
  } catch {}
}

async function initLoginPage() {
  await initPageI18n();
  // 若 URL 带 inviteError，直接展开邀请码面板并提示
  if (new URLSearchParams(window.location.search).has('inviteError')) {
    showInvitePanel();
    showInviteErrorKey('error.invite-expired', '邀请码无效或已失效');
  }
  await redirectIfLoggedIn();
}

initLoginPage();
