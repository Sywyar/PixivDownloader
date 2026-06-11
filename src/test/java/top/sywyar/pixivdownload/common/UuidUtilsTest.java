package top.sywyar.pixivdownload.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class UuidUtilsTest {

    @Nested
    @DisplayName("generateUuidFromFingerprint - UUID 生成")
    class GenerateUuidTests {

        @Test
        @DisplayName("相同输入应生成相同 UUID")
        void shouldGenerateConsistentUuid() {
            String uuid1 = UuidUtils.generateUuidFromFingerprint("127.0.0.1", "Chrome/100");
            String uuid2 = UuidUtils.generateUuidFromFingerprint("127.0.0.1", "Chrome/100");

            assertThat(uuid1).isEqualTo(uuid2);
        }

        @Test
        @DisplayName("不同输入应生成不同 UUID")
        void shouldGenerateDifferentUuidForDifferentInput() {
            String uuid1 = UuidUtils.generateUuidFromFingerprint("127.0.0.1", "Chrome/100");
            String uuid2 = UuidUtils.generateUuidFromFingerprint("192.168.1.1", "Firefox/100");

            assertThat(uuid1).isNotEqualTo(uuid2);
        }

        @Test
        @DisplayName("null 输入应不抛异常")
        void shouldHandleNullInputs() {
            assertThatCode(() -> UuidUtils.generateUuidFromFingerprint(null, null))
                    .doesNotThrowAnyException();

            String uuid = UuidUtils.generateUuidFromFingerprint(null, null);
            assertThat(uuid).isNotNull().isNotBlank();
        }

        @Test
        @DisplayName("生成的 UUID 应符合标准格式")
        void shouldGenerateValidUuidFormat() {
            String uuid = UuidUtils.generateUuidFromFingerprint("127.0.0.1", "Chrome");

            assertThat(uuid).matches(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
            );
        }
    }
}
