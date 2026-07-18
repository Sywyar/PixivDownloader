package top.sywyar.pixivdownload.novel.narration.audio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("小说朗读纯标点短静音")
class NarrationSilenceTest {

    @Test
    @DisplayName("应生成 300ms 的 16kHz 单声道 16-bit 全零 PCM WAV")
    void createsExpectedSilentWav() {
        NarrationAudio audio = NarrationSilence.shortPause();
        byte[] wav = audio.data();
        ByteBuffer header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(audio.contentType()).isEqualTo("audio/wav");
        assertThat(wav).hasSize(9_644);
        assertThat(new String(wav, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(new String(wav, 8, 4, StandardCharsets.US_ASCII)).isEqualTo("WAVE");
        assertThat(header.getShort(22)).isEqualTo((short) 1);
        assertThat(header.getInt(24)).isEqualTo(16_000);
        assertThat(header.getShort(34)).isEqualTo((short) 16);
        assertThat(header.getInt(40)).isEqualTo(9_600);
        assertThat(Arrays.copyOfRange(wav, 44, wav.length)).containsOnly((byte) 0);
    }
}
