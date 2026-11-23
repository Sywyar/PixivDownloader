'use strict';
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.pagination = {
    hasMore(data, page, limit, count) {
        if (typeof data.hasMore === 'boolean') return data.hasMore;
        if (typeof data.hasNext === 'boolean') return data.hasNext;
        if (Number(data.totalPages) > 0) return page < Number(data.totalPages);
        return page * limit < Number(data.total || 0) && count > 0;
    },
    continueScan(data, page, limit, count) {
        if (!this.hasMore(data, page, limit, count)) return false;
        const totalPages = Number(data.totalPages) > 0
            ? Number(data.totalPages) : Math.ceil(Number(data.total) / limit);
        if (Number.isFinite(totalPages) && totalPages > 0) return page < totalPages;
        if (page >= 1000) throw new Error(bt('pagination.error.page-limit', '分页数量超出安全上限，未加入不完整结果'));
        return true;
    },
    async fetchSearchRange(acquisition, startPage, endPage, delayMs, fetchRange, isCurrent) {
        if (!Number.isInteger(delayMs) || delayMs <= 0 || delayMs > 2147483647 || startPage === endPage) {
            return fetchRange(startPage, endPage);
        }

        const requestedPages = endPage - startPage + 1;
        let acceptedPages = requestedPages;
        let fetchedPages = 0;
        let lastPage = startPage;
        let total = 0;
        let limitPage = 0;
        const works = new Map();
        for (let page = startPage; page < startPage + acceptedPages; page++) {
            const started = performance.now();
            const data = await fetchRange(page, page);
            if (!isCurrent()) return null;
            total = data.total;
            if (data.limitPage > 0) {
                limitPage = data.limitPage;
                acceptedPages = Math.min(acceptedPages, limitPage);
            }
            if (!data.fetchedPages || data.startPage !== page || data.endPage !== page) break;
            for (const item of data.items) {
                const id = acquisition.queueId(item);
                if (!works.has(id)) works.set(id, item);
            }
            fetchedPages++;
            lastPage = page;
            if (fetchedPages >= acceptedPages || page * acquisition.pageSize >= total) break;
            let remaining;
            while ((remaining = started + delayMs - performance.now()) > 0) {
                await new Promise(resolve => setTimeout(resolve, Math.min(remaining, 1000)));
                if (!isCurrent()) return null;
            }
        }
        return {items: Array.from(works.values()), total, startPage, endPage: lastPage,
            requestedPages, acceptedPages, fetchedPages, limitPage};
    }
};
