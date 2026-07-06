package top.sywyar.pixivdownload.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

public class PluginConfigEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        PluginConfigPropertySourceLoader.load()
                .ifPresent(source -> addOrReplace(environment.getPropertySources(), source));
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 1;
    }

    private static void addOrReplace(MutablePropertySources sources, MapPropertySource source) {
        if (sources.contains(source.getName())) {
            sources.replace(source.getName(), source);
        } else {
            sources.addLast(source);
        }
    }
}
