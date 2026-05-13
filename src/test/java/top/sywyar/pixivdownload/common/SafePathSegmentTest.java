package top.sywyar.pixivdownload.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import top.sywyar.pixivdownload.i18n.LocalizedException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SafePathSegment tests")
class SafePathSegmentTest {

    @Test
    @DisplayName("普通用户名作为目录段时保留并 trim 两端空格")
    void acceptsNormalSegment() {
        assertThat(SafePathSegment.requireSafeDirectoryName(" alice ")).isEqualTo("alice");
        assertThat(SafePathSegment.requireSafeDirectoryName("user_123")).isEqualTo("user_123");
        assertThat(SafePathSegment.requireSafeDirectoryName("中文名"))
                .isEqualTo("中文名");
    }

    @Test
    @DisplayName("null 输入返回 null，不抛异常")
    void allowsNull() {
        assertThat(SafePathSegment.requireSafeDirectoryName(null)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", ".", "..", "../etc", "..\\foo",
            "a/b", "a\\b", "C:windows", "a:b"})
    @DisplayName("非法目录段应抛 LocalizedException")
    void rejectsUnsafeSegments(String value) {
        assertThatThrownBy(() -> SafePathSegment.requireSafeDirectoryName(value))
                .isInstanceOf(LocalizedException.class);
    }

    @Test
    @DisplayName("以斜杠开头的绝对路径应被拒绝")
    void rejectsAbsolutePaths() {
        assertThatThrownBy(() -> SafePathSegment.requireSafeDirectoryName("/etc/passwd"))
                .isInstanceOf(LocalizedException.class);
    }

    @Test
    @DisplayName("NUL 字符应被拒绝")
    void rejectsNullByte() {
        assertThatThrownBy(() -> SafePathSegment.requireSafeDirectoryName("ali\0ce"))
                .isInstanceOf(LocalizedException.class);
    }
}
