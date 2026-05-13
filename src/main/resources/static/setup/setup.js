'use strict';

let pageI18n = null;
let activeStatus = null;

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

function selectMode(mode) {
  document.querySelector(`input[value="${mode}"]`).checked = true;
  document.getElementById('opt-solo').classList.toggle('selected', mode === 'solo');
  document.getElementById('opt-multi').classList.toggle('selected', mode === 'multi');
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
  const username = document.getElementById('username').value.trim();
  const password = document.getElementById('password').value;
  const confirm  = document.getElementById('confirm-password').value;
  const mode     = document.querySelector('input[name="mode"]:checked').value;

  if (!username) { setStatusKey('validation.username-required', '请填写用户名', null, 'error'); return; }
  if (password.length < 6) { setStatusKey('validation.password-short', '密码长度至少 6 位', null, 'error'); return; }
  if (password !== confirm) { setStatusKey('validation.password-mismatch', '两次密码输入不一致', null, 'error'); return; }

  const btn = document.getElementById('submit-btn');
  btn.disabled = true;
  setStatusKey('status.saving', '正在保存配置...', null, 'info');

  try {
    const res = await fetch('/api/setup/init', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password, mode })
    });
    const data = await res.json();
    if (!res.ok) {
      if (data.error) setStatusText(data.error, 'error');
      else setStatusKey('status.failed', '配置失败', null, 'error');
      btn.disabled = false;
      return;
    }

    setStatusKey('status.complete', '配置完成！正在跳转...', null, 'success');
    setTimeout(() => {
      window.location.href = mode === 'solo' ? '/login.html' : '/pixiv-batch.html';
    }, 800);
  } catch (e) {
    setStatusKey('status.network-error', '网络错误：{message}', {message: e.message}, 'error');
    btn.disabled = false;
  }
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
      window.location.href = data.mode === 'solo' ? '/login.html' : '/pixiv-batch.html';
    }
  } catch {}
}

(async function initSetupPage() {
  await initPageI18n();
  await redirectIfComplete();
})();
