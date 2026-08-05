package top.sywyar.pixivdownload.i18n;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 语言目录 bean 的独立配置类：不依赖任何其它 bean，避免与 {@link AppLocaleResolver}
 * 等组件形成构造器循环。非法清单在上下文启动时直接失败。
 */
@Configuration
public class LocaleCatalogConfiguration {

    @Bean
    public LocaleCatalog localeCatalog() {
        return LocaleCatalog.load(LocaleCatalog.class.getClassLoader());
    }
}
