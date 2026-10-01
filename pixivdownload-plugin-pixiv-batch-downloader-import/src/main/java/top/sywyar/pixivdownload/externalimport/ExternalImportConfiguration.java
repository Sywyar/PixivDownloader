package top.sywyar.pixivdownload.externalimport;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;

/** 配置由宿主绑定的 owner-scoped 属性源加载。 */
@Configuration(proxyBeanMethods = false)
public class ExternalImportConfiguration {
    @Bean public ExternalImportController externalImportController(WorkFileImporter importer,
            NamespaceMessageResolver messages, Environment environment, ApplicationModeProvider mode) {
        return new ExternalImportController(importer, messages,
                environment.getProperty("pixiv-batch-downloader-import.source-root", ""), mode);
    }
}
