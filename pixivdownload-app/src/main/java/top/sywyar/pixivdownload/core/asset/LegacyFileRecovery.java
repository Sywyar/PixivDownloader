package top.sywyar.pixivdownload.core.asset;

import org.springframework.context.ApplicationListener;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.schema.DatabaseReadyEvent;

/** 旧文件清单只在数据库就绪后恢复；无库恢复壳保留原文件与清单。 */
@Component
public final class LegacyFileRecovery implements ApplicationListener<PayloadApplicationEvent<DatabaseReadyEvent>> {
    private final DownloadConfig downloadConfig;

    public LegacyFileRecovery(DownloadConfig downloadConfig) { this.downloadConfig = downloadConfig; }

    @Override public void onApplicationEvent(PayloadApplicationEvent<DatabaseReadyEvent> event) {
        RuntimeFiles.recoverDeleteStagingLeftovers(downloadConfig.getRootFolder());
    }
}
