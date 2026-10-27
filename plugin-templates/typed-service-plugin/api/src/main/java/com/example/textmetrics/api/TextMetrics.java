package com.example.textmetrics.api;

import java.util.List;
import top.sywyar.pixivdownload.plugin.api.contract.schema.ValueProperty;
import top.sywyar.pixivdownload.plugin.api.contract.schema.ValueSchema;
import top.sywyar.pixivdownload.plugin.api.contract.remote.RemoteService;
import top.sywyar.pixivdownload.plugin.api.contract.remote.RemoteOperation;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;

/** 运行时只依赖 JDK 的业务契约；源码 Schema 注解不会进入契约字节码。 */
@RemoteService(owner = "text-metrics-contract")
public interface TextMetrics {
    /** 按 Java 字符串的 UTF-16 长度计算各项和总量。 */
    @RemoteOperation(path = "/api/text-metrics/remote", method = HttpMethod.POST, access = AccessPolicy.ADMIN)
    Result analyze(Request request);

    /** 待统计的文本。 */
    @ValueSchema
    record Request(@ValueProperty(required = true, nullable = false, elementsNullable = false) List<String> texts) {}
    /** 各项长度及总长度。 */
    @ValueSchema
    record Result(
            @ValueProperty(required = true, nullable = false, elementsNullable = false) List<Integer> lengths,
            @ValueProperty(required = true, nullable = false) int total) {}
}
