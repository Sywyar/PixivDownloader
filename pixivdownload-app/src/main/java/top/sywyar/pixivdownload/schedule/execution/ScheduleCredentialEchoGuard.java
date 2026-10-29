package top.sywyar.pixivdownload.schedule.execution;

import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledSensitiveFieldNames;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 计划凭证在插件回调结果中的原文回显检测器。
 *
 * <p>检测器只做 UTF-16 原文比较，不解码、不规范化也不折叠大小写。构造时取得凭证字符的
 * 防御性副本，调用方仍拥有并负责清零原凭证材料；检测器的副本只与凭证材料保持相同的
 * 短生命周期，并在 {@link #close()} 时清零。
 */
final class ScheduleCredentialEchoGuard implements AutoCloseable {

    private static final int LONG_FRAGMENT_MIN_LENGTH = 8;
    private static final int HIGH_ENTROPY_MIN_LENGTH = 16;
    private static final int HIGH_ENTROPY_MIN_DISTINCT_CHARS = 8;
    private static final int SINGLE_CLASS_MIN_LENGTH = 24;
    private static final int SINGLE_CLASS_MIN_DISTINCT_CHARS = 10;
    private static final Fragment[] NO_FRAGMENTS = new Fragment[0];

    private char[] secretSnapshot;
    private Fragment[] fragments;
    private boolean closed;

    ScheduleCredentialEchoGuard(char[] secret) {
        char[] source = secret == null ? new char[0] : secret;
        this.secretSnapshot = Arrays.copyOf(source, source.length);
        this.fragments = collectFragments(secretSnapshot);
    }

    /**
     * 判断一个已经按字段边界提取的插件文本是否回显当前凭证原文。
     *
     * <p>短于八个 UTF-16 code unit 的片段只接受整字段相等；更长片段允许出现在字段内部。
     * 检测器关闭后不再持有凭证材料并固定返回 {@code false}。
     */
    synchronized boolean matches(String candidate) {
        if (closed || candidate == null || candidate.isEmpty()) {
            return false;
        }
        for (Fragment fragment : fragments) {
            if (fragment.substring()
                    ? contains(candidate, secretSnapshot, fragment)
                    : equals(candidate, secretSnapshot, fragment)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 在已经被声明为可逆编码载体的文本中做保守子串检测。与普通字段边界不同，URL 即使只携带
     * 一个短凭证片段也会形成泄漏，因此这里不对短片段放宽。
     */
    synchronized boolean matchesSubstring(String candidate) {
        if (closed || candidate == null || candidate.isEmpty()) {
            return false;
        }
        for (Fragment fragment : fragments) {
            if (contains(candidate, secretSnapshot, fragment)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        Arrays.fill(secretSnapshot, '\0');
        secretSnapshot = new char[0];
        Arrays.fill(fragments, null);
        fragments = NO_FRAGMENTS;
        closed = true;
    }

    private static Fragment[] collectFragments(char[] secret) {
        int fullStart = trimStart(secret, 0, secret.length);
        int fullEnd = trimEnd(secret, fullStart, secret.length);
        if (fullStart >= fullEnd) {
            return NO_FRAGMENTS;
        }

        List<Fragment> collected = new ArrayList<>();
        addFragment(collected, secret, fullStart, fullEnd);

        int cookieStart = skipCookieHeader(secret, fullStart, fullEnd);
        int segmentStart = cookieStart;
        for (int index = cookieStart; index <= fullEnd; index++) {
            if (index == fullEnd || isCookieDelimiter(secret[index])) {
                collectCookieSegment(collected, secret, segmentStart, index);
                segmentStart = index + 1;
            }
        }
        return collected.toArray(Fragment[]::new);
    }

    private static void collectCookieSegment(
            List<Fragment> collected,
            char[] secret,
            int rawStart,
            int rawEnd) {
        int segmentStart = trimStart(secret, rawStart, rawEnd);
        int segmentEnd = trimEnd(secret, segmentStart, rawEnd);
        int separator = firstEquals(secret, segmentStart, segmentEnd);
        if (separator <= segmentStart || separator + 1 >= segmentEnd) {
            return;
        }

        int nameStart = segmentStart;
        int nameEnd = trimEnd(secret, nameStart, separator);
        int valueStart = trimStart(secret, separator + 1, segmentEnd);
        int valueEnd = trimEnd(secret, valueStart, segmentEnd);
        if (nameStart >= nameEnd || valueStart >= valueEnd) {
            return;
        }

        addFragment(collected, secret, nameStart, valueEnd);

        int unquotedStart = valueStart;
        int unquotedEnd = valueEnd;
        if (valueEnd - valueStart >= 2
                && isMatchingQuote(secret[valueStart], secret[valueEnd - 1])) {
            unquotedStart++;
            unquotedEnd--;
        }
        if (unquotedStart >= unquotedEnd) {
            return;
        }

        boolean sensitiveName = isSensitiveCookieName(secret, nameStart, nameEnd);
        if (sensitiveName || looksHighEntropy(secret, unquotedStart, unquotedEnd)) {
            addFragment(collected, secret, valueStart, valueEnd);
            addFragment(collected, secret, unquotedStart, unquotedEnd);
        }
    }

    private static void addFragment(
            List<Fragment> collected,
            char[] secret,
            int start,
            int end) {
        int length = end - start;
        if (length <= 0) {
            return;
        }
        boolean substring = length >= LONG_FRAGMENT_MIN_LENGTH;
        for (Fragment existing : collected) {
            if (existing.length() == length
                    && existing.substring() == substring
                    && sameContent(secret, existing.start(), start, length)) {
                return;
            }
        }
        collected.add(new Fragment(start, length, substring));
    }

    private static int skipCookieHeader(char[] secret, int start, int end) {
        if (!regionMatchesAsciiIgnoreCase(secret, start, end, "cookie")) {
            return start;
        }
        int cursor = start + "cookie".length();
        cursor = trimStart(secret, cursor, end);
        if (cursor >= end || (secret[cursor] != ':' && secret[cursor] != '=')) {
            return start;
        }
        return trimStart(secret, cursor + 1, end);
    }

    private static boolean isSensitiveCookieName(char[] secret, int start, int end) {
        // Cookie 名是结构元数据而不是 secret；复用稳定契约的通用敏感语义，
        // 不在宿主复制来源专属字段名。
        return ScheduledSensitiveFieldNames.isSensitiveFieldName(
                new String(secret, start, end - start));
    }

    private static boolean looksHighEntropy(char[] secret, int start, int end) {
        int length = end - start;
        if (length < HIGH_ENTROPY_MIN_LENGTH) {
            return false;
        }

        char[] distinctChars = new char[SINGLE_CLASS_MIN_DISTINCT_CHARS];
        int distinctCount = 0;
        boolean lower = false;
        boolean upper = false;
        boolean digit = false;
        boolean other = false;
        try {
            for (int index = start; index < end; index++) {
                char value = secret[index];
                if (Character.isWhitespace(value) || Character.isISOControl(value)) {
                    return false;
                }
                if (Character.isLowerCase(value)) {
                    lower = true;
                } else if (Character.isUpperCase(value)) {
                    upper = true;
                } else if (Character.isDigit(value)) {
                    digit = true;
                } else {
                    other = true;
                }

                if (distinctCount < distinctChars.length
                        && !contains(distinctChars, distinctCount, value)) {
                    distinctChars[distinctCount++] = value;
                }
            }
            if (distinctCount < HIGH_ENTROPY_MIN_DISTINCT_CHARS) {
                return false;
            }
            int characterClasses = (lower ? 1 : 0)
                    + (upper ? 1 : 0)
                    + (digit ? 1 : 0)
                    + (other ? 1 : 0);
            return characterClasses >= 2
                    || (length >= SINGLE_CLASS_MIN_LENGTH
                    && distinctCount >= SINGLE_CLASS_MIN_DISTINCT_CHARS);
        } finally {
            Arrays.fill(distinctChars, '\0');
        }
    }

    private static boolean contains(char[] values, int length, char expected) {
        for (int index = 0; index < length; index++) {
            if (values[index] == expected) {
                return true;
            }
        }
        return false;
    }

    private static boolean contains(String candidate, char[] secret, Fragment fragment) {
        int fragmentLength = fragment.length();
        int limit = candidate.length() - fragmentLength;
        for (int candidateStart = 0; candidateStart <= limit; candidateStart++) {
            boolean equal = true;
            for (int offset = 0; offset < fragmentLength; offset++) {
                if (candidate.charAt(candidateStart + offset)
                        != secret[fragment.start() + offset]) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                return true;
            }
        }
        return false;
    }

    private static boolean equals(String candidate, char[] secret, Fragment fragment) {
        if (candidate.length() != fragment.length()) {
            return false;
        }
        for (int offset = 0; offset < fragment.length(); offset++) {
            if (candidate.charAt(offset) != secret[fragment.start() + offset]) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameContent(char[] values, int left, int right, int length) {
        for (int offset = 0; offset < length; offset++) {
            if (values[left + offset] != values[right + offset]) {
                return false;
            }
        }
        return true;
    }

    private static boolean regionMatchesAsciiIgnoreCase(
            char[] values,
            int start,
            int end,
            String expected) {
        if (end - start < expected.length()) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if (asciiLower(values[start + index]) != expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static char asciiLower(char value) {
        return value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value;
    }

    private static int firstEquals(char[] values, int start, int end) {
        for (int index = start; index < end; index++) {
            if (values[index] == '=') {
                return index;
            }
        }
        return -1;
    }

    private static boolean isMatchingQuote(char first, char last) {
        return first == last && (first == '\'' || first == '"');
    }

    private static boolean isCookieDelimiter(char value) {
        return value == ';' || value == '\r' || value == '\n';
    }

    private static int trimStart(char[] values, int start, int end) {
        int cursor = start;
        while (cursor < end && Character.isWhitespace(values[cursor])) {
            cursor++;
        }
        return cursor;
    }

    private static int trimEnd(char[] values, int start, int end) {
        int cursor = end;
        while (cursor > start && Character.isWhitespace(values[cursor - 1])) {
            cursor--;
        }
        return cursor;
    }

    private record Fragment(int start, int length, boolean substring) {
    }
}
