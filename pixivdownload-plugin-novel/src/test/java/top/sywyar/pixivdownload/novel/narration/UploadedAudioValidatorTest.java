package top.sywyar.pixivdownload.novel.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("多角色朗读参考音上传校验")
public class UploadedAudioValidatorTest {

    @Test
    @DisplayName("标准 PCM WAV 应通过并识别采样参数")
    void acceptsPcmWav() {
        UploadedAudioValidator.Result result = UploadedAudioValidator.validate(pcmWav(48_000, 1, 16, 96_000));

        assertThat(result).isNotNull();
        assertThat(result.extension()).isEqualTo("wav");
        assertThat(result.sampleRate()).isEqualTo(48_000);
        assertThat(result.channels()).isEqualTo(1);
    }

    @Test
    @DisplayName("伪 WAV 头或异常采样率应拒绝")
    void rejectsInvalidWavShape() {
        assertThat(UploadedAudioValidator.validate("not a wav".getBytes(StandardCharsets.UTF_8))).isNull();
        assertThat(UploadedAudioValidator.validate(pcmWav(192_000, 1, 16, 384_000))).isNull();
    }

    @Test
    @DisplayName("连续 MP3 Layer III 帧应通过")
    void acceptsMp3Frames() {
        UploadedAudioValidator.Result result = UploadedAudioValidator.validate(mp3Frames(4));

        assertThat(result).isNotNull();
        assertThat(result.extension()).isEqualTo("mp3");
        assertThat(result.sampleRate()).isEqualTo(44_100);
        assertThat(result.channels()).isEqualTo(2);
    }

    @Test
    @DisplayName("HTML 内容伪造为 MP3 应拒绝")
    void rejectsHtmlAsMp3() {
        assertThat(UploadedAudioValidator.validate("<html>not audio</html>".getBytes(StandardCharsets.UTF_8)))
                .isNull();
    }

    @Test
    @DisplayName("声明扩展名与 MIME 冲突时返回不可匹配标记")
    void declaredExtensionReportsConflict() {
        String declared = UploadedAudioValidator.declaredExtension("audio/mpeg", "voice.wav");

        assertThat(declared).isNotEqualTo("mp3");
        assertThat(declared).isNotEqualTo("wav");
    }

    public static byte[] pcmWav(int sampleRate, int channels, int bitsPerSample, int dataSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        writeAscii(out, "RIFF");
        writeLEInt(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLEInt(out, 16);
        writeLEShort(out, 1);
        writeLEShort(out, channels);
        writeLEInt(out, sampleRate);
        writeLEInt(out, byteRate);
        writeLEShort(out, blockAlign);
        writeLEShort(out, bitsPerSample);
        writeAscii(out, "data");
        writeLEInt(out, dataSize);
        out.writeBytes(new byte[dataSize]);
        return out.toByteArray();
    }

    public static byte[] mp3Frames(int count) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int frameLength = 417;
        for (int i = 0; i < count; i++) {
            writeBEInt(out, 0xFFFB_9000);
            out.writeBytes(new byte[frameLength - 4]);
        }
        return out.toByteArray();
    }

    private static void writeAscii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeLEInt(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 24) & 0xFF);
    }

    private static void writeLEShort(ByteArrayOutputStream out, int v) {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static void writeBEInt(ByteArrayOutputStream out, int v) {
        out.write((v >> 24) & 0xFF);
        out.write((v >> 16) & 0xFF);
        out.write((v >> 8) & 0xFF);
        out.write(v & 0xFF);
    }
}
