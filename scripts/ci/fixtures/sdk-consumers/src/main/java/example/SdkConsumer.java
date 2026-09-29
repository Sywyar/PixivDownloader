package example;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.pf4j.Plugin;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.ModelAndView;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadAdmissionPolicy;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadLifecycle;
import top.sywyar.pixivdownload.plugin.api.download.lifecycle.DownloadObserver;
import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.sdk.SdkVersion;

/** 通过单一 SDK 依赖编译全部公开模块及宿主提供的框架类型。 */
public record SdkConsumer(PixivFeaturePlugin feature, DownloadSettings settings, Plugin entry,
                          ApplicationContext context, ResponseEntity<?> response,
                          ModelAndView view, HttpServletRequest request, ObjectMapper json,
                          WorkFileImporter importer, DownloadLifecycle downloads,
                          DownloadObserver observer, DownloadAdmissionPolicy admission) {
    public static final String SDK_VERSION = SdkVersion.VERSION;
}
