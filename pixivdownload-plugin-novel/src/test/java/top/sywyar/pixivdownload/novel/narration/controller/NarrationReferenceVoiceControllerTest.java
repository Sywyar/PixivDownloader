package top.sywyar.pixivdownload.novel.narration.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import top.sywyar.pixivdownload.common.ErrorResponse;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.novel.TestRuntimePathProvider;
import top.sywyar.pixivdownload.novel.db.NovelNarrationVoiceRef;
import top.sywyar.pixivdownload.novel.narration.NarrationReferenceVoiceService;
import top.sywyar.pixivdownload.novel.narration.NarrationReferenceVoicePaths;
import top.sywyar.pixivdownload.novel.narration.NovelNarrationCastService;
import top.sywyar.pixivdownload.novel.narration.UploadedAudioValidatorTest;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("多角色朗读参考音控制器上传安全")
class NarrationReferenceVoiceControllerTest {

    @Mock
    private NovelNarrationCastService castService;
    @Mock
    private NarrationReferenceVoiceService referenceVoiceService;
    @TempDir
    private Path tempDir;

    private TestRuntimePathProvider runtimePaths;
    private NarrationReferenceVoicePaths paths;
    private NarrationReferenceVoiceController controller;

    @BeforeEach
    void setUp() {
        runtimePaths = new TestRuntimePathProvider(tempDir);
        paths = new NarrationReferenceVoicePaths(runtimePaths);
        controller = new NarrationReferenceVoiceController(
                castService, referenceVoiceService, messageResolver(), paths);
    }

    @Test
    @DisplayName("上传：HTML 内容伪造成 audio/mpeg 应拒绝且不落服务")
    void uploadRejectsMimeSpoofedHtml() {
        when(castService.exists(3L)).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "evil.mp3",
                "audio/mpeg",
                "<html>not audio</html>".getBytes(StandardCharsets.UTF_8)
        );

        ResponseEntity<?> response = controller.upload(3L, 1, file, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isInstanceOf(ErrorResponse.class);
        verify(referenceVoiceService, never()).saveUpload(eq(3L), eq(1), any(), any(), any());
    }

    @Test
    @DisplayName("上传：真实类型与声明类型不一致应拒绝")
    void uploadRejectsDeclaredTypeMismatch() {
        when(castService.exists(3L)).thenReturn(true);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "voice.mp3",
                "audio/mpeg",
                UploadedAudioValidatorTest.pcmWav(48_000, 1, 16, 96_000)
        );

        ResponseEntity<?> response = controller.upload(3L, 1, file, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(referenceVoiceService, never()).saveUpload(eq(3L), eq(1), any(), any(), any());
    }

    @Test
    @DisplayName("上传：合法 WAV 按真实类型保存")
    void uploadAcceptsValidWav() {
        when(castService.exists(3L)).thenReturn(true);
        byte[] data = UploadedAudioValidatorTest.pcmWav(48_000, 1, 16, 96_000);
        when(referenceVoiceService.saveUpload(eq(3L), eq(1), any(byte[].class), eq("wav"), eq(" seed ")))
                .thenReturn(new NovelNarrationVoiceRef(3L, 1, "wav", "seed", "upload", 1L));
        MockMultipartFile file = new MockMultipartFile("file", "voice.wav", "audio/wav", data);

        ResponseEntity<?> response = controller.upload(3L, 1, file, " seed ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        NarrationReferenceVoiceController.ReferenceStatus status =
                (NarrationReferenceVoiceController.ReferenceStatus) response.getBody();
        assertThat(status.ext()).isEqualTo("wav");
    }

    @Test
    @DisplayName("预览：参考音响应带 nosniff")
    void previewAddsNosniffHeader() throws Exception {
        byte[] data = UploadedAudioValidatorTest.pcmWav(48_000, 1, 16, 96_000);
        Files.write(paths.file(3L, 1, "wav"), data);
        when(referenceVoiceService.reference(3L, 1))
                .thenReturn(new NovelNarrationVoiceRef(3L, 1, "wav", null, "upload", 1L));

        ResponseEntity<?> response = controller.preview(3L, 1);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getContentType().toString()).isEqualTo("audio/wav");
    }

    private static MessageResolver messageResolver() {
        ReloadableResourceBundleMessageSource source = new ReloadableResourceBundleMessageSource();
        source.setBasenames(
                "classpath:i18n/messages",
                "classpath:i18n/ValidationMessages",
                "classpath:i18n/tts/messages",
                "classpath:i18n/web/narration"
        );
        source.setDefaultEncoding("UTF-8");
        source.setUseCodeAsDefaultMessage(true);
        return TestI18nBeans.messageResolver(source);
    }
}
