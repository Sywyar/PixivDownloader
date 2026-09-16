package top.sywyar.pixivdownload.core.work.service;

/** 路径超限时已由用户授权的处理方式；缺省值不授权改名。 */
public enum DownloadPathAction {
    ASK, TRUNCATE, DEFAULT_NAME, CANCEL;

    /** 旧快照缺少字段时仍须询问，未知值不得变成自动改名授权。 */
    public static DownloadPathAction parse(String value) {
        if (value == null || value.isBlank()) return ASK;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException invalid) {
            return ASK;
        }
    }
}
