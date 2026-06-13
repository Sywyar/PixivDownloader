package top.sywyar.pixivdownload;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;

import java.nio.file.Path;

/**
 * 前两个排除过滤器是 {@code @SpringBootApplication} 元注解的原样展开；展开成显式组合
 * 是为了追加第三个：被 {@link PluginManagedBean} 标记的插件托管 Bean 不经根包扫描注册，
 * 一律由所属插件的 {@code XxxPluginConfiguration} 以 {@code @Bean} 显式提供。
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
        @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = PluginManagedBean.class)})
@EnableScheduling
public class PixivDownloadApplication {

    /*public static void main(String[] args) {
        start(args);
    }*/

    public static ConfigurableApplicationContext start(String[] args) {
        Path configPath = RuntimeFiles.resolveConfigYamlPath();
        String rootFolder = RuntimeFiles.readDownloadRootFromConfig(configPath, RuntimeFiles.DEFAULT_DOWNLOAD_ROOT);
        RuntimeFiles.prepareRuntimeFiles(rootFolder);
        return SpringApplication.run(PixivDownloadApplication.class, args);
    }

}
