package top.sywyar.pixivdownload.novel.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.novel.TestRuntimePathProvider;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.db.NovelNarrationVoiceRef;
import top.sywyar.pixivdownload.novel.narration.audio.NarrationAudioService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static top.sywyar.pixivdownload.novel.narration.UploadedAudioValidatorTest.pcmWav;

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
                mapper, mock(NarrationAudioService.class), store, mock(NarrationReferenceVoicePaths.class), () -> true);

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
                mapper, mock(NarrationAudioService.class), store, mock(NarrationReferenceVoicePaths.class), () -> true);

        byte[] data = {1, 2, 3};
        NovelNarrationVoiceRef ref = svc.saveUpload(3L, 1, data, "wav", " hi ");

        assertThat(ref).isSameAs(saved);
        verify(store).write(3L, 1, data, "wav");
        verify(mapper).touchNarrationCast(eq(3L), anyLong());
    }

    @Test
    @DisplayName("等待参考音锁期间数据库失效，取得锁后拒绝删除且保留音频和元数据")
    void rejectsDeletionAfterWaitingForLock(@TempDir Path temp) throws Exception {
        AtomicBoolean available = new AtomicBoolean(true);
        var paths = new NarrationReferenceVoicePaths(new TestRuntimePathProvider(temp), available::get);
        var store = spy(new NarrationReferenceVoiceStore(paths, available::get));
        store.write(7L, 1, new byte[]{1, 2, 3}, "wav");
        Path original = paths.file(7L, 1, "wav");
        NovelMapper mapper = mock(NovelMapper.class);
        var service = new NarrationReferenceVoiceService(
                mapper, mock(NarrationAudioService.class), store, paths, available::get);
        Object lock = store.lockFor(7L, 1);
        CountDownLatch requested = new CountDownLatch(1);
        doAnswer(call -> { requested.countDown(); return lock; }).when(store).lockFor(7L, 1);
        var executor = Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> deletion;
            synchronized (lock) {
                deletion = executor.submit(() -> service.delete(7L, 1));
                assertThat(requested.await(5, TimeUnit.SECONDS)).isTrue();
                available.set(false);
            }
            assertThatThrownBy(() -> deletion.get(5, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(DatabaseUnavailableException.class);
            verify(store, never()).deleteCharacterFiles(anyLong(), anyInt());
            verifyNoInteractions(mapper);
            assertThat(Files.readAllBytes(original)).containsExactly(1, 2, 3);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("上传校验角色后数据库失效，不发布新参考音")
    void rejectsUploadAfterDatabaseReadInvalidates() {
        AtomicBoolean available = new AtomicBoolean(true);
        NovelMapper mapper = mock(NovelMapper.class);
        NarrationReferenceVoiceStore store = mock(NarrationReferenceVoiceStore.class);
        when(store.lockFor(anyLong(), anyInt())).thenReturn(new Object());
        when(mapper.findNarrationVoiceRef(3L, 5)).thenAnswer(call -> {
            available.set(false);
            return new NovelNarrationVoiceRef(3L, 5, "wav", null, "upload", 1L);
        });
        var service = new NarrationReferenceVoiceService(mapper, mock(NarrationAudioService.class),
                store, mock(NarrationReferenceVoicePaths.class), available::get);

        assertThatThrownBy(() -> service.saveUpload(3L, 5, new byte[]{9}, "mp3", null))
                .isInstanceOf(DatabaseUnavailableException.class);
        verify(store, never()).write(anyLong(), anyInt(), any(), any());
        verify(mapper, never()).updateNarrationVoiceReference(anyLong(), anyInt(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("种子音合成期间数据库失效，不采用返回音频")
    void rejectsSeedReturnedAfterInvalidation() {
        AtomicBoolean available = new AtomicBoolean(true);
        NovelMapper mapper = mock(NovelMapper.class);
        NarrationAudioService audio = mock(NarrationAudioService.class);
        NarrationReferenceVoiceStore store = mock(NarrationReferenceVoiceStore.class);
        when(audio.synthesizeVoiceDesign(any(), any())).thenAnswer(call -> {
            available.set(false);
            return new NarrationAudio(pcmWav(48000, 1, 16, 96000), "audio/wav");
        });
        var service = new NarrationReferenceVoiceService(mapper, audio, store,
                mock(NarrationReferenceVoicePaths.class), available::get);

        assertThatThrownBy(() -> service.generateSeed(3L, 0, "sample"))
                .isInstanceOf(DatabaseUnavailableException.class);
        verifyNoInteractions(store);
        verify(mapper, never()).updateNarrationVoiceReference(anyLong(), anyInt(), any(), any(), any(), any());
    }
}
