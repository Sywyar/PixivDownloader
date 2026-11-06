package top.sywyar.pixivdownload.translation.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/** 只缓存一个批次的自然文本；标记、空白与链接目标按原位置还原。 */
public final class TranslationBatch {
    public static final int OUTPUT_CHARS = 64 * 1024 * 1024;
    private static final Pattern TOKENS = Pattern.compile("\\[\\[[^\\]\\r\\n]*]]|\\[[^\\]\\r\\n]*]|<[^>\\r\\n]*>|&(?:#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);|\\R");
    private final int textLimit;
    private final int charsLimit;
    private final int countLimit;
    private final Function<List<String>, List<String>> translator;
    private final StringBuilder output = new StringBuilder();
    private final StringBuilder literal = new StringBuilder();
    private final List<String> texts = new ArrayList<>();
    private final List<String> prefixes = new ArrayList<>();
    private int chars;

    private TranslationBatch(int textLimit, int charsLimit, int countLimit, Function<List<String>, List<String>> translator) {
        if (textLimit < 1 || charsLimit < textLimit || countLimit < 1)
            throw new IllegalArgumentException("translation.segment-size");
        this.textLimit = textLimit;
        this.charsLimit = charsLimit;
        this.countLimit = countLimit;
        this.translator = translator;
    }

    public static String translate(String source, int textLimit, int charsLimit, int countLimit,
                                   Function<List<String>, List<String>> translator) {
        if (source.length() > OUTPUT_CHARS) throw new IllegalStateException("translation.response-too-large");
        var batch = new TranslationBatch(textLimit, charsLimit, countLimit, translator);
        var matcher = TOKENS.matcher(source);
        int offset = 0;
        while (matcher.find()) {
            batch.text(source.substring(offset, matcher.start()));
            String token = matcher.group();
            if (token.startsWith("[chapter:")) {
                batch.literal.append("[chapter:");
                batch.text(token.substring(9, token.length() - 1));
                batch.literal.append(']');
            } else if (token.startsWith("[[jumpuri:") || token.startsWith("[[rb:")) {
                int colon = token.indexOf(':') + 1, divider = token.indexOf('>', colon);
                if (divider < 0) batch.literal.append(token);
                else {
                    batch.literal.append(token, 0, colon);
                    batch.text(token.substring(colon, divider));
                    batch.literal.append(token.substring(divider));
                }
            } else batch.literal.append(token);
            offset = matcher.end();
        }
        batch.text(source.substring(offset));
        batch.flush();
        batch.append(batch.literal.toString());
        return batch.output.toString();
    }

    private void text(String text) {
        int offset = 0;
        int remaining = text.codePointCount(0, text.length());
        while (offset < text.length()) {
            if (Character.isWhitespace(text.charAt(offset))) {
                literal.append(text.charAt(offset++));
                remaining--;
                continue;
            }
            int count = Math.min(textLimit, remaining);
            int end = text.offsetByCodePoints(offset, count);
            // 优先保留句子和单词；没有可用边界的超长词才按码点切分。
            if (end < text.length()) {
                for (int at = end; at > offset;) {
                    int cp = text.codePointBefore(at);
                    if (Character.isWhitespace(cp) || ".!?。！？;；".indexOf(cp) >= 0) { end = at; break; }
                    at -= Character.charCount(cp);
                }
            }
            int trimmedEnd = end;
            while (trimmedEnd > offset && Character.isWhitespace(text.charAt(trimmedEnd - 1))) trimmedEnd--;
            String part = text.substring(offset, trimmedEnd);
            int partChars = part.codePointCount(0, part.length());
            if (!texts.isEmpty() && (texts.size() >= countLimit || chars + partChars > charsLimit)) flush();
            prefixes.add(literal.toString());
            literal.setLength(0);
            texts.add(part);
            chars += partChars;
            literal.append(text, trimmedEnd, end);
            remaining -= text.codePointCount(offset, end);
            offset = end;
        }
    }

    private void flush() {
        if (texts.isEmpty()) return;
        List<String> translated = translator.apply(List.copyOf(texts));
        if (translated == null || translated.size() != texts.size()) throw new IllegalStateException("translation.invalid-response");
        for (int i = 0; i < translated.size(); i++) {
            String result = translated.get(i);
            if (result == null || result.isBlank()) throw new IllegalStateException("translation.invalid-response");
            append(prefixes.get(i));
            append(result.replace("[", "［").replace("]", "］").replace("<", "＜").replace(">", "＞"));
        }
        texts.clear();
        prefixes.clear();
        chars = 0;
    }

    private void append(String value) {
        if (value.length() > OUTPUT_CHARS - output.length()) throw new IllegalStateException("translation.response-too-large");
        output.append(value);
    }
}
