package top.sywyar.pixivdownload.tts.narration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AI 听小说断句（句级 + paragraphIndex 与渲染块顺序对齐）")
class NarrationSentenceSplitterTest {

    @Test
    @DisplayName("splitSentences：中日英终止符 / 换行切句，连续终止符与右引号并入同句")
    void splitSentencesByTerminators() {
        assertThat(NarrationSentenceSplitter.splitSentences("第一句。第二句！第三句？"))
                .containsExactly("第一句。", "第二句！", "第三句？");
        // 连续终止符并入同句；ASCII '.' 不切断
        assertThat(NarrationSentenceSplitter.splitSentences("Really?! Mr. Smith said so…"))
                .containsExactly("Really?!", "Mr. Smith said so…");
        // 右引号 / 右括号跟随句末并入同句
        assertThat(NarrationSentenceSplitter.splitSentences("「住口！」他喊道。"))
                .containsExactly("「住口！」", "他喊道。");
        // 换行作为句界，空白句丢弃
        assertThat(NarrationSentenceSplitter.splitSentences("上行\n\n下行"))
                .containsExactly("上行", "下行");
    }

    @Test
    @DisplayName("split：章节标题单独成句；ruby 取基词、图片 / 翻页剔除；paragraphIndex 与块顺序逐一对齐")
    void splitAlignsParagraphIndexWithBlocks() {
        String raw = "[chapter:第一章]\n"
                + "第一句。第二句！\n"
                + "[uploadedimage:42]\n"
                + "他说[[rb:漢字 > かんじ]]。[jump:5]\n"
                + "[newpage]\n"
                + "下一页第一句？下一页第二句。";

        List<NarrationSentence> sentences = NarrationSentenceSplitter.split(raw);

        assertThat(sentences).extracting(NarrationSentence::text).containsExactly(
                "第一章",
                "第一句。", "第二句！", "他说漢字。",
                "下一页第一句？", "下一页第二句。");
        // 章节标题块 0；中间整段（含图片/换页行，剔除占位后仍是同一渲染块）块 1；换页后段落块 2。
        assertThat(sentences).extracting(NarrationSentence::paragraphIndex).containsExactly(
                0, 1, 1, 1, 2, 2);
    }

    @Test
    @DisplayName("split：paragraphIndex 与前端可朗读 DOM 块（h2.novel-chapter + 每个非空 <p>）数量一致，[newpage] 不占块")
    void paragraphIndexMatchesRenderedBlockCount() {
        // 双空行切两段 <p>；newpage 不产生块
        String raw = "段一第一句。段一第二句。\n\n段二第一句。\n[newpage]\n段三。";
        List<NarrationSentence> sentences = NarrationSentenceSplitter.split(raw);
        // 三个段落块：0(段一两句) / 1(段二) / 2(段三)
        assertThat(sentences).extracting(NarrationSentence::paragraphIndex)
                .containsExactly(0, 0, 1, 2);
        int maxBlock = sentences.stream().mapToInt(NarrationSentence::paragraphIndex).max().orElse(-1);
        assertThat(maxBlock).isEqualTo(2);
    }

    @Test
    @DisplayName("split：空 / 纯标记输入安全返回空列表")
    void emptyInputSafe() {
        assertThat(NarrationSentenceSplitter.split(null)).isEmpty();
        assertThat(NarrationSentenceSplitter.split("")).isEmpty();
        assertThat(NarrationSentenceSplitter.split("[newpage]\n[newpage]")).isEmpty();
    }

    @Test
    @DisplayName("split：单字超短句并入同段前一句（前句优先），段首短句并入后一句")
    void mergesTinySentencesIntoNeighbor() {
        // 「吗？」（仅 1 可发音字）并入同段前一句
        assertThat(NarrationSentenceSplitter.split("你说得对。吗？后面继续。"))
                .extracting(NarrationSentence::text)
                .containsExactly("你说得对。吗？", "后面继续。");
        // 段首单字句无前句 → 并入同段后一句
        assertThat(NarrationSentenceSplitter.split("啊。这是正文。"))
                .extracting(NarrationSentence::text)
                .containsExactly("啊。这是正文。");
    }

    @Test
    @DisplayName("split：英文 / 拉丁超短句合并按边界补空格，避免单词粘连")
    void mergesLatinTinySentencesWithSpace() {
        // 段首单字母句 "A?" 并入后一同段句，边界补空格 → "A? Next."（而非粘连成 "A?Next."）
        assertThat(NarrationSentenceSplitter.split("A? Next."))
                .extracting(NarrationSentence::text)
                .containsExactly("A? Next.");
        // 换行分隔的单字母短句 "I" 并入前句，补空格不粘连 → "Really? I"（而非 "Really?I"）
        assertThat(NarrationSentenceSplitter.split("Really?\nI\nagree."))
                .extracting(NarrationSentence::text)
                .containsExactly("Really? I", "agree.");
    }

    @Test
    @DisplayName("split：同段内确无邻句可并的孤立单字句原样保留，paragraphIndex 不变")
    void keepsIsolatedTinySentence() {
        List<NarrationSentence> sentences = NarrationSentenceSplitter.split("前段。\n\n吗？\n\n后段。");
        assertThat(sentences).extracting(NarrationSentence::text)
                .containsExactly("前段。", "吗？", "后段。");
        assertThat(sentences).extracting(NarrationSentence::paragraphIndex)
                .containsExactly(0, 1, 2);
    }
}
