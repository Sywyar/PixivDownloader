package top.sywyar.pixivdownload.core.pixiv.media;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("Pixiv 媒体计量流")
class PixivMediaStreamTest {

    private static final byte[] BODY = {1, 2, 3, 4, 5, 6, 7, 8};

    @Test
    @DisplayName("声明长度或上限恰好等于正文时完整读取")
    void readsBodiesThatExactlyMatchTheDeclaredLengthOrLimit() throws IOException {
        try (var declared = new PixivMediaStream(new ByteArrayInputStream(BODY), BODY.length, 100)) {
            assertThat(declared.declaredLength()).isEqualTo(BODY.length);
            assertThat(declared.readAllBytes()).isEqualTo(BODY);
        }
        try (var undeclared = new PixivMediaStream(new ByteArrayInputStream(BODY), -1, BODY.length)) {
            assertThat(undeclared.readAllBytes()).isEqualTo(BODY);
            assertThat(undeclared.exceededLimit()).isFalse();
        }
    }

    @Test
    @DisplayName("未声明长度的正文超过上限时标记超限")
    void marksUndeclaredBodiesThatExceedTheLimit() throws IOException {
        try (var stream = new PixivMediaStream(new ByteArrayInputStream(BODY), -1, 4)) {
            assertThatIOException().isThrownBy(stream::readAllBytes);
            assertThat(stream.exceededLimit()).isTrue();
        }
    }

    @Test
    @DisplayName("正文与声明长度不符时作为传输失败而非超限")
    void treatsDeclaredLengthMismatchAsTransportFailure() throws IOException {
        try (var shortBody = new PixivMediaStream(new ByteArrayInputStream(BODY), 20, 30)) {
            assertThatIOException().isThrownBy(shortBody::readAllBytes);
            assertThat(shortBody.exceededLimit()).isFalse();
        }
        try (var longBody = new PixivMediaStream(new ByteArrayInputStream(BODY), 4, 30)) {
            assertThatIOException().isThrownBy(longBody::readAllBytes);
            assertThat(longBody.exceededLimit()).isFalse();
        }
    }

    @Test
    @DisplayName("拒绝非正上限与超过上限的声明长度，关闭时释放底层流")
    void validatesBoundsAndClosesTheSource() throws IOException {
        var input = new ByteArrayInputStream(BODY);
        assertThatIllegalArgumentException().isThrownBy(() -> new PixivMediaStream(input, -1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new PixivMediaStream(input, 9, 8));
        assertThatIllegalArgumentException().isThrownBy(() -> new PixivMediaStream(input, -2, 8));
        var closed = new AtomicBoolean();
        var stream = new PixivMediaStream(new ByteArrayInputStream(BODY) {
            @Override public void close() { closed.set(true); }
        }, -1, 8);
        stream.close();
        assertThat(closed).isTrue();
    }
}
