package top.sywyar.pixivdownload.novel.narration.analysis;

/**
 * 归一后的单句说话人归属（合并分析的逐句输出形态）。由
 * {@link NarrationAnalysisResponse#normalizedTo(int, java.util.Set)} 产出，严格对齐到输入句数。
 *
 * @param index     句子下标（段内归一时为段内下标；累积成整章脚本时为全局下标）
 * @param speakerId 名册里的说话人 id（{@code 0} 为旁白；段内可能是新角色的临时 id，待编排层重映射为真实 id）
 * @param delivery  逐句情绪 / 语气微调（短英文，可为空串）；只调制这一句，不改角色基底音色画像
 */
public record NarrationLineVoice(int index, int speakerId, String delivery) {

    public static NarrationLineVoice narratorAt(int index) {
        return new NarrationLineVoice(index, NarrationCharacter.NARRATOR_ID, "");
    }
}
