package top.sywyar.pixivdownload.core.work.query;

/** 破坏性批量操作未限制作品范围，且未获得对应作品库的全选确认。 */
public final class WorkSelectionException extends RuntimeException {
    /** 创建缺少全库确认的错误。 */
    public WorkSelectionException() {
        super("An unrestricted work selection requires confirmAll");
    }
}
