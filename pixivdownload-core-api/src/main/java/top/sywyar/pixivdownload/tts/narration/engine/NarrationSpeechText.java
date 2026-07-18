package top.sywyar.pixivdownload.tts.narration.engine;

/**
 * 朗读引擎<b>无关</b>的待合成文本工具（纯静态、可单测）。把自回归 TTS 通用的文本处理收敛到这里，
 * 供调用方统一归一并由各引擎复用：
 * <ul>
 *   <li>{@link #normalize(String)}：省略号 / 悬挂标点结尾 → 句号，纯标点 / 无可发音内容 → 空串（规避空音频 / 长噪音 / 呓语）；</li>
 *   <li>{@link #speakableCount(String)} / {@link #hasSpeakable(String)}：可发音字符统计（短输入收敛 / 跳过判定用）；</li>
 *   <li>{@link #blankToNull(String)}：空白 → {@code null}（供「空则不下发」的可选文本字段）；</li>
 *   <li>{@link #isShortInput(String, int)}：是否为「超短输入」（按可发音字数）。</li>
 * </ul>
 * 句子切分与跨句合并不属于本工具，由调用方在进入合成边界前完成。
 */
public final class NarrationSpeechText {

    /** 句末干净终止符：核心文本已以此结尾就不再补句号（{@code 。}/{@code .} 会被悬挂剥离，故只需保留 {@code ！？!?}）。 */
    static final String SENTENCE_FINAL = "！？!?";
    /** 末尾「悬挂」标记：省略号 / 点号 / 中点 / 破折号 / 波浪号——原样收尾会让自回归停止符不触发。 */
    static final String DANGLING_TAIL = "…⋯‥.。．・—–～~-";
    /** 末尾右引号 / 右括号：先摘出、补完句号后再贴回，保持引号闭合。 */
    static final String TRAILING_CLOSERS = "」』）)】》〉〕｝}]”’\"'";

    private NarrationSpeechText() {
    }

    /**
     * 归一化待合成正文，规避自回归停止符在「省略号 / 悬挂标点结尾」「纯标点无可发音内容」上失灵导致的
     * 空音频 / 长噪音 / 呓语：
     * <ul>
     *   <li>去首尾空白；不含任何可发音字符（字母 / 数字 / 表意文字 / 假名 / 谚文）→ 返回空串（上游按空文本跳过）；</li>
     *   <li>仅当<b>末尾</b>带悬挂标点（省略号 / 连续点号 / 破折号 / 波浪号）时，把这段悬挂尾替换为一个句号，给模型明确
     *       的句末停止线索；右引号 / 右括号收尾先摘出、补句号后再贴回以保持闭合。已带 {@code ！？!?} 等干净终止符、
     *       或本就以普通字符结尾的正常句子<b>原样返回</b>，不打扰。</li>
     * </ul>
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        if (s.isEmpty() || !hasSpeakable(s)) {
            return "";
        }
        int end = s.length();
        while (end > 0 && TRAILING_CLOSERS.indexOf(s.charAt(end - 1)) >= 0) {
            end--;
        }
        String closerTail = s.substring(end);
        int afterClosers = end;
        while (end > 0 && (Character.isWhitespace(s.charAt(end - 1)) || DANGLING_TAIL.indexOf(s.charAt(end - 1)) >= 0)) {
            end--;
        }
        boolean strippedDangling = end < afterClosers;
        if (!strippedDangling && closerTail.isEmpty()) {
            return s;
        }
        String core = s.substring(0, end);
        if (core.isEmpty() || !hasSpeakable(core)) {
            return "";
        }
        char last = core.charAt(core.length() - 1);
        if (strippedDangling && SENTENCE_FINAL.indexOf(last) < 0) {
            String terminator = last <= 0x7F && Character.isLetterOrDigit(last) ? "." : "。";
            return core + terminator + closerTail;
        }
        return core + closerTail;
    }

    /** 是否含至少一个可发音字符（字母 / 数字 / 表意文字 / 假名 / 谚文）。 */
    public static boolean hasSpeakable(String s) {
        return s != null && s.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    /** 可发音字符数（字母 / 数字 / 表意文字 / 假名 / 谚文）。 */
    public static int speakableCount(String s) {
        return s == null ? 0 : (int) s.codePoints().filter(Character::isLetterOrDigit).count();
    }

    /** 空 / 空白 → {@code null}，否则返回 {@code trim()} 后的值。供「空则不下发」的可选文本字段。 */
    public static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 是否「超短输入」：可发音字数 {@code <= threshold}（自回归模型在此类输入上易塌缩成长空白）。 */
    public static boolean isShortInput(String text, int threshold) {
        return speakableCount(text) <= threshold;
    }
}
