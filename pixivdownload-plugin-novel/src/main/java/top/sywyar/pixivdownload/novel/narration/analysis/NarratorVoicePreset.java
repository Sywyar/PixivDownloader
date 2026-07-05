package top.sywyar.pixivdownload.novel.narration.analysis;

import java.util.List;

/**
 * 旁白（cast id 0）的<b>音色预设</b>：一组开箱即用的固定英文音色画像（Control Instruction），供小说首次朗读分析时
 * 让用户选定旁白基调并<b>锁定</b>（{@link NarrationCharacter#editedByUser()}=true，AI 之后绝不漂移）。
 *
 * <p>「富感情朗读」的旁白此前常被反馈<b>音色飘忽不定</b>，根因之一是默认画像过于宽泛（给 TTS 留了过大发挥空间），
 * 且默认旁白来源为 AI 生成、其画像可被分段分析跨段重写。这里把每个预设写到位——逐条限定
 * <b>性别 / 年龄 / 音高 / 音质 / 共鸣 / 呼吸 / 语速 / 节奏 / 音量 / 情绪基线 / 咬字 / 口音 / 稳定性</b>——
 * 缩小模型发挥空间；用户选定后锁定，跨段不再被改写。
 *
 * <p>音色画像<b>统一英文</b>（项目通用约束：喂给富情感 TTS 的 Control Instruction 与 LLM 提示词均固定英文，
 * 不随界面语言变化；界面标签走 i18n）。{@link #DEFAULT} 同时作为 AI 关闭 / 分析失败 / 未显式选择时的<b>全局兜底</b>
 * （{@link NarrationCharacter#DEFAULT_NARRATOR_INSTRUCTION} 即引用其 {@link #instruction()}）。
 */
public enum NarratorVoicePreset {

    /** 温暖女声（默认 / 兜底）：成年女性、三十出头、中音区、温暖清亮平稳。 */
    WARM_FEMALE("warm-female",
            "A single, consistent third-person storytelling narrator. Apparent gender: female. "
                    + "Age: an adult in her early thirties. Pitch: a steady mid-range. Timbre: warm, clear and "
                    + "smooth, with gentle chest resonance and a soft, lightly breathy edge. Baseline affect: calm, "
                    + "composed and quietly engaged — observant, never theatrical. Pace: even and measured at a "
                    + "natural read-aloud speed, with brief natural pauses at punctuation. Volume: moderate and "
                    + "constant. Articulation: clean, crisp enunciation in a neutral, accent-light standard register. "
                    + "Keep this exact voice unchanged from line to line — no drift in pitch, timbre or energy."),

    /** 沉稳男声：成年男性、近四十、中低音区、温暖醇厚略带磨砂感。 */
    CALM_MALE("calm-male",
            "A single, consistent third-person storytelling narrator. Apparent gender: male. "
                    + "Age: an adult in his late thirties. Pitch: low-to-mid. Timbre: warm, clear and smooth, with "
                    + "rich chest resonance and a soft, faintly gravelly edge. Baseline affect: calm, composed and "
                    + "quietly engaged — observant, never theatrical. Pace: even and measured at a natural "
                    + "read-aloud speed, with brief natural pauses at punctuation. Volume: moderate and constant. "
                    + "Articulation: clean, crisp enunciation in a neutral, accent-light standard register. Keep this "
                    + "exact voice unchanged from line to line — no drift in pitch, timbre or energy."),

    /** 中性旁白：性别中性、年龄模糊、平衡中音区，不偏男女。 */
    NEUTRAL("neutral",
            "A single, consistent third-person storytelling narrator. Apparent gender: neutral and "
                    + "androgynous, neither distinctly male nor female. Age: an age-ambiguous adult. Pitch: a "
                    + "balanced mid-range. Timbre: warm, clear and smooth, with light resonance and soft edges. "
                    + "Baseline affect: calm, composed and quietly engaged — observant, never theatrical. Pace: "
                    + "even and measured at a natural read-aloud speed, with brief natural pauses at punctuation. "
                    + "Volume: moderate and constant. Articulation: clean, crisp enunciation in a neutral, "
                    + "accent-light standard register. Keep this exact voice unchanged from line to line — no "
                    + "drift in pitch, timbre or energy.");

    /** 默认 / 兜底旁白预设。 */
    public static final NarratorVoicePreset DEFAULT = WARM_FEMALE;

    private final String id;
    private final String instruction;

    NarratorVoicePreset(String id, String instruction) {
        this.id = id;
        this.instruction = instruction;
    }

    /** 稳定的 kebab-case 标识（如 {@code warm-female}）；前端按此映射 i18n 标签、提交时回传。 */
    public String id() {
        return id;
    }

    /** 固定英文音色画像（喂给富情感 TTS 的 Control Instruction）。 */
    public String instruction() {
        return instruction;
    }

    /** 全部预设（声明顺序）。 */
    public static List<NarratorVoicePreset> all() {
        return List.of(values());
    }

    /** 按 {@link #id()} 查找预设；未知 / 空返回 {@code null}。 */
    public static NarratorVoicePreset byId(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String key = id.trim();
        for (NarratorVoicePreset p : values()) {
            if (p.id.equals(key)) {
                return p;
            }
        }
        return null;
    }
}
