package top.sywyar.pixivdownload.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 调用参数快照")
class AiClientSettingsTest {

    @Test
    @DisplayName("字符串表示始终遮蔽 API Key")
    void toStringRedactsApiKey() {
        String secret = "sk-sensitive-value";
        AiClientSettings settings = new AiClientSettings(
                "https://api.example.test/v1", secret, "model-name", true);

        assertThat(settings.toString())
                .contains("baseUrl=https://api.example.test/v1")
                .contains("apiKey=***")
                .contains("model=model-name")
                .contains("useProxy=true")
                .doesNotContain(secret);
    }
}
