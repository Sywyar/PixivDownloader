package top.sywyar.pixivdownload.gui;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** HTTP 和桌面在同一进程中共享工具历史，不随 Spring 上下文重建。 */
@Configuration(proxyBeanMethods = false)
public class DesktopToolHistoryConfiguration {
    @Bean
    DesktopToolHistory desktopToolHistory() { return DesktopToolHistory.shared(); }
}
