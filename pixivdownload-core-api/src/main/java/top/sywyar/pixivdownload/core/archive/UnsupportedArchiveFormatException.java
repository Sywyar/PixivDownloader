package top.sywyar.pixivdownload.core.archive;

/** 请求的归档格式不受当前导出服务支持。 */
public final class UnsupportedArchiveFormatException extends IllegalArgumentException {
    /** 供调用方识别不支持的归档格式的错误码。 */
    public static final String CODE = "validation.archive.export.format.unsupported";
    /** 请求指定的格式。 */
    private final String format;

    /**
     * 记录未能处理的归档格式。
     *
     * @param format 请求指定的格式
     */
    public UnsupportedArchiveFormatException(String format) {
        super("Unsupported archive format: " + format);
        this.format = format;
    }

    /** {@return 请求指定的归档格式} */
    public String format() {
        return format;
    }
}
