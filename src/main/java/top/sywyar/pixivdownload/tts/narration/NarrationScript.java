package top.sywyar.pixivdownload.tts.narration;

import top.sywyar.pixivdownload.ai.narration.NarrationCharacter;

import java.util.List;

/**
 * 一段文本的<b>多角色朗读脚本</b>：选角名册 + 逐句的「文本 → 说话人 → Control Instruction」。这是情感/选角分析
 * 层交给上层 TTS 引擎适配器的最终产物——适配器只需按 {@link Line#controlInstruction()} 把每句喂给引擎即可。
 *
 * @param cast       名册（旁白居首，id 0）；至少含一名旁白
 * @param lines      与输入句子等长、按朗读顺序的逐句脚本
 * @param multiVoice 是否识别出旁白以外的角色（false 表示全篇单一旁白朗读，如 AI 关闭 / 选角失败）
 */
public record NarrationScript(List<NarrationCharacter> cast, List<Line> lines, boolean multiVoice) {

    /**
     * 脚本中的一行。
     *
     * @param index              句子下标
     * @param text               原句文本
     * @param speakerId          名册里的说话人 id（0 为旁白）
     * @param speakerName        说话人称谓（便于展示 / 调试）
     * @param delivery           本句情绪 / 语气微调（短英文，可为空串）；引擎适配器可据此映射 rate / pitch 等
     * @param controlInstruction 已合成的最终 Control Instruction（角色基底音色画像 + 本句情绪微调），可直接喂 TTS
     */
    public record Line(int index, String text, int speakerId, String speakerName,
                       String delivery, String controlInstruction) {
    }
}
