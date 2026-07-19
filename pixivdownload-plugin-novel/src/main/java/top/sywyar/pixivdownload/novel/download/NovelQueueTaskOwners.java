package top.sywyar.pixivdownload.novel.download;

/**
 * novel 运行期任务在共享队列代际中的插件私有 owner key。原始用户 owner 永不直接充当 tracker key，
 * 避免无 owner、字面值 owner 与自动翻译任务互相碰撞。
 */
public final class NovelQueueTaskOwners {

    private static final String DOWNLOAD_WITHOUT_OWNER = "download:null";
    private static final String DOWNLOAD_OWNER_PREFIX = "download:value:";
    private static final String AUTO_TRANSLATE_PREFIX = "auto-translate:novel:";

    private NovelQueueTaskOwners() {
    }

    public static String download(String ownerUuid) {
        return ownerUuid == null ? DOWNLOAD_WITHOUT_OWNER : DOWNLOAD_OWNER_PREFIX + ownerUuid;
    }

    public static boolean isDownload(String ownerKey) {
        return DOWNLOAD_WITHOUT_OWNER.equals(ownerKey)
                || ownerKey != null && ownerKey.startsWith(DOWNLOAD_OWNER_PREFIX);
    }

    public static String autoTranslate(long novelId) {
        return AUTO_TRANSLATE_PREFIX + novelId;
    }
}
