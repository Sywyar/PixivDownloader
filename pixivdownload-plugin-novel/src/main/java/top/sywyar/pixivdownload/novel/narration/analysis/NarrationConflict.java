package top.sywyar.pixivdownload.novel.narration.analysis;

/**
 * AI 在合并分析中对<b>已有角色</b>上报的一条音色画像冲突：当前画像被本段证据明确且显著地证伪
 * （{@link #TYPE_CONTRADICTION}）或证明明显不完整（{@link #TYPE_INCOMPLETE}）。
 *
 * <p>已有音色画像保持不变，AI 只上报冲突，由用户决定是否采纳 {@link #suggestion}。
 *
 * @param characterId 冲突角色在名册里的 id（{@code 0} 旁白也可被上报冲突）
 * @param type        {@link #TYPE_CONTRADICTION}（完全相反）或 {@link #TYPE_INCOMPLETE}（明显不完整）
 * @param reason      简短英文原因
 * @param suggestion  AI 建议替换成的完整英文音色画像
 */
public record NarrationConflict(int characterId, String type, String reason, String suggestion) {

    public static final String TYPE_CONTRADICTION = "contradiction";
    public static final String TYPE_INCOMPLETE = "incomplete";
}
