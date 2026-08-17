package top.sywyar.pixivdownload.core.work;

/**
 * 描述作品操作的稳定状态与用户可读消息。
 */
public class WorkActionResult {
    /** 操作成功。 */
    public static final String SUCCESS = "success";
    /** 操作失败。 */
    public static final String FAILED = "failed";
    /** 操作已跳过。 */
    public static final String SKIPPED = "skipped";
    /** 目标已经存在。 */
    public static final String EXISTS = "exists";

    private final String status;
    private final String message;

    private WorkActionResult(String status, String message) {
        this.status = status;
        this.message = message;
    }

    /**
     * 创建成功结果。
     *
     * @param message 用户可读消息
     * @return 成功结果
     */
    public static WorkActionResult success(String message) {
        return new WorkActionResult(SUCCESS, message);
    }

    /**
     * 创建失败结果。
     *
     * @param message 用户可读消息
     * @return 失败结果
     */
    public static WorkActionResult failed(String message) {
        return new WorkActionResult(FAILED, message);
    }

    /**
     * 创建跳过结果。
     *
     * @param message 用户可读消息
     * @return 跳过结果
     */
    public static WorkActionResult skipped(String message) {
        return new WorkActionResult(SKIPPED, message);
    }

    /**
     * 创建目标已存在的结果。
     *
     * @param message 用户可读消息
     * @return 已存在结果
     */
    public static WorkActionResult exists(String message) {
        return new WorkActionResult(EXISTS, message);
    }

    /**
     * 返回状态机器码。
     *
     * @return 状态机器码
     */
    public String getStatus() {
        return status;
    }

    /**
     * 返回用户可读消息。
     *
     * @return 用户可读消息
     */
    public String getMessage() {
        return message;
    }
}
