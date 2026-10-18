package top.sywyar.pixivdownload.novel.narration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
import top.sywyar.pixivdownload.novel.narration.analysis.NarrationCharacter;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.db.NovelNarrationVoiceRef;
import top.sywyar.pixivdownload.novel.narration.audio.NarrationAudioService;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationReferenceVoice;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 多角色朗读「参考音 / 标准音」解析与管理服务（引擎无关）：把 {@code (castId, characterId)} 解析成
 * {@link NarrationReferenceVoice}（原始音频字节 + MIME + 转录），供 {@link NovelNarrationScriptService} 在合成时塞进
 * {@link top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceRequest}——参考音解析<b>不埋进引擎</b>，引擎只认值对象。
 *
 * <p>参考音字节存盘于 {@code data/novel/narration-voice/{castId}/{characterId}.{ext}}（经
 * {@link NarrationReferenceVoicePaths} 解析，不进
 * {@code rootFolder}）；元数据（扩展名 / 转录 / 来源 / 时间）落 {@code novel_narration_voices} 的参考音列。
 *
 * <p>三种来源：
 * <ul>
 *   <li><b>自动生成的标准音</b>（{@code auto}）：用角色当前音色画像走 Voice Design 渲一段<b>示例正文</b>（由用户决定、
 *       不叠 delivery），作为该角色跨章一致的「标准音」；过短 / 异常不自动采用，提示用户重试。</li>
 *   <li><b>用户上传</b>（{@code upload}）：真人参考音（wav/mp3），可附转录。</li>
 *   <li><b>无</b>：退回内联 voice-design。</li>
 * </ul>
 * 任何参考音变更都会 {@code touchNarrationCast} 推进花名册 {@code updated_time}，使前端音频缓存键（含 {@code castUpdatedTime}）失效、逐句音频自动重算。
 */
@Slf4j
@Service
public class NarrationReferenceVoiceService {

    /** 自动种子音最小可接受时长（秒）：低于此值视为生成异常、不自动采用。 */
    private static final double MIN_SEED_SECONDS = 1.0;
    /** 无法解析时长时（如 pcm）按字节数兜底：低于此值视为异常。 */
    private static final int MIN_SEED_BYTES = 16_000;

    public static final String SOURCE_AUTO = "auto";
    public static final String SOURCE_UPLOAD = "upload";

    private final NovelMapper novelMapper;
    private final NarrationAudioService narrationAudioService;
    private final NarrationReferenceVoiceStore fileStore;
    private final NarrationReferenceVoicePaths paths;
    private final DatabaseAvailability database;

    public NarrationReferenceVoiceService(NovelMapper novelMapper, NarrationAudioService narrationAudioService,
                                          NarrationReferenceVoiceStore fileStore,
                                          NarrationReferenceVoicePaths paths, DatabaseAvailability database) {
        this.novelMapper = novelMapper;
        this.narrationAudioService = narrationAudioService;
        this.fileStore = fileStore;
        this.paths = paths;
        this.database = database;
    }

    /** 自动种子音生成结果。 */
    public enum Outcome { ADOPTED, TOO_SHORT, NO_BASE }

    public record GenerateResult(Outcome outcome, NovelNarrationVoiceRef ref) {}

    /**
     * 在角色锁内读取元数据与音频；未配置、文件缺失或读取失败时返回 {@code null}。
     */
    public NarrationReferenceVoice resolve(long castId, int characterId) {
        if (castId <= 0) {
            return null;
        }
        return fileStore.withLock(castId, characterId, () -> readReference(castId, characterId));
    }

