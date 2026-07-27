'use strict';
    // ===================== SSE 管理（共享单连接版） =====================
    function ensureSharedSSE() {
        if (sharedSse) return;
        const src = new EventSource(`/api/sse/download`);
        src.addEventListener('download-status', e => {
            try {
                const data = JSON.parse(e.data);
                if (!data || data.artworkId === undefined || data.artworkId === null) return;
                const aid = Number(data.artworkId);
                if (!sseSubscribed.has(aid)) return; // 仅处理我们关注的作品
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

    function openSSE(artworkId) {
        sseSubscribed.add(Number(artworkId));
        ensureSharedSSE();
    }

    function closeSSE(artworkId) {
        sseSubscribed.delete(Number(artworkId));
        if (sseSubscribed.size === 0) closeSharedSSE();
    }

    function handleSseEvent(artworkId, data) {
        if (data.completed || data.failed || data.cancelled) {
            closeSSE(artworkId);
            for (let i = activeDownloads.length - 1; i >= 0; i--) {
                if (activeDownloads[i].artworkId === artworkId) activeDownloads.splice(i, 1);
            }
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
        const entry = activeDownloads.find(d => d.artworkId === artworkId);
        if (entry) {
            if (data.downloadedCount !== undefined) entry.downloadedCount = data.downloadedCount;
            if (data.totalImages     !== undefined) entry.totalImages     = data.totalImages;
            if (data.progress        !== undefined) entry.progressPercentage = data.progress;
        }
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
        if (immediate) {
            syncActiveIds();
        } else {
            activeUpdateTimer = setTimeout(() => syncActiveIds(), getActiveSyncInterval());
        }
    }

    async function syncActiveIds() {
        try {
            const res = await fetch('/api/download/status/active');
            if (!res.ok) return;
            const { artworkIds } = await res.json();
            const currentIds = new Set(artworkIds.map(Number));

            // 移除不再活跃的条目
            const prevCount = activeDownloads.length;
            activeDownloads.filter(d => !currentIds.has(d.artworkId)).forEach(d => closeSSE(d.artworkId));
            for (let i = activeDownloads.length - 1; i >= 0; i--) {
                if (!currentIds.has(activeDownloads[i].artworkId)) activeDownloads.splice(i, 1);
            }
            // 清理掉已不在活跃列表里的订阅
            for (const id of Array.from(sseSubscribed)) {
                if (!currentIds.has(id)) closeSSE(id);
            }

            // 添加新条目并建立 SSE
            const existingIds = new Set(activeDownloads.map(d => d.artworkId));
            const newIds = [...currentIds].filter(id => !existingIds.has(id));
            const newDetails = await Promise.all(
                newIds.map(id => fetch(`/api/download/status/${id}`).then(r => r.ok ? r.json() : null))
            );
            newDetails.filter(d => d && !d.completed && !d.failed && !d.cancelled).forEach(d => {
                activeDownloads.push(d);
                openSSE(d.artworkId);
            });

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
        } catch (e) { console.error(t('log.active-download-sync-failed', '活跃下载同步失败'), e); }
        scheduleActiveSync();
    }

    function activeDownloadItemHtml(d) {
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
    }

    // ----- 高频刷新防卡死：Vue 响应式接管活跃下载列表（与下载队列同手法）-----
    let activeDownloadsVueMounted = false;
    let activeDownloadsLangTick = null;

    function mountActiveDownloadsVue() {
        if (activeDownloadsVueMounted) return;
        const el = document.getElementById('activeDownloadsList');
        if (!el || !el.isConnected) return;
        PixivVue.ensure().then(function (Vue) {
            if (activeDownloadsVueMounted || !el.isConnected) return;
            const langTick = Vue.ref(0);
            // 单行子组件：自己的渲染 effect，只在该 item 字段变化（或语言 tick 变化）时重渲染。
            const ActiveDownloadRow = {
                props: {
                    item: {type: Object, required: true},
                    langTick: {type: Number, default: 0}
                },
                template: '<div v-html="rowHtml"></div>',
                setup: function (props) {
                    const rowHtml = Vue.computed(function () {
                        void props.langTick;
                        return activeDownloadItemHtml(props.item);
                    });
                    return {rowHtml: rowHtml};
                }
            };
            PixivVue.mountOn(el, {
                components: {ActiveDownloadRow: ActiveDownloadRow},
                template: '<ActiveDownloadRow v-for="d in items" :key="d.artworkId" :item="d" :lang-tick="langTick" />',
                setup: function () {
                    return {
                        items: activeDownloads,   // reactive，filter 已改 in-place splice 保持身份稳定
                        langTick: langTick
                    };
                }
            }).then(function (res) {
                if (res) {
                    activeDownloadsVueMounted = true;
                    activeDownloadsLangTick = langTick;
                }
            });
        });
    }

    // 语言切换时调用：Vue 已接管 → bump langTick 重渲染；未接管 → 退回命令式 renderActiveDownloads。
    function refreshActiveDownloadsLangVue() {
        if (activeDownloadsVueMounted && activeDownloadsLangTick) {
            activeDownloadsLangTick.value++;
        } else {
            renderActiveDownloads();
        }
    }

    function renderActiveDownloads() {
        const container  = document.getElementById('activeDownloadsList');
        const emptyState = document.getElementById('emptyActiveState');

        if (!activeDownloads.length) {
            if (!activeDownloadsVueMounted && container) container.innerHTML = '';
            if (emptyState) emptyState.classList.remove('d-none');
            return;
        }

        if (emptyState) emptyState.classList.add('d-none');
        if (activeDownloadsVueMounted) {
            // Vue 已接管列表 DOM：响应式驱动按行更新，不重建 innerHTML。
            return;
        }
        container.innerHTML = activeDownloads.map(d => activeDownloadItemHtml(d)).join('');
        // 首次命令式渲染后异步挂载 Vue 接管后续高频更新（挂载失败则保持命令式，下次重试）。
        mountActiveDownloadsVue();
    }
