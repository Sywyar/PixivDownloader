package top.sywyar.pixivdownload.tts.narration;

import top.sywyar.pixivdownload.novel.NovelMarkupParser;

import java.util.ArrayList;
import java.util.List;

/**
 * 「AI 听小说」断句工具：把小说原始 Pixiv markup 切成<b>逐句</b>的 {@link NarrationSentence}，每句带上它所属
 * 渲染块的 {@code paragraphIndex}（与前端 DOM 块顺序对齐，见 {@link NovelMarkupParser#textBlocks}）。
 *
 * <p>职责边界：本类<b>只断句、不分段、不调 AI</b>。分段（每批发给 AI 的文本量）在编排层
 * （{@code novel.NovelNarrationCastService}）按「分段字数」完成；归属永远按句。纯函数、可单测。
 *
 * <p>断句规则：先用 {@link NovelMarkupParser#textBlocks} 把正文转成纯文本块（剔除 ruby 注音 / 图片占位 /
 * 翻页标记，章节标题单独成块），再对每个段落块按中日英句末终止符（{@code 。！？!?…} 与换行）切句，连续终止符
 * （如 {@code ?!}、{@code 。。。}）与紧随其后的右引号 / 右括号并入同一句。章节标题整条作为一句。
 */
public final class NarrationSentenceSplitter {

    /** 句末终止标点。<b>不含</b> ASCII {@code '.'}，避免切断英文缩写 / 小数。 */
    private static final String TERMINATORS = "。！？!?…";

    /** 跟随句末、应与本句保持在一起的收尾字符（右引号 / 右括号等）。 */
    private static final String CLOSERS = "」』）)】》〉〕｝}]”’\"'";

    /** 低于此可发音字符数（即仅 0~1 个字，如「吗？」「啊」）的句子视为「超短句」，合并进同段邻句。 */
    private static final int MIN_SPEAKABLE_CHARS = 2;

    private NarrationSentenceSplitter() {
    }

    /** 把原始 markup 断句为逐句 {@link NarrationSentence}（带 paragraphIndex），按全局顺序。 */
    public static List<NarrationSentence> split(String raw) {
        List<NarrationSentence> out = new ArrayList<>();
        List<NovelMarkupParser.TextBlock> blocks = NovelMarkupParser.textBlocks(raw);
        for (int p = 0; p < blocks.size(); p++) {
            NovelMarkupParser.TextBlock block = blocks.get(p);
            if (block.kind() == NovelMarkupParser.TextBlockKind.CHAPTER) {
                String title = block.text() == null ? "" : block.text().trim();
                if (!title.isEmpty()) {
                    out.add(new NarrationSentence(title, p));
                }
            } else {
                for (String sentence : splitSentences(block.text())) {
                    out.add(new NarrationSentence(sentence, p));
                }
            }
        }
        return mergeTinySentences(out);
    }

    /**
     * 合并「超短句」：可发音字符数 &lt; {@link #MIN_SPEAKABLE_CHARS}（即仅 0~1 个字，如「吗？」「啊」）的句子并入
     * <b>同一 {@code paragraphIndex}</b> 的相邻句（优先并入前一句，否则并入后一句）。VoxCPM 等自回归 TTS 在仅 1 个
     * 可发音字的输入上会塌缩成长时间空白且不发声，合并到邻句即可规避。<b>只在同段内合并</b>，不改变任何
     * {@code paragraphIndex} 的存在性，故不破坏与前端 DOM 块的逐一对齐；段内确无邻句可并的孤立短句原样保留
     * （引擎侧另有短输入 token 上限兜底其空白时长）。
     */
    static List<NarrationSentence> mergeTinySentences(List<NarrationSentence> sentences) {
        List<NarrationSentence> result = new ArrayList<>();
        String carry = null;      // 暂存等待并入「后一同段句」的短句文本
        int carryPara = -1;
        for (NarrationSentence s : sentences) {
            String text = s.text();
            int para = s.paragraphIndex();
            if (carry != null) {
                if (carryPara == para) {
                    text = carry + text;     // 前缀并入当前句
                } else {
                    result.add(new NarrationSentence(carry, carryPara)); // 无同段后继 → 原样保留
                }
                carry = null;
            }
            if (speakableCount(text) < MIN_SPEAKABLE_CHARS) {
                if (!result.isEmpty() && result.get(result.size() - 1).paragraphIndex() == para) {
                    NarrationSentence prev = result.remove(result.size() - 1);
                    result.add(new NarrationSentence(prev.text() + text, para)); // 并入同段前一句
                } else {
                    carry = text;            // 段首短句：暂存，尝试并入后一同段句
                    carryPara = para;
                }
            } else {
                result.add(new NarrationSentence(text, para));
            }
        }
        if (carry != null) {
            result.add(new NarrationSentence(carry, carryPara));
        }
        return result;
    }

    /** 可发音字符数（字母 / 数字 / 表意文字 / 假名 / 谚文）。 */
    static int speakableCount(String text) {
        return text == null ? 0 : (int) text.codePoints().filter(Character::isLetterOrDigit).count();
    }

    /** 把一段纯文本按句末终止符 / 换行切成多句（已 trim、丢弃空白句）。 */
    static List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return result;
        }
        StringBuilder cur = new StringBuilder();
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (c == '\n') {
                flush(result, cur);
                i++;
                continue;
            }
            cur.append(c);
            i++;
            if (TERMINATORS.indexOf(c) >= 0) {
                // 连续终止符并入同句（?! / 。。。）
                while (i < n && TERMINATORS.indexOf(text.charAt(i)) >= 0) {
                    cur.append(text.charAt(i));
                    i++;
                }
                // 收尾右引号 / 右括号并入同句（保留引号闭合）
                while (i < n && CLOSERS.indexOf(text.charAt(i)) >= 0) {
                    cur.append(text.charAt(i));
                    i++;
                }
                flush(result, cur);
            }
        }
        flush(result, cur);
        return result;
    }

    private static void flush(List<String> result, StringBuilder cur) {
        String s = cur.toString().trim();
        cur.setLength(0);
        if (!s.isEmpty()) {
            result.add(s);
        }
    }
}