    private NarrationReferenceVoice readReference(long castId, int characterId) {
        NovelNarrationVoiceRef ref = novelMapper.findNarrationVoiceRef(castId, characterId);
        if (ref == null || !ref.present()) {
            return null;
        }
        Path file = paths.file(castId, characterId, ref.ext());
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            byte[] audio = Files.readAllBytes(file);
            if (audio.length == 0) {
                return null;
            }
            return new NarrationReferenceVoice(audio, mimeForExt(ref.ext()), ref.text());
        } catch (IOException e) {
            log.warn("read narration reference voice failed: castId={}, characterId={}, err={}",
                    castId, characterId, e.getMessage());
            return null;
        }
    }

    /** 某花名册各角色的参考音来源（characterId → {@code auto}/{@code upload}），供前端展示状态。 */
    public Map<Integer, String> sources(long castId) {
        Map<Integer, String> out = new LinkedHashMap<>();
        if (castId <= 0) {
            return out;
        }
        for (NovelNarrationVoiceRef ref : novelMapper.findNarrationVoiceRefs(castId)) {
            if (ref.present()) {
                out.put(ref.characterId(), ref.source() == null ? SOURCE_AUTO : ref.source());
            }
        }
        return out;
    }

    /**
     * 自动生成并采用某角色的「标准音」：用其当前音色画像走 Voice Design 渲 {@code seedText}、落盘 + 落库。
     * {@code seedText} 是<b>用户决定</b>的示例正文（控制器在用户留空时回退 i18n 默认句），同时作为参考音的转录
     * （ref_text）。过短 / 异常不采用。
     *
     * @throws top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceException 引擎不可用 / 合成失败（控制器转 502）
     */
    public GenerateResult generateSeed(long castId, int characterId, String seedText) {
        database.requireAvailable();
        String base = baseInstruction(castId, characterId);
        if (base == null || base.isBlank()) {
            return new GenerateResult(Outcome.NO_BASE, null);
        }
        String text = seedText == null ? "" : seedText.trim();
        database.requireAvailable();
        NarrationAudio audio = narrationAudioService.synthesizeVoiceDesign(text, base);
        database.requireAvailable();
        byte[] data = audio == null ? null : audio.data();
        String ext = extForContentType(audio == null ? null : audio.contentType());
        if (data == null || !acceptableSeed(data)) {
            return new GenerateResult(Outcome.TOO_SHORT, null);
        }
        NovelNarrationVoiceRef ref = store(castId, characterId, data, ext, text, SOURCE_AUTO);
        // store 返回 null 表示该角色不在花名册中（理论上不会发生：能取到 base 即在册）；防御性按 NO_BASE 处理。
        return ref == null ? new GenerateResult(Outcome.NO_BASE, null)
                : new GenerateResult(Outcome.ADOPTED, ref);
    }

    /**
     * 保存用户上传的参考音（wav/mp3）+ 可选转录，标记来源为 {@code upload}。角色不在该花名册中（且非旁白）时
     * <b>不落盘</b>、返回 {@code null}（控制器转 404），避免产生孤儿文件。
     */
    public NovelNarrationVoiceRef saveUpload(long castId, int characterId, byte[] data, String ext, String refText) {
        return store(castId, characterId, data, normalizeExt(ext), refText, SOURCE_UPLOAD);
    }

    /** 删除某角色的参考音（文件 + 库内元数据），并推进花名册 updated_time 使缓存失效。 */
    public void delete(long castId, int characterId) {
        fileStore.withLock(castId, characterId, () -> {
            database.requireAvailable();
            fileStore.deleteCharacterFiles(castId, characterId, () -> {
                database.requireAvailable();
                novelMapper.clearNarrationVoiceReference(castId, characterId);
                novelMapper.touchNarrationCast(castId, System.currentTimeMillis());
            });
            return null;
        });
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /**
     * 按角色串行发布音频与记录；角色消失或记录写入失败时，由宿主恢复旧文件。
     */
    private NovelNarrationVoiceRef store(long castId, int characterId, byte[] data, String ext,
                                         String refText, String source) {
        return fileStore.withLock(castId, characterId, () -> {
            database.requireAvailable();
            if (characterId != NarrationCharacter.NARRATOR_ID
                    && novelMapper.findNarrationVoiceRef(castId, characterId) == null) {
                return null;
            }
            database.requireAvailable();
            long now = System.currentTimeMillis();
            String text = refText == null || refText.isBlank() ? null : refText.trim();
            try {
                fileStore.write(castId, characterId, data, ext, () -> {
                    database.requireAvailable();
                    ensureVoiceRow(castId, characterId);
                    if (novelMapper.updateNarrationVoiceReference(castId, characterId, ext, text, source, now) <= 0) {
                        throw new MissingVoiceException();
                    }
                    novelMapper.touchNarrationCast(castId, now);
                });
            } catch (MissingVoiceException missing) {
                if (missing.getSuppressed().length != 0) throw missing;
                return null;
            }
            return new NovelNarrationVoiceRef(castId, characterId, ext, text, source, now);
        });
    }

    private static final class MissingVoiceException extends RuntimeException {}

    private void ensureVoiceRow(long castId, int characterId) {
        if (characterId == NarrationCharacter.NARRATOR_ID
                && novelMapper.findNarrationVoiceRef(castId, characterId) == null) {
            novelMapper.insertNarrationVoiceIfAbsent(castId, NarrationCharacter.NARRATOR_ID, "Narrator",
                    "unknown", "unknown", NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION, false,
                    System.currentTimeMillis());
        }
    }

    /** 取角色当前音色画像作种子生成基底；旁白缺画像时回退默认旁白画像。 */
    private String baseInstruction(long castId, int characterId) {
        for (NarrationCharacter c : novelMapper.findNarrationVoices(castId)) {
            if (c.id() == characterId) {
                if (c.controlInstruction() != null && !c.controlInstruction().isBlank()) {
                    return c.controlInstruction().trim();
                }
                return characterId == NarrationCharacter.NARRATOR_ID
                        ? NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION : null;
            }
        }
        return characterId == NarrationCharacter.NARRATOR_ID
                ? NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION : null;
    }

    /** 校验种子音是否够长：优先解析 WAV 时长，无法解析时按字节数兜底。 */
    private static boolean acceptableSeed(byte[] data) {
        if (data.length < MIN_SEED_BYTES) {
            return false;
        }
        double seconds = wavDurationSeconds(data);
        return seconds < 0 || seconds >= MIN_SEED_SECONDS;
    }

    /** 解析标准 PCM WAV 头得到时长（秒）；非 WAV / 解析失败返回 -1。 */
    static double wavDurationSeconds(byte[] data) {
        if (data == null || data.length < 44) {
            return -1;
        }
        if (data[0] != 'R' || data[1] != 'I' || data[2] != 'F' || data[3] != 'F'
                || data[8] != 'W' || data[9] != 'A' || data[10] != 'V' || data[11] != 'E') {
            return -1;
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int sampleRate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        long dataSize = -1;
        int pos = 12;
        while (pos <= data.length - 8) {
            int id0 = data[pos] & 0xFF, id1 = data[pos + 1] & 0xFF, id2 = data[pos + 2] & 0xFF, id3 = data[pos + 3] & 0xFF;
            long chunkSize = Integer.toUnsignedLong(buffer.getInt(pos + 4));
            int body = pos + 8;
            long next = body + chunkSize + (chunkSize & 1L);
            if (next > data.length) {
                return -1;
            }
            if (id0 == 'f' && id1 == 'm' && id2 == 't' && id3 == ' ' && chunkSize >= 16) {
                channels = Short.toUnsignedInt(buffer.getShort(body + 2));
                sampleRate = buffer.getInt(body + 4);
                bitsPerSample = Short.toUnsignedInt(buffer.getShort(body + 14));
            } else if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
                dataSize = chunkSize;
            }
            pos = (int) next;
        }
        long bytesPerSecond = (long) sampleRate * channels * (bitsPerSample / 8);
        if (dataSize <= 0 || bytesPerSecond <= 0) {
            return -1;
        }
        return (double) dataSize / bytesPerSecond;
    }

    /** 受支持的参考音扩展名归一：未知 → wav。 */
    public static String normalizeExt(String ext) {
        if (ext == null) {
            return "wav";
        }
        String e = ext.trim().toLowerCase(Locale.ROOT);
        return switch (e) {
            case "mp3" -> "mp3";
            case "pcm" -> "pcm";
            default -> "wav";
        };
    }

    private static String extForContentType(String contentType) {
        if (contentType == null) {
            return "wav";
        }
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (ct.contains("mpeg") || ct.contains("mp3")) {
            return "mp3";
        }
        if (ct.contains("pcm")) {
            return "pcm";
        }
        return "wav";
    }

    public static String mimeForExt(String ext) {
        return switch (normalizeExt(ext)) {
            case "mp3" -> "audio/mpeg";
            case "pcm" -> "audio/pcm";
            default -> "audio/wav";
        };
    }
}
