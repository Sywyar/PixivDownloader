package top.sywyar.pixivdownload.plugin.api.download.submission;

/** 下载提交的受控失败，不携带插件异常、路径或凭据。 */
public final class DownloadTaskException extends RuntimeException {
    /** 机器可判定的失败原因。 */
    public enum Code {
        /** 当前没有此类型的已发布提交处理器。 */ UNSUPPORTED_WORK_TYPE,
        /** 精确 publication 或数据库暂时不可用。 */ UNAVAILABLE,
        /** 提供方查询目标作品时确认当前身份找不到该作品。 */ WORK_NOT_FOUND,
        /** 来源暂时限制请求。 */ UPSTREAM_RATE_LIMITED,
        /** 幂等键已用于不同命令。 */ CONFLICT,
        /** 有界任务存储已满。 */ CAPACITY_EXCEEDED,
        /** 类型校验或准入规则拒绝。 */ REJECTED,
        /** 当前调用者不是管理员。 */ FORBIDDEN
    }
    /** 提供方返回的失败信息。仅传递安全的字段诊断和上游重试时间。
     * @param code 稳定失败码
     * @param field 请求字段的 JSON Pointer，无法定位时为空
     * @param reason 校验关键字或业务原因码，无法定位时为空
     * @param retryAfterMillis 上游建议的等待毫秒数；零表示未提供
     */
    public record Failure(Code code, String field, String reason, long retryAfterMillis) {
        /** 构造不含重试时间的失败。
         * @param code 稳定失败码
         * @param field 请求字段的 JSON Pointer 或空值
         * @param reason 稳定原因码或空值 */
        public Failure(Code code, String field, String reason) { this(code, field, reason, 0); }

        /** 校验字段诊断的范围。
         * @param code 稳定失败码
         * @param field 字段的 JSON Pointer 或空值
         * @param reason 稳定原因码或空值
         * @param retryAfterMillis 上游建议的等待毫秒数或零 */
        public Failure {
            java.util.Objects.requireNonNull(code);
            if (retryAfterMillis < 0 || retryAfterMillis > 0 && code != Code.UPSTREAM_RATE_LIMITED)
                throw new IllegalArgumentException("Invalid submission retry delay");
            if ((field == null) != (reason == null)
                    || field != null && (code != Code.REJECTED || !field.matches("/(requestId|workType|workId|options(/[a-zA-Z][a-zA-Z0-9.-]{0,63})?)")
                    || !reason.matches("[a-z][a-zA-Z0-9_]{0,63}")))
                throw new IllegalArgumentException("Invalid submission field diagnostic");
        }
    }
    /** 可跨插件传递的受控失败。 */
    private final Failure failure;
    /** 可序列化的稳定失败原因，不保存原始插件异常。 */
    private final String programPermission;
    /**
     * 使用稳定失败码创建下载任务异常。
     *
     * @param code 失败原因
     */
    public DownloadTaskException(Code code) {
        this(new Failure(code, null, null), null);
    }

    /** 从提供方的结构化失败信息生成受控异常。
     * @param failure 提供方返回的失败信息 */
    public DownloadTaskException(Failure failure) {
        this(failure, null);
    }

    private DownloadTaskException(Failure failure, String programPermission) {
        super(failure.code().name());
        this.programPermission = programPermission;
        this.failure = failure;
    }
    /**
     * {@return 失败原因}
     */
    public Code code() { return failure.code(); }

    /** {@return 可返回给调用方的提交失败信息} */
    public Failure failure() { return failure; }

    /** 构造上游限流的安全诊断。
     * @param retryAfterMillis 上游建议的等待毫秒数；零表示未提供
     * @return 含重试时间的受控失败 */
    public static DownloadTaskException upstreamRateLimited(long retryAfterMillis) {
        return new DownloadTaskException(new Failure(Code.UPSTREAM_RATE_LIMITED, null, null, retryAfterMillis));
    }

    /** 构造提交提供方权限不足的错误。
     * @param owner 提供方的功能插件 ID
     * @return 含所需权限的受控失败 */
    public static DownloadTaskException missingCreatePermission(String owner) {
        if (owner == null || !owner.matches("[a-z][a-z0-9.-]{0,95}")) return new DownloadTaskException(Code.FORBIDDEN);
        return new DownloadTaskException(new Failure(Code.FORBIDDEN, null, null), owner + ":downloads.create");
    }

    /** {@return 程序密钥所缺的权限，其它失败返回空值} */
    public String programPermission() { return programPermission; }
}
