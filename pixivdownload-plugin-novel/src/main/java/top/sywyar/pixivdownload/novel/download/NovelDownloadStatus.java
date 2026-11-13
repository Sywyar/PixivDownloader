package top.sywyar.pixivdownload.novel.download;

import lombok.Data;
import lombok.AccessLevel;
import lombok.Setter;
import top.sywyar.pixivdownload.core.work.WorkActionResult;

@Data
public class NovelDownloadStatus {
    private volatile String errorCode;
    private String errorMessageKey;
    private Object[] errorMessageArgs = new Object[0];
    private Long novelId;
    private String title;
    private String ownerUuid;
    private String format;
    @Setter(AccessLevel.NONE)
    private volatile State state = new State("pending", false, false, false, null);
    private String downloadPath;
    /** 内嵌图片总数（本次需下载的占位符数量）；无内嵌图时为 0。 */
    private int embeddedTotal;
    /** 已处理的内嵌图片数量（无论成功失败都计入，用于进度展示）。 */
    private int embeddedDone;
    /** 封面图片总字节数（来自上游 Content-Length）；未知时为 0。 */
    private long coverTotalBytes;
    /** 封面图片已下载字节数，用于流式进度条。 */
    private long coverDownloadedBytes;
    private WorkActionResult bookmarkResult;
    private WorkActionResult collectionResult;

    public NovelDownloadStatus(Long novelId, String title, String format, String ownerUuid) {
        this.novelId = novelId;
        this.title = title;
        this.ownerUuid = ownerUuid;
        this.format = format;
    }

    public record State(String stage, boolean completed, boolean failed, boolean cancelled, String errorMessage) {}

    public synchronized void setStage(String stage) {
        if (!state.completed()) state = new State(stage, false, false, false, null);
    }

    public synchronized void complete() {
        if (!state.cancelled()) state = new State("completed", true, false, false, null);
    }

    public synchronized void fail(String errorMessage) {
        if (!state.cancelled()) state = new State(state.stage(), true, true, false, errorMessage);
    }

    public synchronized void cancel(String errorMessage) {
        state = new State("cancelled", true, false, true, errorMessage);
    }

    public boolean isCancelled() {
        return state.cancelled();
    }

    public boolean isCompleted() {
        return state.completed();
    }

    public boolean isFailed() {
        return state.failed();
    }

    public String getStage() {
        return state.stage();
    }

    public String getErrorMessage() {
        return state.errorMessage();
    }

    /** 请求边界按当前语言展开错误，下载状态只持有受控 key 和参数。 */
    public String errorMessage(top.sywyar.pixivdownload.i18n.MessageResolver messages) {
        return errorMessageKey == null ? state.errorMessage() : messages.get(errorMessageKey, errorMessageArgs);
    }
}
