package top.sywyar.pixivdownload.ai.controller;

import top.sywyar.pixivdownload.ai.model.AiModelInfo;

import java.util.List;

/** GUI 模型查询端点的有界结构化响应。 */
public record AiModelsResponse(
        boolean success,
        String error,
        List<AiModelInfo> models,
        int count
) {
    public static AiModelsResponse ok(List<AiModelInfo> models) {
        List<AiModelInfo> safeModels = models == null ? List.of() : List.copyOf(models);
        return new AiModelsResponse(true, null, safeModels, safeModels.size());
    }

    public static AiModelsResponse fail(String error) {
        return new AiModelsResponse(false, error, List.of(), 0);
    }
}
