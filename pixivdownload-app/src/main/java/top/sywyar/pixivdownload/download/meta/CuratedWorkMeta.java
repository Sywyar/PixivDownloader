package top.sywyar.pixivdownload.download.meta;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@link WorkMetaCurator} 归一化一份捕获 meta 的产物：可重建的列投影值（{@code uploadTime} /
 * {@code isOriginal}）+ 待落盘的完整 sidecar 文档（schemaVersion=1）。
 *
 * <p><b>列投影与 sidecar 解耦</b>：列投影值（{@code uploadTime}/{@code isOriginal}）始终可用，
 * 但 sidecar 文档可能因超总大小上限被<b>拒绝</b>（{@code document == null}）。被拒时<b>绝不</b>落出
 * {@code raw} 残缺的半成品 sidecar；调用方据 {@link #hasDocument()} 跳过落盘、仅保留列投影
 * （见 {@link WorkMetaCaptureService} 的 warn-continue 一致性模型）。
 *
 * @param uploadTime 真实上传时间（epoch 毫秒，nullable）——写入 {@code upload_time} 列投影
 * @param isOriginal 原创标记三态（nullable）——写入 artworks {@code is_original} 列投影（小说该列在 insert 时已写）
 * @param document   完整 sidecar JSON 文档（权威落点），由 {@link WorkSidecarStore} 原子写出；
 *                   归一化结果超总大小上限被拒时为 {@code null}（此时不得落盘）
 */
public record CuratedWorkMeta(Long uploadTime, Boolean isOriginal, ObjectNode document) {

    /**
     * sidecar 文档是否可落盘。{@code false} 表示归一化结果超过 sidecar 总大小上限被拒绝：
     * 列投影仍有效，但不得写出 {@code raw} 残缺的半成品 sidecar。
     */
    public boolean hasDocument() {
        return document != null;
    }
}
