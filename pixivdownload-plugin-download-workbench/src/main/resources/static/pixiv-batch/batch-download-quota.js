'use strict';
    /**
     * 流式抓取 JSON：边读边通过 onProgress(已接收, 总字节) 上报进度，
     * 用于小说正文（meta）下载的流式进度条。返回一个与 Response 兼容的轻量对象
     * （含 ok / status / json()）。非 2xx 或浏览器不支持流时回退为原始 Response。
     */
    async function fetchJsonWithProgress(url, opts, onProgress) {
        const res = await fetch(url, opts);
        if (!res.ok || !res.body || typeof res.body.getReader !== 'function') {
            return res;
        }
        const totalHeader = res.headers.get('Content-Length');
        const total = totalHeader ? Number(totalHeader) : 0;
        const reader = res.body.getReader();
        const chunks = [];
        let received = 0;
        for (; ;) {
            const {done, value} = await reader.read();
            if (done) break;
            chunks.push(value);
            received += value.length;
            if (onProgress) onProgress(received, total);
        }
        let len = 0;
        chunks.forEach(c => len += c.length);
        const buf = new Uint8Array(len);
        let off = 0;
        chunks.forEach(c => {
            buf.set(c, off);
            off += c.length;
        });
        const text = new TextDecoder('utf-8').decode(buf);
        return {
            ok: res.ok,
            status: res.status,
            json: () => Promise.resolve(JSON.parse(text))
        };
    }
    const WINDOWS_RESERVED_FILE_NAMES = new Set([
        'CON', 'PRN', 'AUX', 'NUL',
        'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9',
        'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'
    ]);

    const STATUS_TIMEOUT_MS = 300000;

    /* ============================================================
       配额 & 压缩包
    ============================================================ */
    let quotaInfo = {enabled: false, adminMode: false, artworksUsed: 0, maxArtworks: 50, resetSeconds: 0};
    let archiveCountdownTimer = null;
    let archivePollTimer = null;
    let quotaResetTimer = null;
    let quotaExceededHandled = false;

    async function initQuota() {
        try {
            const res = await fetch(BASE + '/api/quota/init', {method: 'POST', credentials: 'same-origin'});
            if (!res.ok) return;
            const data = await res.json();
            quotaInfo.adminMode = !!data.adminMode;
            if (quotaInfo.adminMode) {
                quotaInfo.enabled = false;
                hideQuotaBar();
                return;
            }
            if (!data.enabled) {
                quotaInfo.enabled = false;
                hideQuotaBar();
                return;
            }
            quotaInfo = {
                enabled: true, adminMode: false, artworksUsed: data.artworksUsed, maxArtworks: data.maxArtworks,
                resetSeconds: data.resetSeconds
            };
            renderQuotaBar();
            startQuotaResetCountdown();
            // 恢复已有的压缩包链接
            if (data.archive && data.archive.token) {
                showArchiveCard(data.archive.token, data.archive.expireSeconds, data.archive.status === 'ready');
            }
        } catch {
        }
    }

    function hideQuotaBar() {
        document.getElementById('quota-bar').style.display = 'none';
        document.getElementById('qb-reset').textContent = '';
        clearInterval(quotaResetTimer);
    }

    function renderQuotaBar() {
        if (!quotaInfo.enabled) {
            hideQuotaBar();
            return;
        }
        document.getElementById('quota-bar').style.display = 'block';
        document.getElementById('qb-used').textContent = quotaInfo.artworksUsed;
        document.getElementById('qb-max').textContent = quotaInfo.maxArtworks;
        const pct = Math.min(100, Math.round(quotaInfo.artworksUsed / quotaInfo.maxArtworks * 100));
        const fill = document.getElementById('qb-fill');
        fill.style.width = pct + '%';
        fill.className = 'qb-fill' + (pct >= 90 ? ' danger' : pct >= 70 ? ' warn' : '');
        document.getElementById('qb-reset').textContent =
            quotaInfo.resetSeconds > 0
                ? bt('status.quota-reset', '配额重置剩余：{time}', {time: formatSeconds(quotaInfo.resetSeconds)})
                : '';
    }

    function startQuotaResetCountdown() {
        clearInterval(quotaResetTimer);
        if (quotaInfo.resetSeconds <= 0) return;
        quotaResetTimer = setInterval(() => {
            if (quotaInfo.resetSeconds > 0) quotaInfo.resetSeconds--;
            renderQuotaBar();
            if (quotaInfo.resetSeconds <= 0) clearInterval(quotaResetTimer);
        }, 1000);
    }

    function handleQuotaExceeded(data) {
        quotaInfo.artworksUsed = data.artworksUsed || quotaInfo.artworksUsed;
        renderQuotaBar();

        // 标记所有未开始/等待中的队列项为失败
        state.queue.forEach(q => {
            if (['pending', 'idle', 'paused'].includes(q.status)) {
                q.status = 'failed';
                q.statusMessageKey = null;
                q.lastMessage = bt('queue.message.failed-quota', '失败 - 达到限额');
                q.endTime = q.endTime || new Date().toISOString();
            }
        });
        state.stopRequested = true;
        state.isRunning = false;
        updateStats();
        saveQueue();
        renderQueue();
        updateButtonsState();
        setStatus(bt('status.archive-limit', '已达到下载限额'), 'error');

        const token = data.archiveToken;
        const expireSeconds = data.archiveExpireSeconds || 3600;
        showArchiveCard(token, expireSeconds, false);
    }

    function showArchiveCard(token, expireSeconds, ready, title = bt('status.archive-limit', '已达到下载限额')) {
        clearInterval(archiveCountdownTimer);
        clearInterval(archivePollTimer);

        document.getElementById('archive-card').style.display = 'block';
        document.getElementById('ac-title').textContent = title;
        document.getElementById('ac-expired-area').style.display = 'none';
        document.getElementById('ac-dl-area').style.display = 'none';

        if (ready) {
            activateArchiveDownload(token, expireSeconds);
        } else {
            document.getElementById('ac-status').textContent = bt('status.archive-packing', '正在打包已下载文件，请稍候...');
            pollArchiveReady(token, expireSeconds);
        }
    }

    function pollArchiveReady(token, expireSeconds) {
        archivePollTimer = setInterval(async () => {
            try {
                const res = await fetch(BASE + '/api/archive/status/' + token);
                const data = await res.json();
                if (data.status === 'ready') {
                    clearInterval(archivePollTimer);
                    activateArchiveDownload(token, data.expireSeconds || expireSeconds);
                } else if (data.status === 'expired') {
                    clearInterval(archivePollTimer);
                    showArchiveExpired();
                } else if (data.status === 'empty') {
                    clearInterval(archivePollTimer);
                    document.getElementById('ac-status').textContent = bt(
                        'status.archive-empty',
                        '暂无可打包文件（当前下载仍在进行中，完成后自动包含）'
                    );
                }
            } catch {
            }
        }, 2000);
    }

    function activateArchiveDownload(token, expireSeconds) {
        document.getElementById('ac-status').textContent = bt(
            'status.archive-ready',
            '压缩包已就绪，请在有效期内下载：'
        );
        const dlArea = document.getElementById('ac-dl-area');
        dlArea.style.display = 'block';
        const btn = document.getElementById('ac-dl-btn');
        btn.href = BASE + '/api/archive/download/' + token;
        btn.removeAttribute('disabled');

        let remaining = Math.max(0, parseInt(expireSeconds));
        document.getElementById('ac-countdown').textContent = bt(
            'status.archive-validity',
            '下载链接有效期：{time}',
            {time: formatSeconds(remaining)}
        );
        archiveCountdownTimer = setInterval(() => {
            remaining--;
            if (remaining <= 0) {
                clearInterval(archiveCountdownTimer);
                showArchiveExpired();
            } else {
                document.getElementById('ac-countdown').textContent = bt(
                    'status.archive-validity',
                    '下载链接有效期：{time}',
                    {time: formatSeconds(remaining)}
                );
            }
        }, 1000);
    }

    function showArchiveExpired() {
        document.getElementById('ac-dl-area').style.display = 'none';
        document.getElementById('ac-expired-area').style.display = 'block';
        document.getElementById('ac-status').textContent = '';
    }

    async function autoPackAfterQueue() {
        try {
            const res = await fetch(BASE + '/api/quota/pack', {
                method: 'POST',
                credentials: 'same-origin'
            });
            if (res.status === 204) return; // 无文件可打包（可能已被打包或源文件已删除）
            if (!res.ok) return;
            const data = await res.json();
            if (data.archiveToken) {
                setStatus(bt('status.batch-finished-packing', '批量下载结束，正在打包文件...'), 'info');
                showArchiveCard(
                    data.archiveToken,
                    data.archiveExpireSeconds || 3600,
                    false,
                    bt('status.download-complete-packing', '下载完成，正在打包')
                );
            }
        } catch {
        }
    }

    async function triggerAdminPack() {
        const ids = state.queue
            .filter(q => q.status === 'completed')
            .map(q => Number(q.id))
            .filter(Number.isFinite);

        if (ids.length === 0) {
            setStatus(bt('status.no-completed-to-pack', '队列中暂无已完成的作品可供打包'), 'warning');
            return;
        }

        const btn = document.getElementById('admin-pack-btn');
        btn.disabled = true;

        try {
            const res = await fetch(BASE + '/api/archive/pack-artworks', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({artworkIds: ids})
            });

            if (res.status === 401) {
                isAdmin = false;
                updateAuthButtons();
                updateAdminPackButton();
                setStatus(bt('status.login-expired', '登录状态已失效，请重新登录'), 'error');
                return;
            }

            if (res.status === 204) {
                setStatus(bt('status.pack-folder-missing', '数据库中未找到对应文件夹，可能已被移动或删除'), 'warning');
                return;
            }

            let data = null;
            try {
                data = await res.json();
            } catch {
            }

            if (!res.ok) {
                setStatus(
                    (data && data.error)
                        ? data.error
                        : bt('status.pack-failed-http', '打包失败：HTTP {code}', {code: res.status}),
                    'error'
                );
                return;
            }

            setStatus(
                bt('status.pack-request-submitted', '已提交打包请求（{count} 个作品），正在生成压缩包...', {count: ids.length}),
                'info'
            );
            showArchiveCard(
                data.archiveToken,
                data.archiveExpireSeconds || 3600,
                false,
                bt('status.admin-packing', '管理员打包中（{count} 个作品）', {count: ids.length})
            );
        } catch (e) {
            setStatus(bt('status.pack-request-failed', '打包请求失败：{message}', {message: e.message}), 'error');
        } finally {
            updateAdminPackButton();
        }
    }
