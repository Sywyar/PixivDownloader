package top.sywyar.pixivdownload.guicompose.model;

import top.sywyar.pixivdownload.guicompose.model.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiToolHost.BackfillTask;

import java.util.Map;
import java.util.Objects;

/**
 * Compose 模型一次发布的完整桌面界面快照。
 *
 * @param revision 文档修订号
 * @param document 与修订号同代的完整文档
 * @param interactionRevisions 各交互节点当前的契约修订号
 * @param automaticBackfillNotice 当前尚未收起的自动回填提示，没有时为 null
 */
public record DesktopUiSnapshot(
        long revision,
        DesktopUiDocument document,
        Map<String, Long> interactionRevisions,
        BackfillTask automaticBackfillNotice
) {
    public DesktopUiSnapshot(long revision, DesktopUiDocument document, Map<String, Long> interactionRevisions) {
        this(revision, document, interactionRevisions, null);
    }

    /**
     * 校验修订号并防御性复制交互映射。
     *
     * @param revision 文档修订号
     * @param document 与修订号同代的完整文档
     * @param interactionRevisions 各交互节点当前的契约修订号
     * @param automaticBackfillNotice 当前尚未收起的自动回填提示
     */
    public DesktopUiSnapshot {
        if (revision < 0L) throw new IllegalArgumentException("revision must not be negative");
        document = Objects.requireNonNull(document, "document");
        interactionRevisions = Map.copyOf(Objects.requireNonNull(
                interactionRevisions, "interactionRevisions"));
        interactionRevisions.forEach((nodeId, interactionRevision) -> {
            if (nodeId == null || nodeId.isBlank()) {
                throw new IllegalArgumentException("interaction node id must not be blank");
            }
            if (interactionRevision == null || interactionRevision < 0L) {
                throw new IllegalArgumentException("interaction revision must not be negative");
            }
        });
    }
}
