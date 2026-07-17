package com.example.pixivdownload.minimal;

import com.example.pixivdownload.minimal.web.ExampleMinimalController;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Explicit bean assembly for the plugin-owned child ApplicationContext. */
@Configuration(proxyBeanMethods = false)
public class ExampleMinimalConfiguration {

    @Bean
    public ExampleMinimalPlugin exampleMinimalPlugin() {
        return new ExampleMinimalPlugin();
    }

    @Bean
    public ExampleMinimalController exampleMinimalController() {
        return new ExampleMinimalController();
    }
}
