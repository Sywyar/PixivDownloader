package top.sywyar.pixivdownload.novel.ai;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Objects;

/** 小说 AI 响应的 JSON 对象解析，容忍外围说明文字与代码围栏。 */
public final class AiResponseParser {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private AiResponseParser() {
    }

    public static <T> T parse(String content, Class<T> responseType) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("empty AI response");
        }
        try {
            return Objects.requireNonNull(MAPPER.readValue(content, responseType), "empty AI response");
        } catch (Exception first) {
            int start = content.indexOf('{');
            int end = content.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return Objects.requireNonNull(MAPPER.readValue(content.substring(start, end + 1), responseType),
                            "empty AI response");
                } catch (Exception ignored) {
                    // 保留首次解析失败，便于定位原始响应。
                }
            }
            throw new IllegalArgumentException("unparseable AI response: " + first.getMessage(), first);
        }
    }
}
