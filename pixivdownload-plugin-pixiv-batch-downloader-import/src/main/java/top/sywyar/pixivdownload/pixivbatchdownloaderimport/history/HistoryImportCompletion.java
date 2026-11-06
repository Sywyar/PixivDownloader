package top.sywyar.pixivdownload.pixivbatchdownloaderimport.history;

import top.sywyar.pixivdownload.plugin.api.gui.importing.DesktopFileImportTool.Completion;
import java.util.Optional;
import java.util.UUID;

/** 浏览器与文件导入共享一条待确认提示；只保存计数，不持有作品列表。 */
public final class HistoryImportCompletion {
    private Completion pending;

    public synchronized void completed(int imported) {
        if (imported > 0) pending = new Completion(UUID.randomUUID().toString(),
                imported + (pending == null ? 0L : pending.imported()));
    }
    public synchronized Optional<Completion> pending() { return Optional.ofNullable(pending); }
    public synchronized void acknowledge(String id) {
        if (pending != null && pending.id().equals(id)) pending = null;
    }
}
