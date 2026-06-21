package top.sywyar.pixivdownload.push;

import org.springframework.http.MediaType;

import java.util.List;

/**
 * 通道构造好的一次对外 HTTP 调用描述——pushbot 里 {@code api/*.json} 模板的 Java 等价物：
 * 目标 URL + 内容类型 + 请求体字节 + 需脱敏的密钥 + 是否走代理。
 * <p>
 * {@link PushHttpSender} 据此统一完成发送、错误归类与脱敏，通道实现因此<b>不接触</b> HTTP / 日志 / 脱敏细节，
 * 只负责把 {@link PushMessage} 渲染成本通道协议的请求体。
 *
 * @param url         目标 URL（可含 token 等密钥；这些密钥须同时列入 {@code secrets} 以便脱敏）
 * @param contentType 请求内容类型；{@code null} 归一为 {@code application/json}
 * @param body        请求体字节（UTF-8）；{@code null} 归一为空数组
 * @param secrets     出现在 URL / body 中、绝不能写入日志的密钥（token / device-key 等），发送器会从错误详情里抹除
 * @param useProxy    是否经已配置的 HTTP 代理出站（由通道按自身配置决定）
 */
public record OutboundRequest(String url, MediaType contentType, byte[] body,
                              List<String> secrets, boolean useProxy) {

    public OutboundRequest {
        if (contentType == null) {
            contentType = MediaType.APPLICATION_JSON;
        }
        if (body == null) {
            body = new byte[0];
        }
        secrets = secrets == null ? List.of()
                : secrets.stream().filter(s -> s != null && !s.isBlank()).toList();
    }

    /** 便捷工厂：{@code application/json} 请求体。 */
    public static OutboundRequest json(String url, byte[] body, List<String> secrets, boolean useProxy) {
        return new OutboundRequest(url, MediaType.APPLICATION_JSON, body, secrets, useProxy);
    }
}
