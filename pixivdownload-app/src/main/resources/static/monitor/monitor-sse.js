'use strict';
    // ===================== SSE 管理（共享单连接版） =====================
    function ensureSharedSSE() {
        if (sharedSse || pageSuspended || activeDownloads.length === 0) return;
        const src = new EventSource(`/api/sse/download`);
        src.addEventListener('download-status', e => {
            if (sharedSse !== src) return;
            try {
                const data = JSON.parse(e.data);
                if (!data || data.artworkId === undefined || data.artworkId === null) return;
                const aid = Number(data.artworkId);
                handleSseEvent(aid, data);
            } catch {}
        });
        // EventSource 自动重连
        sharedSse = src;
    }

    function closeSharedSSE() {
        if (sharedSse) {
            try { sharedSse.close(); } catch {}
            sharedSse = null;
        }
    }

    function handleSseEvent(artworkId, data) {
        const entry = activeDownloads.find(d => d.artworkId === artworkId);
        if (!entry) return;
        if (data.completed || data.failed || data.cancelled) {
            activeDownloads = activeDownloads.filter(d => d.artworkId !== artworkId);
            if (activeDownloads.length === 0) closeSharedSSE();
            lastCompletionTime = Date.now();
            scheduleActiveSync(); // 切换到快速轮询
            document.getElementById('activeCount').textContent     = activeDownloads.length;
            document.getElementById('activeDownloads').textContent = activeDownloads.length;
            renderActiveDownloads();
            updateLastUpdatedTime();
            loadStatistics();
            currentPage = 1;
            loadAllDataForChart();
            return;
        }
        if (data.downloadedCount !== undefined) entry.downloadedCount = data.downloadedCount;
        if (data.totalImages     !== undefined) entry.totalImages     = data.totalImages;
        if (data.progress        !== undefined) entry.progressPercentage = data.progress;
        document.getElementById('activeCount').textContent     = activeDownloads.length;
        document.getElementById('activeDownloads').textContent = activeDownloads.length;
        renderActiveDownloads();
        updateLastUpdatedTime();
    }

    // ===================== 活跃下载同步 =====================
    function getActiveSyncInterval() {
        if (document.hidden) return 15000;
        if (Date.now() < foregroundBurstUntil) return 1000;
        if (activeDownloads.length > 0) return 1000;
        if (Date.now() - lastCompletionTime < 10000) return 1000;
        return 8000;
    }

    function scheduleActiveSync(immediate) {
        clearTimeout(activeUpdateTimer);
        activeUpdateTimer = null;
        if (pageSuspended || activeSyncController) return;
        if (immediate) {
            syncActiveIds();
        } else {
            activeUpdateTimer = setTimeout(() => syncActiveIds(), getActiveSyncInterval());
        }
    }

    async function syncActiveIds() {
        if (pageSuspended || activeSyncController) return;
        clearTimeout(activeUpdateTimer);
        activeUpdateTimer = null;
        const controller = activeSyncController = new AbortController();
        try {
            const res = await fetch('/api/download/status/active', {signal: controller.signal});
            if (!res.ok) return;
            const { artworkIds } = await res.json();
            if (activeSyncController !== controller) return;
            const currentIds = new Set(artworkIds.map(Number));

            // 移除不再活跃的条目
            const prevCount = activeDownloads.length;
            activeDownloads = activeDownloads.filter(d => currentIds.has(d.artworkId));
            if (activeDownloads.length === 0) closeSharedSSE();

            // 添加新条目并建立 SSE
            const existingIds = new Set(activeDownloads.map(d => d.artworkId));
            const newIds = [...currentIds].filter(id => !existingIds.has(id));
            const newDetails = await Promise.all(
                newIds.map(id => fetch(`/api/download/status/${id}`, {signal: controller.signal})
                    .then(r => r.ok ? r.json() : null))
            );
            if (activeSyncController !== controller) return;
            newDetails.filter(d => d && !d.completed && !d.failed && !d.cancelled).forEach(d => {
                activeDownloads.push(d);
            });
            ensureSharedSSE();

            document.getElementById('activeCount').textContent     = activeDownloads.length;
            document.getElementById('activeDownloads').textContent = activeDownloads.length;
            renderActiveDownloads();
            updateLastUpdatedTime();

            if (prevCount > 0 && activeDownloads.length < prevCount) {
                lastCompletionTime = Date.now();
                loadStatistics();
                currentPage = 1;
                loadAllDataForChart();
            }
        } catch (e) {
            if (!controller.signal.aborted) console.error(t('log.active-download-sync-failed', '活跃下载同步失败'), e);
        } finally {
            controller.abort();
            if (activeSyncController === controller) {
                activeSyncController = null;
                scheduleActiveSync();
            }
        }
    }

    function renderActiveDownloads() {
        const container  = document.getElementById('activeDownloadsList');
        const emptyState = document.getElementById('emptyActiveState');

        if (!activeDownloads.length) {
            container.innerHTML = '';
            emptyState.classList.remove('d-none');
            return;
        }

        emptyState.classList.add('d-none');
        container.innerHTML = activeDownloads.map(d => {
            const progress = d.progressPercentage || 0;
            return `
            <div class="active-download-item">
                <div class="active-download-header">
                    <div>
                        <div class="download-artwork-title" title="${escapeHtml(d.title)}">${escapeHtml(d.title || '—')}</div>
                        <div class="download-artwork-id">#${d.artworkId}</div>
                        <div class="active-download-source">pixiv.net</div>
                    </div>
                </div>
                <div>
                    <div class="progress-info">
                        <span class="progress-count">${d.downloadedCount || 0} / ${d.totalImages || 0}</span>
                        <span class="progress-percentage">${progress.toFixed(1)}%</span>
                    </div>
                    <div class="active-download-progress">
                        <div class="active-download-progress-bar" style="width:${progress}%"></div>
                    </div>
                </div>
                <div class="download-status">
                    <div class="status-indicator"></div>
                    <span>${escapeHtml(t('status.downloading', 'DOWNLOADING...'))}</span>
                </div>
            </div>`;
        }).join('');
    }
