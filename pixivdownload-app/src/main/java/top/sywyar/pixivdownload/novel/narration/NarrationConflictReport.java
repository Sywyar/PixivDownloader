package top.sywyar.pixivdownload.novel.narration;

/**
 * 一条<b>待用户处理</b>的朗读音色冲突：AI 在分析中判定某个<b>用户锁定</b>（{@code edited_by_user=1}）角色的画像
 * 被正文证据完全相反 / 明显不完整，但因用户画像是权威、AI 绝不擅自覆盖，于是把冲突上报给上层提示用户手动定夺
 * （采纳建议 / 保留原值 / 改写）。
 *
 * <p>注意：AI 生成（未被用户改过）的角色遇到同类冲突会被<b>自动采纳建议</b>、不进本列表。
 *
 * @param characterId        冲突角色在名册里的 id
 * @param name               角色称谓（原文语言）
 * @param type               冲突类型：{@code contradiction}（完全相反）/ {@code incomplete}（明显不完整）
 * @param reason             AI 给出的简短英文原因
 * @param currentInstruction 角色当前（用户锁定）的英文音色画像
 * @param suggestion         AI 建议替换成的完整英文音色画像
 */
public record NarrationConflictReport(int characterId, String name, String type,
                                      String reason, String currentInstruction, String suggestion) {
}
