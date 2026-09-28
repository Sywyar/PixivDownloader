package top.sywyar.pixivdownload.core.metadata.sidecar;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@link WorkMetaCurator} 归一化一份捕获 meta 的产物：可重建的列投影值（{@code uploadTime} /
 * {@code isOriginal}）与待入库的完整快照文档（schemaVersion=1）。
 *
 * <p><b>列投影与快照解耦</b>：列投影值（{@code uploadTime}/{@code isOriginal}）始终可用，
 * 但快照文档可能因超总大小上限被<b>拒绝</b>（{@code document == null}）。被拒时<b>绝不</b>落出
 * {@code raw} 残缺的半成品快照；调用方据 {@link #hasDocument()} 保留已有快照、仅更新列投影。
 *
 * @param uploadTime 真实上传时间（epoch 毫秒，nullable）——写入 {@code upload_time} 列投影
 * @param isOriginal 原创标记三态（nullable）——写入 artworks {@code is_original} 列投影（小说该列在 insert 时已写）
 * @param document   完整快照 JSON 文档，由 {@link top.sywyar.pixivdownload.core.metadata.WorkMetadataStore} 写入数据库；
 *                   归一化结果超总大小上限被拒时为 {@code null}
 */
public record CuratedWorkMeta(Long uploadTime, Boolean isOriginal, ObjectNode document) {

    /**
     * 快照文档是否可入库。{@code false} 表示归一化结果超过快照总大小上限被拒绝：
     * 列投影仍有效，但不得写出 {@code raw} 残缺的半成品快照。
     */
    public boolean hasDocument() {
        return document != null;
    }
}
