package top.sywyar.pixivdownload.gui.backfill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.DatabaseTarget;
import top.sywyar.pixivdownload.core.db.UnavailableDataSource;
import top.sywyar.pixivdownload.i18n.MessageBundles;
import top.sywyar.pixivdownload.tools.ArtworksBackFill;

import javax.sql.DataSource;
import java.util.concurrent.atomic.AtomicBoolean;

/** 无头启动只提交后台任务；执行、取消和连接释放仍由后端执行器拥有。 */
public final class HeadlessBackfillStartup implements ApplicationListener<ApplicationReadyEvent> {
    private static final Logger LOG = LoggerFactory.getLogger(ArtworksBackFill.class.getName() + ".startup");
    private final SpringApplication application;
    private final AtomicBoolean submitted = new AtomicBoolean();

    public HeadlessBackfillStartup(SpringApplication application) {
        this.application = application;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        // 只接收本次启动的就绪事件，不把子上下文或重复通知当成新执行。
        if (event.getSpringApplication() != application || !submitted.compareAndSet(false, true)) return;
        var context = event.getApplicationContext();
        try {
            var target = context.getBean(DatabaseTarget.class);
            if (!target.available() || UnavailableDataSource.isUnavailable(context.getBean(DataSource.class))) return;
            var proxy = context.getBean(ProxyConfig.class);
            var download = context.getBean(DownloadConfig.class);
            var defaults = ArtworksBackFill.Options.defaults();
            var options = new ArtworksBackFill.Options(target.displayName(), proxy.getHost(), proxy.getPort(),
                    proxy.isEnabled(), defaults.delayMs(), defaults.limit(), false, target)
                    .withSources(download.getRootFolder(), false).forStartup();
            context.getBean(DesktopBackfillRunner.class).start(options, value -> {});
        } catch (Exception failure) {
            LOG.warn(MessageBundles.getForLog("gui.launcher.log.startup.backfill.failed"), failure);
        }
    }
}
