package top.sywyar.pixivdownload.plugin.web.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.plugin.api.contract.schema.ValueSchema;

/** 浏览器与生成客户端使用 SDK 自身提供的同一份固定值编码实现。 */
@RestController
public final class SdkWebResourcesController {
    @GetMapping(value = "/sdk/values/value-codecs.mjs", produces = "text/javascript;charset=UTF-8")
    public Resource valueCodecs() {
        return new ClassPathResource("/META-INF/pixivdownload/web/value-codecs.mjs", ValueSchema.class);
    }
}
