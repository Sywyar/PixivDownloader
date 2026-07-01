package top.sywyar.pixivdownload.plugin.runtime.install.verify;

import java.util.List;

/**
 * 用于升级 / 降级判定的 semver 版本比较（纯 JDK，不引第三方 semver 库——本模块依赖面只许 JDK / Spring / PF4J /
 * slf4j / plugin-api）。实现 SemVer 2.0.0 §11 优先级：先比 {@code major.minor.patch}；相等时<b>有 prerelease 段者
 * 优先级更低</b>；都有 prerelease 时逐段比较（纯数字段按数值、含字母段按 ASCII 字典序、数字段 < 字母段、段更多者更高）；
 * {@code +build} 元数据不参与比较。
 *
 * <p>入参版本在安装器里已由 {@link top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor#externalValidationErrors()}
 * 保证是合法 semver，故解析采用宽容兜底（异常段记 0）而不再抛错。
 */
public final class PluginPackageVersion implements Comparable<PluginPackageVersion> {

    private final int major;
    private final int minor;
    private final int patch;
    private final List<String> prerelease;

    private PluginPackageVersion(int major, int minor, int patch, List<String> prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    public static PluginPackageVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new PluginPackageVersion(0, 0, 0, List.of());
        }
        String value = raw.trim();
        int plus = value.indexOf('+');
        if (plus >= 0) {
            value = value.substring(0, plus); // 丢弃 build 元数据
        }
        String core;
        List<String> prerelease = List.of();
        int dash = value.indexOf('-');
        if (dash >= 0) {
            core = value.substring(0, dash);
            String pre = value.substring(dash + 1);
            prerelease = pre.isEmpty() ? List.of() : List.of(pre.split("\\.", -1));
        } else {
            core = value;
        }
        String[] parts = core.split("\\.", -1);
        return new PluginPackageVersion(intAt(parts, 0), intAt(parts, 1), intAt(parts, 2), prerelease);
    }

    private static int intAt(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(parts[index].trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public int compareTo(PluginPackageVersion other) {
        int cmp = Integer.compare(major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(minor, other.minor);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(patch, other.patch);
        if (cmp != 0) {
            return cmp;
        }
        boolean thisRelease = prerelease.isEmpty();
        boolean otherRelease = other.prerelease.isEmpty();
        if (thisRelease && otherRelease) {
            return 0;
        }
        if (thisRelease) {
            return 1; // 正式版 > 预发布版
        }
        if (otherRelease) {
            return -1;
        }
        int common = Math.min(prerelease.size(), other.prerelease.size());
        for (int i = 0; i < common; i++) {
            cmp = compareIdentifier(prerelease.get(i), other.prerelease.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(prerelease.size(), other.prerelease.size());
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNumeric = isNumeric(a);
        boolean bNumeric = isNumeric(b);
        if (aNumeric && bNumeric) {
            String as = stripLeadingZeros(a);
            String bs = stripLeadingZeros(b);
            if (as.length() != bs.length()) {
                return Integer.compare(as.length(), bs.length());
            }
            return as.compareTo(bs);
        }
        if (aNumeric) {
            return -1; // 数字段优先级低于字母数字段
        }
        if (bNumeric) {
            return 1;
        }
        return a.compareTo(b);
    }

    private static boolean isNumeric(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String stripLeadingZeros(String value) {
        int i = 0;
        while (i < value.length() - 1 && value.charAt(i) == '0') {
            i++;
        }
        return value.substring(i);
    }
}
