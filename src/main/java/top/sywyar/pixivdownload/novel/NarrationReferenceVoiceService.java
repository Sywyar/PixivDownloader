package top.sywyar.pixivdownload.novel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.novel.db.NovelMapper;
import top.sywyar.pixivdownload.novel.db.NovelNarrationVoiceRef;
import top.sywyar.pixivdownload.tts.narration.NarrationAudioService;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationAudio;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationReferenceVoice;
import top.sywyar.pixivdownload.util.TimestampUtils;

import java.io.IOException;
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
 * <p>参考音字节存盘于 {@code data/narration-voice/{castId}/{characterId}.{ext}}（{@link RuntimeFiles}，不进
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

    public NarrationReferenceVoiceService(NovelMapper novelMapper, NarrationAudioService narrationAudioService,
                                          NarrationReferenceVoiceStore fileStore) {
        this.novelMapper = novelMapper;
        this.narrationAudioService = narrationAudioService;
        this.fileStore = fileStore;
    }

    /** 自动种子音生成结果。 */
    public enum Outcome { ADOPTED, TOO_SHORT, NO_BASE }

    public record GenerateResult(Outcome outcome, NovelNarrationVoiceRef ref) {}

    /**
     * 解析某角色的参考音：无参考音元数据 / 音频文件缺失 → {@code null}。供合成时塞进请求；最大努力，绝不抛出。
     */
    public NarrationReferenceVoice resolve(long castId, int characterId) {
        if (castId <= 0) {
            return null;
        }
        NovelNarrationVoiceRef ref = novelMapper.findNarrationVoiceRef(castId, characterId);
        if (ref == null || !ref.present()) {
            return null;
        }
        Path file = RuntimeFiles.narrationVoiceFile(castId, characterId, ref.ext());
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

    /** 某角色参考音元数据（用于试听 / 状态展示）；无则 {@code null}。 */
    public NovelNarrationVoiceRef reference(long castId, int characterId) {
        if (castId <= 0) {
            return null;
        }
        NovelNarrationVoiceRef ref = novelMapper.findNarrationVoiceRef(castId, characterId);
        return ref != null && ref.present() ? ref : null;
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
        String base = baseInstruction(castId, characterId);
        if (base == null || base.isBlank()) {
            return new GenerateResult(Outcome.NO_BASE, null);
        }
        String text = seedText == null ? "" : seedText.trim();
        NarrationAudio audio = narrationAudioService.synthesizeVoiceDesign(text, base, null);
        byte[] data = audio == null ? null : audio.data();
        String ext = extForContentType(audio == null ? null : audio.contentType());
        if (data == null || !acceptableSeed(data, ext)) {
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
        synchronized (fileStore.lockFor(castId, characterId)) {
            fileStore.deleteCharacterFiles(castId, characterId);
            novelMapper.clearNarrationVoiceReference(castId, characterId);
            novelMapper.touchNarrationCast(castId, TimestampUtils.nowMillis());
        }
    }

    // ── 内部 ─────────────────────────────────────────────────────────────────

    /**
     * 落盘 + 写库某角色的参考音元数据，按 {@code (castId, characterId)} 串行：先确认角色行存在（避免写出无元数据
     * 绑定的孤儿文件）→ 原子写文件 → 写库（{@code UPDATE} 行数为 0 时回滚刚写入的文件）→ 推进缓存键。角色不在册
     * 返回 {@code null}。
     */
    private NovelNarrationVoiceRef store(long castId, int characterId, byte[] data, String ext,
                                         String refText, String source) {
        synchronized (fileStore.lockFor(castId, characterId)) {
            // 旁白行可能尚未持久化：先确保有行。其它角色必须已在该花名册，否则不落盘（避免孤儿文件）。
            ensureVoiceRow(castId, characterId);
            if (novelMapper.findNarrationVoiceRef(castId, characterId) == null) {
                return null;
            }
            fileStore.write(castId, characterId, data, ext);
            long now = TimestampUtils.nowMillis();
            String text = refText == null || refText.isBlank() ? null : refText.trim();
            int updated = novelMapper.updateNarrationVoiceReference(castId, characterId, ext, text, source, now);
            if (updated <= 0) {
                // 并发删除等极端情况：DB 未更新，回滚刚写入的文件，避免孤儿。
                fileStore.deleteCharacterFiles(castId, characterId);
                return null;
            }
            novelMapper.touchNarrationCast(castId, now);
            return novelMapper.findNarrationVoiceRef(castId, characterId);
        }
    }

    private void ensureVoiceRow(long castId, int characterId) {
        if (characterId == NarrationCharacter.NARRATOR_ID
                && novelMapper.findNarrationVoiceRef(castId, characterId) == null) {
            novelMapper.insertNarrationVoiceIfAbsent(castId, NarrationCharacter.NARRATOR_ID, "Narrator",
                    "unknown", "unknown", NarrationCharacter.DEFAULT_NARRATOR_INSTRUCTION, false,
                    TimestampUtils.nowMillis());
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
    private static boolean acceptableSeed(byte[] data, String ext) {
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
        int sampleRate = 0;
        int channels = 0;
        int bitsPerSample = 0;
        long dataSize = -1;
        int pos = 12;
        while (pos + 8 <= data.length) {
            int id0 = data[pos] & 0xFF, id1 = data[pos + 1] & 0xFF, id2 = data[pos + 2] & 0xFF, id3 = data[pos + 3] & 0xFF;
            long chunkSize = readLEUInt(data, pos + 4);
            int body = pos + 8;
            if (id0 == 'f' && id1 == 'm' && id2 == 't' && id3 == ' ' && body + 16 <= data.length) {
                channels = (int) readLEUShort(data, body + 2);
                sampleRate = (int) readLEUInt(data, body + 4);
                bitsPerSample = (int) readLEUShort(data, body + 14);
            } else if (id0 == 'd' && id1 == 'a' && id2 == 't' && id3 == 'a') {
                dataSize = Math.min(chunkSize, data.length - (long) body);
            }
            pos = body + (int) (chunkSize + (chunkSize & 1)); // chunks word-aligned
        }
        long bytesPerSecond = (long) sampleRate * channels * (bitsPerSample / 8);
        if (dataSize <= 0 || bytesPerSecond <= 0) {
            return -1;
        }
        return (double) dataSize / bytesPerSecond;
    }

    private static long readLEUInt(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8)
                | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    private static long readLEUShort(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8);
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
