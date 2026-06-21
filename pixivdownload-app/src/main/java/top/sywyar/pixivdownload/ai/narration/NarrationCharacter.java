package top.sywyar.pixivdownload.ai.narration;

/**
 * 多角色有声朗读里的一个<b>说话人音色</b>（旁白或一名角色）。这是「同一个人描述必须准确」的载体：
 * 同一角色在整章中复用<b>同一份</b> {@code controlInstruction}（音色画像），逐句脚本按 {@link #id} 引用，
 * 保证音色跨句一致。
 *
 * <p>{@code controlInstruction} 是可直接喂给富情感 TTS（VoxCPM 等）的音色描述（统一英文），刻画性别 /
 * 年龄 / 音质 / 语速 / 性格气质；逐句的情绪微调（delivery）在此基础上追加，不改动本基底画像。
 *
 * @param id                 稳定编号；{@code 0} 恒为旁白（{@link #narrator}），角色从 {@code 1} 起按首次出现编号
 * @param name               角色在原文中的称谓（原文语言，便于归属匹配与展示）；旁白为 {@code "Narrator"}
 * @param gender             {@code male} / {@code female} / {@code unknown}
 * @param age                {@code child} / {@code teen} / {@code young} / {@code middle} / {@code elderly} / {@code unknown}
 * @param controlInstruction 英文音色画像（Control Instruction）
 * @param narrator           是否为旁白
 * @param editedByUser       画像来源：{@code false}=AI 生成（可被 AI 补充 / 自动应用冲突建议覆盖），
 *                           {@code true}=用户手改锁定（AI 绝不覆盖，只能以冲突形式弹给用户处理）
 */
public record NarrationCharacter(int id, String name, String gender, String age,
                                 String controlInstruction, boolean narrator, boolean editedByUser) {

    public static final int NARRATOR_ID = 0;

    /**
     * 旁白兜底音色画像：AI 关闭 / 分析失败 / 未显式选择旁白音色时全篇用此单一旁白朗读。取
     * {@link NarratorVoicePreset#DEFAULT}（温暖女声）的画像作为单一事实源——一段写到位的详细音色描述
     * （性别 / 年龄 / 音高 / 音质 / 语速 / 情绪基线 / 咬字 / 稳定性约束），缩小 TTS 发挥空间、减轻音色漂移。
     */
    public static final String DEFAULT_NARRATOR_INSTRUCTION = NarratorVoicePreset.DEFAULT.instruction();

    /** 构造一名旁白（id 0，AI 生成来源）；{@code instruction} 为空时回退到 {@link #DEFAULT_NARRATOR_INSTRUCTION}。 */
    public static NarrationCharacter narrator(String instruction) {
        String ci = instruction == null || instruction.isBlank()
                ? DEFAULT_NARRATOR_INSTRUCTION : instruction.trim();
        return new NarrationCharacter(NARRATOR_ID, "Narrator", "unknown", "unknown", ci, true, false);
    }

    /** 默认旁白（兜底音色，AI 生成来源）。 */
    public static NarrationCharacter defaultNarrator() {
        return narrator(DEFAULT_NARRATOR_INSTRUCTION);
    }
}
