'use strict';

window.loginPageTitle = document.querySelector('title[data-pixiv-page-title]')?.textContent || '';
let pageI18n = null;
let activeLoginError = null;
let activeInviteError = null;
let themeToggleMounted = false;

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
  document.title = window.loginPageTitle || lt('page.title', 'Pixiv 批量下载器 — 登录');
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

function mountThemeToggle() {
  const headerActions = document.getElementById('headerActions');
  if (!headerActions || !window.PixivTheme || themeToggleMounted) return;
  PixivTheme.mount({
    mountPoint: headerActions,
    variant: 'login'
  });
  themeToggleMounted = true;
}

function getRedirect() {
  const params = new URLSearchParams(window.location.search);
  const r = params.get('redirect');
  if (r && r.startsWith('/') && !r.startsWith('//')) {
    try {
      const target = new URL(r, window.location.href);
      if (target.origin === window.location.origin) return target.href;
    } catch (_) { /* 无效回跳地址使用默认页面。 */ }
  }
  return '/redirect';
}

function setAuthButtonsDisabled(disabled) {
  document.getElementById('login-btn').disabled = disabled;
  document.getElementById('invite-btn').disabled = disabled;
}

async function doLogin() {
  if (document.getElementById('login-btn').disabled) return;
  const username  = document.getElementById('username').value.trim();
  const password  = document.getElementById('password').value;
  const rememberMe = document.getElementById('remember').checked;

  if (!username || !password) {
    showLoginErrorKey('validation.credentials-required', '请输入用户名和密码');
    return;
  }

  setAuthButtonsDisabled(true);
  showLoginErrorText('');

  try {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({username, password, rememberMe}),
      credentials: 'same-origin'
    });
    const data = await res.json();
    if (!res.ok) {
      if (data && typeof data.code === 'string' && typeof data.error === 'string' && res.status < 500) {
        showLoginErrorText(data.error);
      } else if (res.status >= 500) {
        showLoginErrorKey('error.network', '连接失败，请稍后重试');
      } else {
        showLoginErrorKey('error.login-failed', '登录失败');
      }
      setAuthButtonsDisabled(false);
      return;
    }
    window.location.href = getRedirect();
  } catch (e) {
    if (e.name === 'AbortError') { setAuthButtonsDisabled(false); return; }
    showLoginErrorKey('error.network', '连接失败，请稍后重试');
    setAuthButtonsDisabled(false);
  }
}

async function doInviteRedeem() {
  if (document.getElementById('invite-btn').disabled) return;
  const code = document.getElementById('inviteCodeInput').value.trim();
  if (!code) {
    showInviteErrorKey('validation.invite-required', '请输入邀请码');
    return;
  }
  setAuthButtonsDisabled(true);
  showInviteErrorText('');
  try {
    const res = await fetch('/api/auth/invite-redeem', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({code}),
      credentials: 'same-origin',
    });
    const data = await res.json();
    if (!res.ok) {
      if (data && typeof data.code === 'string' && typeof data.error === 'string' && res.status < 500) {
        showInviteErrorText(data.error);
      } else if (res.status >= 500) {
        showInviteErrorKey('error.network', '连接失败，请稍后重试');
      } else {
        showInviteErrorKey('error.invite-invalid', '邀请码无效');
      }
      setAuthButtonsDisabled(false);
      return;
    }
    // 后端按受邀访客可见落点贡献解析 redirect；仅当响应异常缺失 redirect 时才用公开首页兜底。
    window.location.href = data.redirect || '/';
  } catch (e) {
    if (e.name === 'AbortError') { setAuthButtonsDisabled(false); return; }
    showInviteErrorKey('error.network', '连接失败，请稍后重试');
    setAuthButtonsDisabled(false);
  }
}

document.getElementById('useInviteCode').addEventListener('click', () => {
  showInvitePanel();
  document.getElementById('inviteCodeInput').focus();
});
document.getElementById('backToLogin').addEventListener('click', () => {
  document.getElementById('inviteCard').hidden = true;
  document.getElementById('loginCard').hidden = false;
});

function showInvitePanel() {
  document.getElementById('loginCard').hidden = true;
  document.getElementById('inviteCard').hidden = false;
}

document.addEventListener('keydown', e => {
  if (e.key === 'Enter' && !e.defaultPrevented && !e.target?.closest?.('[data-ui-slot], [data-page-component]')) {
    if (!document.getElementById('inviteCard').hidden) {
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
  PixivActions.bind(document, {click: {doLogin, doInviteRedeem}});
  await initPageI18n();
  mountThemeToggle();
  // 若 URL 带 inviteError，直接展开邀请码面板并提示
  if (new URLSearchParams(window.location.search).has('inviteError')) {
    showInvitePanel();
    showInviteErrorKey('error.invite-expired', '邀请码无效或已失效');
  }
  const invitation = new URLSearchParams(window.location.hash.slice(1)).get('invite');
  if (invitation && /^[A-HJ-NP-Z2-9]{24}$/i.test(invitation)) {
    document.getElementById('inviteCodeInput').value = invitation.toUpperCase();
    history.replaceState(null, '', window.location.pathname + window.location.search);
    showInvitePanel();
  } else await redirectIfLoggedIn();
}
