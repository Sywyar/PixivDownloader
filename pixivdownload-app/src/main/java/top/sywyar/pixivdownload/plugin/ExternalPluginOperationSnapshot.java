package top.sywyar.pixivdownload.plugin;

/** 管理 API 可读取的不可变操作快照。 */
public record ExternalPluginOperationSnapshot(
        String packageId,
        ExternalPluginOperation operation,
        String transactionId,
        String diagnostic) {
}
