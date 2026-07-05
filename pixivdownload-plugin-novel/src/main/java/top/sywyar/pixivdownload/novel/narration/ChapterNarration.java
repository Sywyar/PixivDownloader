package top.sywyar.pixivdownload.novel.narration;

import top.sywyar.pixivdownload.novel.narration.analysis.NarrationScript;

import java.util.List;

/**
 * 一章小说经合并单次按段分析后的产物：完整逐句朗读脚本 + 未解决的冲突列表。供上层（控制器 / UI）驱动多角色
 * 朗读，并把 {@link #conflicts} 弹给用户手动定夺。
 *
 * @param script    逐句脚本（每行带 speaker / delivery / 合成后的 Control Instruction）；始终与输入句子等长
 * @param conflicts 未解决的冲突（仅针对用户锁定角色；AI 生成角色的冲突已自动采纳建议，不在此列）
 * @param castId    本次分析实际所用花名册 ID（{@code 0} 表示无花名册 / 纯旁白），供上层落库脚本行
 */
public record ChapterNarration(NarrationScript script, List<NarrationConflictReport> conflicts, long castId) {
}
