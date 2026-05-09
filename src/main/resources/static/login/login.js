'use strict';

function getRedirect() {
  const params = new URLSearchParams(window.location.search);
  const r = params.get('redirect');
  if (r && r.startsWith('/') && !r.startsWith('//')) return r;
  return '/pixiv-batch.html';
}

function showError(msg) {
  document.getElementById('error-msg').textContent = msg;
}

async function doLogin() {
  const username  = document.getElementById('username').value.trim();
  const password  = document.getElementById('password').value;
  const rememberMe = document.getElementById('remember').checked;

  if (!username || !password) { showError('请输入用户名和密码'); return; }

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
      showError(data.error || '登录失败');
      btn.disabled = false;
      return;
    }
    window.location.href = getRedirect();
  } catch (e) {
    showError('网络错误：' + e.message);
    btn.disabled = false;
  }
}

function showInviteError(msg) {
  document.getElementById('invite-error').textContent = msg;
}

async function doInviteRedeem() {
  const code = document.getElementById('inviteCodeInput').value.trim();
  if (!code) { showInviteError('请输入邀请码'); return; }
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
      showInviteError(data.error || '邀请码无效');
      btn.disabled = false;
      return;
    }
    window.location.href = data.redirect || '/pixiv-gallery.html';
  } catch (e) {
    showInviteError('网络错误：' + e.message);
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

// 若 URL 带 inviteError，直接展开邀请码面板并提示
if (new URLSearchParams(window.location.search).has('inviteError')) {
  document.getElementById('loginCard').classList.add('hidden');
  document.getElementById('inviteCard').classList.remove('hidden');
  showInviteError('邀请码无效或已失效');
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

// 若已登录则直接跳转
(async () => {
  try {
    const res = await fetch('/api/auth/check', { credentials: 'same-origin' });
    const data = await res.json();
    if (data.valid) window.location.href = getRedirect();
  } catch {}
})();

// 加载 i18n（只针对新增的邀请码区块；原始管理员登录文本保持现状）
(async () => {
  if (!window.PixivI18n) return;
  try {
    const i18n = await PixivI18n.create({ namespaces: ['invite', 'common'] });
    if (i18n.apply) i18n.apply();
  } catch (_) { /* 忽略 i18n 失败，回退到默认文案 */ }
})();
