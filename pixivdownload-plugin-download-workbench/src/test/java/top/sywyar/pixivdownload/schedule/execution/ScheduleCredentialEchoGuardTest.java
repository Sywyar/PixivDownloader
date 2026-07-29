package top.sywyar.pixivdownload.schedule.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("计划凭证原文回显检测器")
class ScheduleCredentialEchoGuardTest {

    @Test
    @DisplayName("完整凭证按去除首尾空白后的原文匹配")
    void matchesTheTrimmedFullCredentialWithoutChangingTheCallerArray() {
        char[] source = " \tplain-token-123456 \r\n".toCharArray();

        try (ScheduleCredentialEchoGuard guard = new ScheduleCredentialEchoGuard(source)) {
            source[2] = 'X';

            assertThat(guard.matches("plain-token-123456")).isTrue();
            assertThat(guard.matches("prefix plain-token-123456 suffix")).isTrue();
            assertThat(guard.matches("Plain-token-123456")).isFalse();
        }

        assertThat(source[2]).isEqualTo('X');
    }

    @Test
    @DisplayName("Cookie 对、敏感值和未知高熵值均能被识别")
    void matchesCookiePairsSensitiveValuesAndUnknownHighEntropyValues() {
        char[] secret = ("Cookie: locale=zh; PHPSESSID=12345_abc; "
                + "device=Ab12Cd34Ef56Gh78; repeated=aaaaaaaaaaaaaaaa; "
                + "theme=dark").toCharArray();

        try (ScheduleCredentialEchoGuard guard = new ScheduleCredentialEchoGuard(secret)) {
            assertThat(guard.matches("locale=zh")).isTrue();
            assertThat(guard.matches("prefix PHPSESSID=12345_abc suffix")).isTrue();
            assertThat(guard.matches("12345_abc")).isTrue();
            assertThat(guard.matches("device=Ab12Cd34Ef56Gh78")).isTrue();
            assertThat(guard.matches("value=Ab12Cd34Ef56Gh78")).isTrue();
            assertThat(guard.matches("zh")).isFalse();
            assertThat(guard.matches("aaaaaaaaaaaaaaaa")).isFalse();
            assertThat(guard.matches("dark")).isFalse();
        }
    }

    @Test
    @DisplayName("账号键派生前缀不会被凭证值误报")
    void doesNotMatchDerivedAccountKeyPrefixes() {
        try (ScheduleCredentialEchoGuard guard =
                     new ScheduleCredentialEchoGuard("PHPSESSID=12345_abc".toCharArray())) {
            assertThat(guard.matches("12345")).isFalse();
            assertThat(guard.matches("account:12345")).isFalse();
            assertThat(guard.matches("12345_abc")).isTrue();
        }
    }

    @Test
    @DisplayName("短片段只匹配完整字段而长片段允许位于字段内部")
    void limitsShortFragmentsToWholeFieldMatches() {
        try (ScheduleCredentialEchoGuard shortGuard =
                     new ScheduleCredentialEchoGuard("sid=abc".toCharArray());
             ScheduleCredentialEchoGuard longGuard =
                     new ScheduleCredentialEchoGuard("sid=abcdefgh".toCharArray())) {
            assertThat(shortGuard.matches("abc")).isTrue();
            assertThat(shortGuard.matches("prefix-abc-suffix")).isFalse();
            assertThat(shortGuard.matches("sid=abc")).isTrue();
            assertThat(shortGuard.matches("prefix sid=abc suffix")).isFalse();

            assertThat(longGuard.matches("prefix-abcdefgh-suffix")).isTrue();
        }
    }

    @Test
    @DisplayName("带引号的敏感 Cookie 值同时识别原始值和去引号值")
    void matchesQuotedSensitiveCookieValuesWithoutNormalizingThem() {
        try (ScheduleCredentialEchoGuard guard = new ScheduleCredentialEchoGuard(
                "session=\"Ab12Cd34\"; preference='blue'".toCharArray())) {
            assertThat(guard.matches("\"Ab12Cd34\"")).isTrue();
            assertThat(guard.matches("Ab12Cd34")).isTrue();
            assertThat(guard.matches("blue")).isFalse();
        }
    }

