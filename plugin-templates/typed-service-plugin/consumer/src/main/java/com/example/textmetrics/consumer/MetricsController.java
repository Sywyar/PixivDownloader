package com.example.textmetrics.consumer;

import com.example.textmetrics.api.TextMetrics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.service.PluginServices;
import top.sywyar.pixivdownload.plugin.api.service.ServiceAccessException;
import top.sywyar.pixivdownload.plugin.api.service.ServiceKey;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.util.Locale;

/** 管理员业务入口；失败响应保留机器码并按请求语言解析说明。 */
@RestController
public final class MetricsController {
    private final PluginServices services;
    private final MessageResolver messages;

    public MetricsController(PluginServices services, MessageResolver messages) {
        this.services = services;
        this.messages = messages;
    }

    @PostMapping("/api/text-metrics")
    public ResponseEntity<?> analyze(@RequestBody TextMetrics.Request request, Locale locale) {
        try {
            var service = services.require(new ServiceKey<>("text-metrics-contract", TextMetrics.class));
            return ResponseEntity.ok(service.analyze(request));
        } catch (ServiceAccessException failure) {
            return ResponseEntity.status(503).body(ApiErrorResponse.of(failure.reason().name(),
                    messages.get(locale, "service.unavailable")));
        }
    }
}
