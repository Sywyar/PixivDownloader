package top.sywyar.pixivdownload.novel.db;

/**
 * 持久化的<b>整章逐句朗读脚本</b>一行（{@code novel_narration_scripts}）：一本小说在某语言下的分析结果。
 * LLM 分析昂贵，故逐句归属持久化，重播不重算，只在用户主动「重新分析」时重算。
 *
 * @param novelId      小说 ID
 * @param lang         内容语言代码（{@code ''} 表示原文；与详情页内容语言切换一致）
 * @param castId       本次分析所用花名册 ID（{@code 0} 表示无花名册 / 纯旁白）
 * @param segmentSize  本次分析所用的分段字数（{@code 0}=整章一批）
 * @param analyzedTime 分析时间（Unix epoch 毫秒）
 * @param scriptJson   有序逐句脚本 JSON（{@code [{i,speaker,speakerName,delivery,paragraphIndex,text}]}）；
 *                     <b>不</b>存 controlInstruction —— 合成时按 speaker 从活花名册取基底再合并 delivery，
 *                     使音色编辑 / 冲突解决即时生效、无需重分析
 */
public record NovelNarrationScriptRow(
        long novelId,
        String lang,
        long castId,
        int segmentSize,
        long analyzedTime,
        String scriptJson
) {
}
