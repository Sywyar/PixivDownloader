package top.sywyar.pixivdownload.web;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Servlet 过滤器共用的 API 错误 JSON 写出点。HTML 错误仍由调用方通过 {@code sendError} 进入容器错误页。 */
public final class ApiErrorWriter {

    private static final ObjectMapper MAPPER = JsonMapper.builderWithJackson2Defaults().build();

    private ApiErrorWriter() {
    }

    public static void write(HttpServletResponse response, int status, String code, String error) throws IOException {
        write(response, status, ApiErrorResponse.of(code, error));
    }

    /** 写出带端点诊断字段的错误；{@code code} / {@code error} 仍由 {@link ApiErrorResponse} 保证。 */
    public static void write(HttpServletResponse response, int status, ApiErrorResponse body) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
