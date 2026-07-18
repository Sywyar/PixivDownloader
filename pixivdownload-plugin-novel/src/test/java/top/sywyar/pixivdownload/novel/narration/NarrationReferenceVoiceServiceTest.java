package top.sywyar.pixivdownload.novel.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.db.NovelNarrationVoiceRef;
import top.sywyar.pixivdownload.novel.narration.audio.NarrationAudioService;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("多角色朗读参考音服务（时长解析 / 扩展名归一）")
class NarrationReferenceVoiceServiceTest {

    @Test
    @DisplayName("wavDurationSeconds：标准 PCM WAV 头按数据块大小算出时长")
    void parsesWavDuration() {
        // 48kHz / 单声道 / 16bit，data 块 96000 字节 = 1.0 秒
        byte[] wav = pcmWav(48000, 1, 16, 96000);
        assertThat(NarrationReferenceVoiceService.wavDurationSeconds(wav)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("wavDurationSeconds：非 WAV / 过短数据返回 -1（交由字节数兜底）")
    void returnsMinusOneForNonWav() {
        assertThat(NarrationReferenceVoiceService.wavDurationSeconds(null)).isEqualTo(-1);
        assertThat(NarrationReferenceVoiceService.wavDurationSeconds(new byte[]{1, 2, 3})).isEqualTo(-1);
        assertThat(NarrationReferenceVoiceService.wavDurationSeconds("not a wav at all......".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(-1);
    }

    @Test
    @DisplayName("normalizeExt / mimeForExt：wav / mp3 / pcm 归一，未知回退 wav")
    void normalizesExtAndMime() {
        assertThat(NarrationReferenceVoiceService.normalizeExt("MP3")).isEqualTo("mp3");
        assertThat(NarrationReferenceVoiceService.normalizeExt("flac")).isEqualTo("wav");
        assertThat(NarrationReferenceVoiceService.normalizeExt(null)).isEqualTo("wav");
        assertThat(NarrationReferenceVoiceService.mimeForExt("mp3")).isEqualTo("audio/mpeg");
        assertThat(NarrationReferenceVoiceService.mimeForExt("wav")).isEqualTo("audio/wav");
        assertThat(NarrationReferenceVoiceService.mimeForExt("pcm")).isEqualTo("audio/pcm");
    }

    @Test
    @DisplayName("上传到不在花名册的角色：不落盘、不写库，返回 null（控制器据此转 404）")
    void saveUploadRejectsCharacterNotInCast() {
        NovelMapper mapper = mock(NovelMapper.class);
        NarrationReferenceVoiceStore store = mock(NarrationReferenceVoiceStore.class);
        when(store.lockFor(anyLong(), anyInt())).thenReturn(new Object());
        // 角色 5 不在册：findNarrationVoiceRef 返回 null（非旁白，ensureVoiceRow 也不会兜底建行）
        when(mapper.findNarrationVoiceRef(3L, 5)).thenReturn(null);

        NarrationReferenceVoiceService svc = new NarrationReferenceVoiceService(
                mapper, mock(NarrationAudioService.class), store, mock(NarrationReferenceVoicePaths.class));

        NovelNarrationVoiceRef ref = svc.saveUpload(3L, 5, new byte[]{1, 2, 3}, "wav", null);

        assertThat(ref).isNull();
        verify(store, never()).write(anyLong(), anyInt(), any(), any());
        verify(mapper, never()).updateNarrationVoiceReference(anyLong(), anyInt(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("上传到在册角色：原子写盘 + 写库 + 推进缓存键，返回新参考音元数据")
    void saveUploadStoresWhenCharacterPresent() {
        NovelMapper mapper = mock(NovelMapper.class);
        NarrationReferenceVoiceStore store = mock(NarrationReferenceVoiceStore.class);
        when(store.lockFor(anyLong(), anyInt())).thenReturn(new Object());
        NovelNarrationVoiceRef saved = new NovelNarrationVoiceRef(3L, 1, "wav", "hi", "upload", 1L);
        // 角色 1 在册：第一次取到无参考音的行（非 null 即在册），写库后第二次取到新元数据
        when(mapper.findNarrationVoiceRef(3L, 1)).thenReturn(
                new NovelNarrationVoiceRef(3L, 1, null, null, null, null), saved);
        when(mapper.updateNarrationVoiceReference(eq(3L), eq(1), eq("wav"), eq("hi"), eq("upload"), anyLong()))
                .thenReturn(1);

        NarrationReferenceVoiceService svc = new NarrationReferenceVoiceService(
                mapper, mock(NarrationAudioService.class), store, mock(NarrationReferenceVoicePaths.class));

        byte[] data = {1, 2, 3};
        NovelNarrationVoiceRef ref = svc.saveUpload(3L, 1, data, "wav", " hi ");

        assertThat(ref).isSameAs(saved);
        verify(store).write(3L, 1, data, "wav");
        verify(mapper).touchNarrationCast(eq(3L), anyLong());
    }

    /** 构造一个最小可解析的 PCM WAV（含 fmt + data 块）。 */
    private static byte[] pcmWav(int sampleRate, int channels, int bitsPerSample, int dataSize) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        writeAscii(out, "RIFF");
        writeLEInt(out, 36 + dataSize);
        writeAscii(out, "WAVE");
        writeAscii(out, "fmt ");
        writeLEInt(out, 16);
        writeLEShort(out, 1); // PCM
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
}
