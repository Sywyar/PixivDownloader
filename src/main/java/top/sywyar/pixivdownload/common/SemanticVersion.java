package top.sywyar.pixivdownload.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 语义化版本，支持本项目标准版本号 {@code n.n.n-xx.n}。
 *
 * <p>{@code n} 为数字段，{@code xx} 为预发布后缀（大小写不敏感，内部统一小写）。
 * 后缀优先级由低到高：
 * <ol>
 *   <li>无法识别的后缀 —— 最低优先级（{@link #RANK_UNKNOWN}）</li>
 *   <li>{@code nightly} / {@code snapshot}（每日构建）</li>
 *   <li>{@code dev} / {@code alpha}（开发内测）</li>
 *   <li>{@code beta} / {@code m} / {@code preview}（公开测试）</li>
 *   <li>{@code rc}（候选发布）</li>
 *   <li>无后缀的正式版 —— 最高优先级（{@link #RANK_RELEASE}）</li>
 * </ol>
 *
 * <p>核心数字段先按段比较；相等时比较后缀优先级；再相等时比较后缀内的数字序列
 * （如 {@code -snapshot.1} 中的 {@code 1}），缺失视为 0。
 */
public final class SemanticVersion implements Comparable<SemanticVersion> {

    /** 无法识别的后缀：最低优先级。 */
    public static final int RANK_UNKNOWN = 0;
    public static final int RANK_NIGHTLY = 1;
    public static final int RANK_DEV = 2;
    public static final int RANK_BETA = 3;
    public static final int RANK_RC = 4;
    /** 无预发布后缀的正式版：最高优先级。 */
    public static final int RANK_RELEASE = 5;

    private final int[] core;
    private final int preReleaseRank;
    private final long[] preReleaseNumbers;
    private final String raw;

    private SemanticVersion(int[] core, int preReleaseRank, long[] preReleaseNumbers, String raw) {
        this.core = core;
        this.preReleaseRank = preReleaseRank;
        this.preReleaseNumbers = preReleaseNumbers;
        this.raw = raw;
    }

    /**
     * 解析版本字符串。容错处理：去掉前导 {@code v}/{@code V}、忽略 {@code +} 之后的构建元数据，
     * 非数字核心段尽量取前导数字、否则按 0 处理。空/null 返回 {@code null}。
     */
    public static SemanticVersion parseOrNull(String version) {
        if (version == null || version.isBlank()) {
            return null;
        }
        String raw = version.trim();
        String normalized = raw;
        if (normalized.length() > 1
                && (normalized.charAt(0) == 'v' || normalized.charAt(0) == 'V')
                && Character.isDigit(normalized.charAt(1))) {
            normalized = normalized.substring(1);
        }

        // 忽略 SemVer 构建元数据（如 Tampermonkey 的 +host-<host>）。
        int plus = normalized.indexOf('+');
        if (plus >= 0) {
            normalized = normalized.substring(0, plus);
        }

        String corePart;
        String preReleasePart;
        int dash = normalized.indexOf('-');
        if (dash >= 0) {
            corePart = normalized.substring(0, dash);
            preReleasePart = normalized.substring(dash + 1);
        } else {
            corePart = normalized;
            preReleasePart = null;
        }

        int[] core = parseCore(corePart);
        int preReleaseRank;
        long[] preReleaseNumbers;
        if (preReleasePart == null || preReleasePart.isBlank()) {
            preReleaseRank = RANK_RELEASE;
            preReleaseNumbers = new long[0];
        } else {
            String[] tokens = preReleasePart.toLowerCase(Locale.ROOT).split("\\.");
            List<Long> numbers = new ArrayList<>();

            // 首段可能是纯后缀（snapshot）或后缀粘连数字（rc1）。
            String first = tokens[0];
            int splitAt = 0;
            while (splitAt < first.length() && !Character.isDigit(first.charAt(splitAt))) {
                splitAt++;
            }
            String suffixName = first.substring(0, splitAt);
            String trailingDigits = first.substring(splitAt);
            preReleaseRank = rankOf(suffixName);
            if (!trailingDigits.isEmpty()) {
                numbers.add(parseLongOrZero(trailingDigits));
            }
            for (int i = 1; i < tokens.length; i++) {
                numbers.add(parseLongOrZero(tokens[i]));
            }
            preReleaseNumbers = numbers.stream().mapToLong(Long::longValue).toArray();
        }

        return new SemanticVersion(core, preReleaseRank, preReleaseNumbers, raw);
    }

    /**
     * 比较两个版本字符串。返回 &gt;0 表示 {@code left} 较新，&lt;0 表示较旧，0 表示一致。
     * 任一侧为空时，空侧视为更旧；两侧都为空返回 0。
     */
    public static int compare(String left, String right) {
        SemanticVersion l = parseOrNull(left);
        SemanticVersion r = parseOrNull(right);
        if (l == null) {
            return r == null ? 0 : -1;
        }
        if (r == null) {
            return 1;
        }
        return l.compareTo(r);
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int len = Math.max(core.length, other.core.length);
        for (int i = 0; i < len; i++) {
            int li = i < core.length ? core[i] : 0;
            int ri = i < other.core.length ? other.core[i] : 0;
            if (li != ri) {
                return Integer.compare(li, ri);
            }
        }
        if (preReleaseRank != other.preReleaseRank) {
            return Integer.compare(preReleaseRank, other.preReleaseRank);
        }
        int numLen = Math.max(preReleaseNumbers.length, other.preReleaseNumbers.length);
        for (int i = 0; i < numLen; i++) {
            long li = i < preReleaseNumbers.length ? preReleaseNumbers[i] : 0L;
            long ri = i < other.preReleaseNumbers.length ? other.preReleaseNumbers[i] : 0L;
            if (li != ri) {
                return Long.compare(li, ri);
            }
        }
        return 0;
    }

    public int preReleaseRank() {
        return preReleaseRank;
    }

    public String raw() {
        return raw;
    }

    private static int[] parseCore(String corePart) {
        String[] segments = corePart.split("\\.");
        int[] core = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            core[i] = parseIntLeadingDigits(segments[i]);
        }
        return core;
    }

    private static int parseIntLeadingDigits(String segment) {
        StringBuilder digits = new StringBuilder();
        for (char c : segment.trim().toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) {
            return 0;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static long parseLongOrZero(String value) {
        StringBuilder digits = new StringBuilder();
        for (char c : value.trim().toCharArray()) {
            if (Character.isDigit(c)) {
                digits.append(c);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) {
            return 0L;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    /** 后缀名（已小写）→ 优先级。无法识别返回 {@link #RANK_UNKNOWN}。 */
    private static int rankOf(String suffixName) {
        return switch (suffixName) {
            case "nightly", "snapshot" -> RANK_NIGHTLY;
            case "dev", "alpha" -> RANK_DEV;
            case "beta", "m", "preview" -> RANK_BETA;
            case "rc" -> RANK_RC;
            default -> RANK_UNKNOWN;
        };
    }
}
