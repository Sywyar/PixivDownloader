package top.sywyar.pixivdownload.core.work.service;

/** 路径超限时已由用户授权的处理方式；缺省值不授权改名。 */
public enum DownloadPathAction {
    /** 暂停并询问用户。 */
    ASK,
    /** 按原模板截断文件名。 */
    TRUNCATE,
    /** 使用默认短文件名。 */
    DEFAULT_NAME,
    /** 取消当前超限作品的下载。 */
    CANCEL;

    /**
     * 旧快照缺少字段时仍须询问，未知值不得变成自动改名授权。
     * @param value 保存的操作码，可为空
     * @return 已知操作；缺少或未知值返回 {@link #ASK}
     */
    public static DownloadPathAction parse(String value) {
        if (value == null || value.isBlank()) return ASK;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException invalid) {
            return ASK;
        }
    }
}
