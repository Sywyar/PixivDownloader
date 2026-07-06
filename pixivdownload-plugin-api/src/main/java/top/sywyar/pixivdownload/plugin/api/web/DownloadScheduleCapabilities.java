package top.sywyar.pixivdownload.plugin.api.web;

/**
 * 某下载类型对计划任务的声明式能力。当前计划任务执行器缺席时，宿主应挂起残留任务而不是删除数据或抛 500。
 */
public record DownloadScheduleCapabilities(
        boolean saveable,
        boolean sourceSerializable,
        boolean suspendWhenExecutorMissing
) {

    /** 默认：不可保存为计划任务，但若残留任务引用该类型且执行器缺席，应挂起。 */
    public static DownloadScheduleCapabilities notSaveable() {
        return new DownloadScheduleCapabilities(false, false, true);
    }

    /** 可保存为计划任务，source 可序列化，执行器缺席时挂起。 */
    public static DownloadScheduleCapabilities saveableSource() {
        return new DownloadScheduleCapabilities(true, true, true);
    }
}
