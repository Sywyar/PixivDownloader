package top.sywyar.pixivdownload.ai.controller;

import top.sywyar.pixivdownload.ai.AiClientSettings;

/**
 * GUI 配置页"测试 AI 连接"按钮的请求 DTO。
 * <p>
 * 由 {@link AiTestController#test} 接收；字段对应 GUI AI 分组的当前表单值。包含 API Key（用户尚未保存配置，
 * 需通过本地端点传给后端），仅在
 * {@link top.sywyar.pixivdownload.common.NetworkUtils#isTrustedLocalRequest} + GUI token 双校验后通过同进程
 * localhost 流转。
 */
public record AiTestRequest(
        String baseUrl,
        String apiKey,
        String model,
        Boolean useProxy
) {

    /** 转为不可变 {@link AiClientSettings}；缺字段回退安全默认值。 */
    public AiClientSettings toClientSettings() {
        return new AiClientSettings(
                nullToEmpty(baseUrl),
                nullToEmpty(apiKey),
                nullToEmpty(model),
                useProxy != null && useProxy);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
