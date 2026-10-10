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
    }
};
