package top.sywyar.pixivdownload.core.work.model;

/** 当前作品记录的文件管理方式。 */
public enum FileManagement {
    /** 文件由应用管理，删除作品时同时清理。 */
    MANAGED,
    /** 作品引用外部文件，删除作品时保留原件。 */
    EXTERNAL_REFERENCE
}
