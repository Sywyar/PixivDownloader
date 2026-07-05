package top.sywyar.pixivdownload.novel.narration.analysis;

/**
 * AI 在合并分析中对<b>已有角色</b>上报的一条音色画像冲突：当前画像被本段证据明确且显著地证伪
 * （{@link #TYPE_CONTRADICTION}）或证明明显不完整（{@link #TYPE_INCOMPLETE}）。
 *
 * <p>这是「AI 绝不擅自覆盖用户画像」的载体——AI 只<b>上报</b>冲突，是否采纳由编排层按角色的编辑来源裁决：
 * AI 生成的画像自动采纳 {@link #suggestion}，用户锁定的画像保留原值、把冲突弹给用户处理。
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
