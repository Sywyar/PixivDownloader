package top.sywyar.pixivdownload.novel.narration.analysis;

import top.sywyar.pixivdownload.novel.narration.analysis.NarrationCharacter;
import top.sywyar.pixivdownload.novel.narration.analysis.NarrationConflict;
import top.sywyar.pixivdownload.novel.narration.analysis.NarrationLineVoice;

import java.util.List;
import java.util.Map;

/**
 * 一段句子经合并单次 AI 分析后的<b>纯结果</b>（无 DB / 无引擎）：
 * {@link NarrationScriptService#analyzeSegment} 的产物，交给编排层（{@code novel.narration.NovelNarrationCastService}）
 * 入册新角色、按编辑来源路由补充 / 冲突，并把逐句 speaker 的临时 id 重映射为真实 id。
 *
 * @param lines             与本段输入句数等长、按下标升序的逐句归属（speaker 可能是新角色的临时 id）
 * @param newCharacters     本段发现的新角色（带模型分配的临时 id，来源恒为 AI 生成）
 * @param updatedCharacters 对已有角色的兼容性补充：「角色 id → 补充后的英文画像」
 * @param renamedCharacters 对已有角色的受控改名：「角色 id → 更准确的原文称谓」（第一人称主角真实姓名后段揭晓时按同一 id 并入）
 * @param conflicts         对已有角色的冲突上报（仅含合法 type + 非空 suggestion 的条目）
 */
public record NarrationSegmentAnalysis(
        List<NarrationLineVoice> lines,
        List<NarrationCharacter> newCharacters,
        Map<Integer, String> updatedCharacters,
        Map<Integer, String> renamedCharacters,
        List<NarrationConflict> conflicts
) {

    /** 兜底空结果：每句归旁白、无新角色 / 补充 / 改名 / 冲突。用于 AI 关闭 / 调用失败 / 回复不可解析时。 */
    public static NarrationSegmentAnalysis narratorFallback(int sentenceCount) {
        List<NarrationLineVoice> lines = new java.util.ArrayList<>(Math.max(sentenceCount, 0));
        for (int i = 0; i < sentenceCount; i++) {
            lines.add(NarrationLineVoice.narratorAt(i));
        }
        return new NarrationSegmentAnalysis(lines, List.of(), Map.of(), Map.of(), List.of());
    }
}
