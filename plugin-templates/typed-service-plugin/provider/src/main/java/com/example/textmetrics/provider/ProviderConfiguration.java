package com.example.textmetrics.provider;

import com.example.textmetrics.api.TextMetrics;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 显式导出具名 Bean；实现和 Spring 类型不进入中性契约。 */
@Configuration(proxyBeanMethods = false)
public class ProviderConfiguration {
    @Bean
    TextMetrics textMetrics() {
        return request -> {
            var lengths = request.texts().stream().map(String::length).toList();
            return new TextMetrics.Result(lengths, lengths.stream().mapToInt(Integer::intValue).sum());
        };
    }
}