    @Test
    @DisplayName("匹配保持 UTF-16 原文且不做解码或 Unicode 规范化")
    void keepsUtf16TextExactWithoutDecodingOrNormalization() {
        String exact = "café%2F😀Ab12";
        try (ScheduleCredentialEchoGuard guard =
                     new ScheduleCredentialEchoGuard(("token=" + exact).toCharArray())) {
            assertThat(guard.matches(exact)).isTrue();
            assertThat(guard.matches("cafe\u0301%2F😀Ab12")).isFalse();
            assertThat(guard.matches("café/😀Ab12")).isFalse();
            assertThat(guard.matches("CAFÉ%2F😀Ab12")).isFalse();
        }
    }

    @Test
    @DisplayName("关闭时清零内部快照且关闭后固定不再匹配")
    void clearsItsSnapshotOnCloseAndReturnsFalseAfterwards() {
        char[] source = "token=Secret12345".toCharArray();
        ScheduleCredentialEchoGuard guard = new ScheduleCredentialEchoGuard(source);
        char[] snapshot = readSecretSnapshot(guard);

        guard.close();
        guard.close();

        assertThat(snapshot).containsOnly('\0');
        assertThat(readSecretSnapshot(guard)).isEmpty();
        assertThat(source).containsExactly("token=Secret12345".toCharArray());
        assertThat(guard.matches("Secret12345")).isFalse();
        assertThat(guard.matches(null)).isFalse();
    }

    @Test
    @DisplayName("撤销插件句柄后仍检测回显并在材料最终关闭时清零")
    void materialKeepsEchoDetectionUntilItsFinalClose() {
        ScheduleCredentialMaterial material = new ScheduleCredentialMaterial(
                "token=Secret12345", "fixture-reference", "account-1");

        try (material) {
            material.revoke();
            try (var handle = material.openHandle()) {
                assertThat(handle.isPresent()).isFalse();
            }
            assertThat(material.containsEcho("Secret12345")).isTrue();
        }

        assertThat(material.containsEcho("Secret12345")).isFalse();
    }

    @Test
    @DisplayName("JSON 标量按解析后的字段边界检测短数字与转义字符串")
    void materialMatchesDecodedJsonScalarBoundaries() {
        ObjectMapper objectMapper = new ObjectMapper();
        try (ScheduleCredentialMaterial numeric = new ScheduleCredentialMaterial(
                "sid=12345", "fixture-reference", null);
             ScheduleCredentialMaterial exponent = new ScheduleCredentialMaterial(
                     "sid=1e3", "fixture-reference", null);
             ScheduleCredentialMaterial decimal = new ScheduleCredentialMaterial(
                     "sid=1.00", "fixture-reference", null);
             ScheduleCredentialMaterial escaped = new ScheduleCredentialMaterial(
                     "token=Ab12Cd34", "fixture-reference", null)) {
            assertThat(numeric.containsEchoInJson(
                    objectMapper, "{\"cursor\":12345}")).isTrue();
            assertThat(exponent.containsEchoInJson(
                    objectMapper, "{\"cursor\":1e3}")).isTrue();
            assertThat(decimal.containsEchoInJson(
                    objectMapper, "{\"cursor\":1.00}")).isTrue();
            assertThat(escaped.containsEchoInJson(
                    objectMapper, "{\"cursor\":\"Ab12Cd\\u0033\\u0034\"}")).isTrue();
            assertThat(numeric.containsEchoInJson(
                    objectMapper,
                    "{\"wrapper\":\"{\\\"cursor\\\":12345}\"}")).isTrue();
            assertThat(numeric.containsEchoInJson(
                    objectMapper,
                    "{\"wrapper\":\"{\\\"cursor\\\":12345 BROKEN\"}")).isFalse();
            assertThat(numeric.containsEchoInJson(
                    objectMapper, "{\"cursor\":\"prefix-12345-suffix\"}")).isFalse();
        }
    }

    @Test
    @DisplayName("空凭证和只有空白的凭证不会产生匹配片段")
    void ignoresMissingAndWhitespaceOnlyCredentials() {
        try (ScheduleCredentialEchoGuard missing = new ScheduleCredentialEchoGuard(null);
             ScheduleCredentialEchoGuard blank =
                     new ScheduleCredentialEchoGuard(" \t\r\n".toCharArray())) {
            assertThat(missing.matches("anything")).isFalse();
            assertThat(blank.matches("anything")).isFalse();
        }
    }

    private static char[] readSecretSnapshot(ScheduleCredentialEchoGuard guard) {
        try {
            Field field = ScheduleCredentialEchoGuard.class.getDeclaredField("secretSnapshot");
            field.setAccessible(true);
            return (char[]) field.get(guard);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
