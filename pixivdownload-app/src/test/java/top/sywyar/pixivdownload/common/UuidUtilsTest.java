package top.sywyar.pixivdownload.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("UuidUtils UUID 工具")
class UuidUtilsTest {

    /** 真实 UUID v4（version 4 / RFC 4122 variant）。 */
    private static final String V4_ID = "11111111-2222-4333-8444-555555555555";

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

    @Nested
    @DisplayName("parseUuidV4 - canonical v4 外形校验")
    class ParseUuidV4Tests {

        @Test
        @DisplayName("canonical v4 成功解析")
        void parsesCanonicalV4() {
            assertThat(UuidUtils.parseUuidV4(V4_ID)).isEqualTo(UUID.fromString(V4_ID));
        }

        @Test
        @DisplayName("大写 canonical v4 成功解析并规范化为小写")
        void parsesUpperCaseCanonicalAndNormalizes() {
            UUID parsed = UuidUtils.parseUuidV4(V4_ID.toUpperCase(Locale.ROOT));
            assertThat(parsed).isEqualTo(UUID.fromString(V4_ID));
            assertThat(parsed.toString()).as("UUID.toString() 输出小写").isEqualTo(V4_ID);
        }

        @Test
        @DisplayName("允许两侧空白与末尾换行，trim 后校验")
        void trimsWhitespaceAndTrailingNewline() {
            assertThat(UuidUtils.parseUuidV4("  " + V4_ID + "  \r\n"))
                    .isEqualTo(UUID.fromString(V4_ID));
            assertThat(UuidUtils.parseUuidV4(V4_ID + "\n"))
                    .isEqualTo(UUID.fromString(V4_ID));
        }

        @Test
        @DisplayName("缺字符的非 canonical 短形式失败")
        void rejectsMissingCharacters() {
            assertThat(UuidUtils.parseUuidV4("11111111-2222-4333-8444-55555555555"))
                    .as("末组缺字符").isNull();
            assertThat(UuidUtils.parseUuidV4("1111111-2222-4333-8444-555555555555"))
                    .as("首组缺字符").isNull();
        }

        @Test
        @DisplayName("多余字符 / 非标准短组失败")
        void rejectsExtraCharactersAndShortGroups() {
            assertThat(UuidUtils.parseUuidV4(V4_ID + "x")).as("多余字符").isNull();
            assertThat(UuidUtils.parseUuidV4("11111111-222-4333-8444-555555555555"))
                    .as("非标准短组").isNull();
            assertThat(UuidUtils.parseUuidV4("11111111-2222-4333-84-555555555555"))
                    .as("缺字符短组").isNull();
        }

        @Test
        @DisplayName("缺少连字符失败")
        void rejectsMissingHyphens() {
            assertThat(UuidUtils.parseUuidV4("11111111222243338444555555555555"))
                    .as("无连字符").isNull();
            assertThat(UuidUtils.parseUuidV4("11111111-22224333-8444-555555555555"))
                    .as("缺中间连字符").isNull();
        }

        @Test
        @DisplayName("version 非 4 失败")
        void rejectsWrongVersion() {
            assertThat(UuidUtils.parseUuidV4("11111111-2222-1333-8444-555555555555"))
                    .isNull();
            assertThat(UuidUtils.parseUuidV4("11111111-2222-5333-8444-555555555555"))
                    .isNull();
        }

        @Test
        @DisplayName("variant 非 2 失败")
        void rejectsWrongVariant() {
            assertThat(UuidUtils.parseUuidV4("11111111-2222-4333-ccc4-555555555555"))
                    .as("Microsoft variant 拒绝").isNull();
            assertThat(UuidUtils.parseUuidV4("11111111-2222-4333-0333-555555555555"))
                    .as("NCS 变体拒绝").isNull();
            assertThat(UuidUtils.parseUuidV4("11111111-2222-4333-f333-555555555555"))
                    .as("未来变体拒绝").isNull();
        }

        @Test
        @DisplayName("null / 空白 / 非法文本失败")
        void rejectsNullAndGarbage() {
            assertThat(UuidUtils.parseUuidV4(null)).isNull();
            assertThat(UuidUtils.parseUuidV4("")).isNull();
            assertThat(UuidUtils.parseUuidV4("   ")).isNull();
            assertThat(UuidUtils.parseUuidV4("not-a-uuid")).isNull();
        }

        @Test
        @DisplayName("全局 UUID_PATTERN 仍接受普通 UUID 外形，不收紧为 v4")
        void globalPatternRemainsGeneralPurpose() {
            assertThat(UuidUtils.UUID_PATTERN.matcher(V4_ID).matches()).isTrue();
            assertThat(UuidUtils.UUID_PATTERN.matcher("11111111-2222-1333-8444-555555555555")
                    .matches()).as("version 1 外形仍匹配全局模式").isTrue();
            assertThat(UuidUtils.UUID_PATTERN.matcher("11111111222243338444555555555555").matches())
                    .as("无连字符不匹配").isFalse();
        }
    }
}
