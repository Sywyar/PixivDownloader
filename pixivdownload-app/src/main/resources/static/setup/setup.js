'use strict';

let pageI18n = null;
let activeStatus = null;
let setupSubmitting = false;
let weakPasswordConfirmationPending = false;
const MIN_PASSWORD_LENGTH = 8;
const RECOMMENDED_PASSWORD_LENGTH = 12;

function st(key, fallback, vars) {
  if (pageI18n) return pageI18n.t(key, fallback, vars);
  if (!vars) return fallback;
  return String(fallback).replace(/\{([a-zA-Z0-9_.-]+)\}/g, (match, name) => (
    Object.prototype.hasOwnProperty.call(vars, name) ? vars[name] : match
  ));
}

function applyStaticPageTranslations() {
  document.title = st('page.title', 'Pixiv 批量下载器 — 初始配置');
  if (pageI18n) pageI18n.apply(document.body);
  syncStatusText();
}

async function initPageI18n() {
  pageI18n = await PixivI18n.create({namespaces: ['setup', 'common']});
  await PixivLangSwitcher.mount({
    mountPoint: document.getElementById('headerActions'),
    i18n: pageI18n,
    variant: 'setup',
    onChange: function (nextClient) {
      pageI18n = nextClient;
      applyStaticPageTranslations();
    }
  });
  PixivTheme.mount({
    mountPoint: document.getElementById('headerActions')
  });
  applyStaticPageTranslations();
}

function syncProxyEnabled() {
  const enabled = document.getElementById('proxy-enabled').checked;
  document.getElementById('proxy-host').disabled = !enabled;
  document.getElementById('proxy-port').disabled = !enabled;
}

function showStatus(msg, tone) {
  const el = document.getElementById('status-msg');
  el.textContent = msg;
  if (tone) {
    el.dataset.tone = tone;
  } else {
    delete el.dataset.tone;
  }
}

function setStatusKey(key, fallback, vars, tone) {
  activeStatus = {key, fallback, vars, tone};
  showStatus(st(key, fallback, vars), tone);
}

function setStatusText(text, tone) {
  activeStatus = {text, tone};
  showStatus(text, tone);
}

function syncStatusText() {
  if (!activeStatus) return;
  if (activeStatus.key) {
    showStatus(st(activeStatus.key, activeStatus.fallback, activeStatus.vars), activeStatus.tone);
  } else {
    showStatus(activeStatus.text, activeStatus.tone);
  }
}

async function submitSetup() {
  if (setupSubmitting) return;
  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;
  const mode     = 'solo';
  const proxyEnabled = document.getElementById('proxy-enabled').checked;
  const proxyHost = document.getElementById('proxy-host').value.trim();
  const proxyPortText = document.getElementById('proxy-port').value.trim();

  if (!username) { setStatusKey('validation.username-required', '请填写用户名', null, 'error'); return; }
  if (password.length < MIN_PASSWORD_LENGTH) { setStatusKey('validation.password-short', '密码长度至少 8 位', null, 'error'); return; }

  let proxyPort = 7890;
  if (proxyEnabled) {
    if (!proxyHost) { setStatusKey('proxy.validation.host-required', '启用代理时请填写代理主机', null, 'error'); return; }
    proxyPort = Number(proxyPortText);
    if (!Number.isInteger(proxyPort) || proxyPort < 1 || proxyPort > 65535) {
      setStatusKey('proxy.validation.port-invalid', '代理端口必须是 1-65535 之间的整数', null, 'error');
      return;
    }
  } else if (proxyPortText && Number.isInteger(Number(proxyPortText))) {
    proxyPort = Number(proxyPortText);
  }

  if (password.length < RECOMMENDED_PASSWORD_LENGTH && !weakPasswordConfirmationPending) {
    weakPasswordConfirmationPending = true;
    setStatusKey('password-warning.message',
        '本软件会保存其他软件的 Cookie 等敏感凭据，建议使用至少 12 位密码。若仍要继续，请再次点击完成配置。',
        null, 'warning');
    return;
  }
  weakPasswordConfirmationPending = false;

  const btn = document.getElementById('submit-btn');
  setupSubmitting = true;
  btn.disabled = true;
  setStatusKey('status.saving', '正在保存配置...', null, 'info');

  try {
    const res = await fetch('/api/setup/init', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username, password, mode,
        proxyEnabled,
        proxyHost: proxyHost || '127.0.0.1',
        proxyPort
      })
    });
    const data = await res.json();
    if (!res.ok) {
      if (data.error) setStatusText(data.error, 'error');
      else setStatusKey('status.failed', '配置失败', null, 'error');
      btn.disabled = false;
      setupSubmitting = false;
      return;
    }

    if (data.warning) {
      setStatusText(`${data.warning} ${st('status.redirecting', '正在跳转...')}`, 'warning');
    } else {
      setStatusKey('status.complete', '配置完成！正在跳转...', null, 'success');
    }
    setTimeout(() => {
      window.location.href = '/login.html';
    }, 800);
  } catch (e) {
    setStatusKey('status.network-error', 'status.network-error', {message: e.message}, 'error');
    btn.disabled = false;
    setupSubmitting = false;
  }
}

function resetWeakPasswordConfirmation() {
  weakPasswordConfirmationPending = false;
}

// 回车提交
document.addEventListener('keydown', e => {
  if (e.key === 'Enter') submitSetup();
});

async function redirectIfComplete() {
  try {
    const res = await fetch('/api/setup/status');
    const data = await res.json();
    if (data.setupComplete) {
      window.location.href = '/login.html';
    }
  } catch {}
}

(async function initSetupPage() {
  document.getElementById('password').addEventListener('input', resetWeakPasswordConfirmation);
  PixivActions.bind(document, {
    click: {submitSetup},
    change: {syncProxyEnabled}
  });
  await initPageI18n();
  await redirectIfComplete();
})();
