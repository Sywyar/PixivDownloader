package top.sywyar.pixivdownload.novel.narration;

/**
 * 待用户处理的朗读音色冲突：保留角色已有画像，由用户决定采纳建议、保留原值或改写。
 *
 * @param characterId        冲突角色在名册里的 id
 * @param name               角色称谓（原文语言）
 * @param type               冲突类型：{@code contradiction}（完全相反）/ {@code incomplete}（明显不完整）
 * @param reason             AI 给出的简短英文原因
 * @param currentInstruction 角色当前的英文音色画像
 * @param suggestion         AI 建议替换成的完整英文音色画像
 */
public record NarrationConflictReport(int characterId, String name, String type,
                                      String reason, String currentInstruction, String suggestion) {
}
