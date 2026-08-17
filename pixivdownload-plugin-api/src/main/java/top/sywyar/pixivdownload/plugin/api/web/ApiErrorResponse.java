package top.sywyar.pixivdownload.plugin.api.web;

import java.util.Objects;

/**
 * API 失败响应的稳定最小契约。调用方只按 {@link #code()} 做机器分支；{@link #error()} 是按请求语言解析的
 * 人类可读说明，不得作为控制流依据。具体端点可以增加诊断字段，但必须保留这两个字段。
 *
 * <p>HTTP 状态行是传输状态的权威来源，本最小契约不要求在 JSON 中重复它。HTML 页面错误不使用本契约，
 * 而是由宿主容器的错误派发渲染状态页。
 */
public interface ApiErrorResponse {

    /** 与界面语言无关的稳定机器码。 */
    String code();

    /** 按请求语言解析的人类可读说明。 */
    String error();

    /** 构造不带端点专属诊断字段的标准失败响应。 */
    static ApiErrorResponse of(String code, String error) {
        return new Basic(code, error);
    }

    /** 仅含稳定必填字段的标准投影。 */
    record Basic(String code, String error) implements ApiErrorResponse {

        public Basic {
            if (code == null || code.isBlank()) {
                throw new IllegalArgumentException("API error code must not be blank");
            }
            code = code.strip();
            error = Objects.requireNonNull(error, "API error message");
        }
    }
}
