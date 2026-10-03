package top.sywyar.pixivdownload.sdk.community.submission;

import top.sywyar.pixivdownload.sdk.community.content.MarketContent;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Pattern;

/** 从冻结日志提取精确版本；围栏内标题不参与版本定位。 */
public final class ChangelogSections {
    private static final Pattern HEADING = Pattern.compile("^ {0,3}##[ \\t]+\\[v?([^]\\s]+)](?:[ \\t].*)?$");
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern REFERENCE = Pattern.compile("^ {0,3}\\[[^]\\r\\n]+]:[ \\t]*\\S.*$");
    private ChangelogSections() { }

    public static String utf8(byte[] bytes) {
        if (bytes.length > MarketContent.DOCUMENT_BYTES) {
            throw CommunityJson.limit("/content/document", MarketContent.DOCUMENT_BYTES, "bytes");
        }
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (text.indexOf('\0') >= 0) throw new ContractException("SCHEMA_INVALID", "/content/document");
            return text.startsWith("\ufeff") ? text.substring(1) : text;
        } catch (java.nio.charset.CharacterCodingException failure) {
            throw new ContractException("SCHEMA_INVALID", "/content/document");
        }
    }

    public static String extract(byte[] bytes, String version) {
        CommunityValues.version(version, "/version");
        String[] lines = utf8(bytes).replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        var references = new ArrayList<Integer>();
        int start = -1, end = lines.length, matches = 0, fenceLength = 0;
        char fenceCharacter = 0;
        for (int i = 0; i < lines.length; i++) {
            var fence = FENCE.matcher(lines[i]);
            if (fence.matches()) {
                String marker = fence.group(1), suffix = fence.group(2);
                if (fenceLength == 0 && (marker.charAt(0) != '`' || suffix.indexOf('`') < 0)) {
                    fenceCharacter = marker.charAt(0);
                    fenceLength = marker.length();
                    continue;
                }
                if (fenceLength != 0 && marker.charAt(0) == fenceCharacter
                        && marker.length() >= fenceLength && suffix.isBlank()) {
                    fenceLength = 0;
                    continue;
                }
            }
            if (fenceLength != 0) continue;
            var heading = HEADING.matcher(lines[i]);
            if (heading.matches()) {
                // Unreleased 是边界，但绝不代替目标版本。
                String candidate = heading.group(1);
                boolean versionHeading = candidate.equalsIgnoreCase("Unreleased");
                try { CommunityValues.version(candidate, "/version"); versionHeading = true; }
                catch (ContractException ignored) { }
                if (versionHeading && start >= 0 && end == lines.length) end = i;
                if (candidate.equals(version)) { matches++; if (start < 0) start = i; }
            }
            if (REFERENCE.matcher(lines[i]).matches()) references.add(i);
        }
        if (matches == 0) throw new ContractException("CHANGELOG_VERSION_MISSING", "/content/changelog");
        if (matches != 1) throw new ContractException("CHANGELOG_VERSION_DUPLICATED", "/content/changelog");
        StringBuilder result = new StringBuilder(String.join("\n", java.util.Arrays.copyOfRange(lines, start, end)).stripTrailing());
        // 定义可以位于文件尾；一并保留，避免相对正文失去链接目标。
        for (int index : references) if (index < start || index >= end) {
            result.append("\n\n").append(lines[index]);
            for (int i = index + 1; i < lines.length && (lines[i].startsWith("    ") || lines[i].startsWith("\t")); i++) {
                result.append('\n').append(lines[i]);
            }
        }
        result.append('\n');
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > MarketContent.DOCUMENT_BYTES) {
            throw CommunityJson.limit("/content/releaseNotes", MarketContent.DOCUMENT_BYTES, "bytes");
        }
        return result.toString();
    }
}
