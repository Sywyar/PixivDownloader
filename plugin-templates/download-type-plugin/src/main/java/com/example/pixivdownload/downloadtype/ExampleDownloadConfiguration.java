package com.example.pixivdownload.downloadtype;

import com.example.pixivdownload.downloadtype.queue.ExampleDownloadQueue;
import com.example.pixivdownload.downloadtype.schedule.ExampleScheduledSourceExecutor;
import com.example.pixivdownload.downloadtype.schedule.ExampleScheduledWorkExecutor;
import com.example.pixivdownload.downloadtype.web.ExampleDownloadController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;

/** Explicit child-context assembly. The host does not component-scan third-party plugins. */
@Configuration
public class ExampleDownloadConfiguration {

    @Bean
    public ExampleDownloadQueue exampleDownloadQueue() {
        return new ExampleDownloadQueue();
    }

    @Bean
    public ExampleDownloadController exampleDownloadController(
            ExampleDownloadQueue queue,
            RequestOwnerIdentityResolver requestOwnerIdentityResolver) {
        return new ExampleDownloadController(queue, requestOwnerIdentityResolver);
    }

    @Bean
    public ExampleScheduledSourceExecutor exampleScheduledSourceExecutor(ObjectMapper objectMapper) {
        return new ExampleScheduledSourceExecutor(objectMapper);
    }

    @Bean
    public ExampleScheduledWorkExecutor exampleScheduledWorkExecutor(
            ObjectMapper objectMapper,
            ExampleDownloadQueue queue) {
        return new ExampleScheduledWorkExecutor(objectMapper, queue);
    }
}
