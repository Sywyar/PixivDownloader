package top.sywyar.pixivdownload.setup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.net.URI;

@Component
@Slf4j
@RequiredArgsConstructor
public class BrowserLauncher implements ApplicationRunner {

    private final SetupService setupService;
    @Value("${server.port:6999}")
    private int port;

    @Override
    public void run(ApplicationArguments args) {
        if (setupService.isSetupComplete()) return;

        String url = "http://localhost:" + port + "/setup.html";
        log.info("First launch detected, opening browser: {}", url);

        // 尝试用 Desktop API 打开（适用于有 GUI 的系统）
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            try {
                Desktop.getDesktop().browse(new URI(url));
                return;
            } catch (Exception e) {
                log.warn("Desktop.browse failed: {}", e.getMessage());
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
            log.warn("Failed to open browser via shell: {}", e.getMessage());
            log.info("Please open manually: {}", url);
        }
    }
}
