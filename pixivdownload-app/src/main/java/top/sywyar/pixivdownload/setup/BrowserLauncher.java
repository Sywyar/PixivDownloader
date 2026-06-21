package top.sywyar.pixivdownload.setup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;

@Component
@ConditionalOnProperty(name = "setup.browser.auto-open", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class BrowserLauncher implements ApplicationRunner {

    private final SetupService setupService;
    private final AppMessages messages;
    @Value("${server.port:6999}")
    private int port;

    @Override
    public void run(ApplicationArguments args) {
        if (setupService.isSetupComplete()) return;

        // 仅 nogui（headless / --no-gui）模式才自动打开浏览器到 setup 页；
        // GUI 模式下首次配置改由「首页」引导内完成，不再弹浏览器。
        // GuiLauncher 通过系统属性桥接（--no-gui 会被 filterArgs 过滤，无法走 ApplicationArguments）；
        // 属性缺失时（如直接运行 PixivDownloadApplication）回退到 isHeadless()。
        String headlessProp = System.getProperty("pixivdownload.headless");
        boolean noGui = headlessProp != null
                ? Boolean.parseBoolean(headlessProp)
                : GraphicsEnvironment.isHeadless();
        if (!noGui) return;

        String url = "http://localhost:" + port + "/setup.html";
        log.info(message("setup.browser.log.first-launch.opening", url));

        // 尝试用 Desktop API 打开（适用于有 GUI 的系统）
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI(url));
                return;
            } catch (Exception e) {
                log.warn(message("setup.browser.log.desktop-browse.failed", e.getMessage()));
            }
        }

        // 备用：通过系统命令打开
        String os = System.getProperty("os.name", "").toLowerCase();
        try {
            if (os.contains("win")) {
                Runtime.getRuntime().exec(new String[]{"cmd", "/c", "start", url});
            } else if (os.contains("mac")) {
                Runtime.getRuntime().exec(new String[]{"open", url});
            } else {
                Runtime.getRuntime().exec(new String[]{"xdg-open", url});
            }
        } catch (Exception e) {
            log.warn(message("setup.browser.log.shell-open.failed", e.getMessage()));
            log.info(message("setup.browser.log.manual-open.required", url));
        }
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
