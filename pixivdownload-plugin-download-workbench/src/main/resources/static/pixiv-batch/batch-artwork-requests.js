'use strict';
    function assertProcessInvocation(invocation) {
        if (invocation) invocation.assertActive();
    }

    function processSignal(invocation) {
        return invocation ? invocation.signal : undefined;
    }

    async function getArtworkMeta(artworkId, invocation) {
        assertProcessInvocation(invocation);
        const data = await apiGet(`/api/pixiv/artwork/${artworkId}/meta`, {
            signal: processSignal(invocation)
        });
        assertProcessInvocation(invocation);
        if (data.error) throw new Error(data.error);
        return data;
    }

    async function getArtworkPages(artworkId, invocation) {
        assertProcessInvocation(invocation);
        const data = await apiGet(`/api/pixiv/artwork/${artworkId}/pages`, {
            signal: processSignal(invocation)
        });
        assertProcessInvocation(invocation);
        if (data.error) throw new Error(data.error);
        return data.urls || [];
    }

    async function getUgoiraMeta(artworkId, invocation) {
        assertProcessInvocation(invocation);
        const data = await apiGet(`/api/pixiv/artwork/${artworkId}/ugoira`, {
            signal: processSignal(invocation)
        });
        assertProcessInvocation(invocation);
        if (data.error) throw new Error(data.error);
        return data;
    }

    async function checkDownloaded(artworkId, invocation) {
        assertProcessInvocation(invocation);
        const query = state.settings.verifyHistoryFiles ? '?verifyFiles=true' : '';
        const res = await fetch(`${BASE}/api/downloaded/${artworkId}${query}`, {
            signal: processSignal(invocation)
        });
        assertProcessInvocation(invocation);
        if (res.status === 400) return null;
        if (res.status !== 200) throw new Error(bt('queue.message.failed-status-error', '失败 — 状态查询异常'));
        const data = await res.json();
        assertProcessInvocation(invocation);
        if (!data.artworkId) throw new Error(bt('queue.message.failed-status-error', '失败 — 状态查询异常'));
        return data;
    }

    // 补齐旧记录缺失的元数据；文件核验不会新增数据库记录。
    // 用前端拉到的 Pixiv 元数据补齐缺失字段。后端是幂等的：DB 已有完整记录直接返回原记录。
    async function recoverArtworkMetadata(artworkId, meta, invocation) {
        assertProcessInvocation(invocation);
        try {
            const res = await fetch(`${BASE}/api/downloaded/${artworkId}/recover-metadata`, {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                signal: processSignal(invocation),
                body: JSON.stringify(meta)
            });
            assertProcessInvocation(invocation);
            if (res.status === 200) {
                const recovered = await res.json();
                assertProcessInvocation(invocation);
                return recovered;
            }
        } catch (e) {
            assertProcessInvocation(invocation);
            // best-effort：元数据补齐失败不改变已有下载记录
            console.warn(bt('download.log.recover-metadata-failed', '恢复作品元数据失败: artworkId={id}', {id: artworkId}), e);
        }
        return null;
    }

    async function getDownloadStatus(artworkId, invocation) {
        assertProcessInvocation(invocation);
        const res = await fetch(`${BASE}/api/download/status/${artworkId}`, {
            signal: processSignal(invocation)
        });
        assertProcessInvocation(invocation);
        const data = await res.json();
        assertProcessInvocation(invocation);
        return data;
    }
