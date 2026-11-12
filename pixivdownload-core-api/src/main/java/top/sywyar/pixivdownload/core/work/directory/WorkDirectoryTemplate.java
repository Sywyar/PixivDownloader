package top.sywyar.pixivdownload.core.work.directory;

import top.sywyar.pixivdownload.core.work.filename.WorkTemplateVariables;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 最终作品目录模板。固定前缀只供路径基础设施使用，展开不追加任何目录层级。
 * 身份段静态校验不代替落盘时对既有目录和其它作品的归属检查。
 */
public final class WorkDirectoryTemplate {
    private static final Pattern TOKEN = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_+\u002d]*)}");
    private static final Pattern RESERVED = Pattern.compile("(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?");
    private final Path prefix;
    private final List<String> segments;

    private WorkDirectoryTemplate(Path prefix, List<String> segments) {
        this.prefix = prefix;
        this.segments = List.copyOf(segments);
    }

    /**
     * 校验模板；作品 ID 必须独占一个变量段，可带固定文字，但不能与其它变量拼接。
     * @param template 相对或绝对的最终目录模板
     * @return 已解析的不可变模板
     */
    public static WorkDirectoryTemplate parse(String template) {
        if (template == null || template.isBlank()) throw rejected("required", "");
        String value = template.trim().replace('\\', '/');
        if (value.matches("(?i)^[a-z]:($|[^/].*)") || value.codePoints().anyMatch(Character::isISOControl))
            throw rejected("path", "");
        try {
            Path path = Path.of(value);
            Path prefix = path.getRoot();
            if (prefix == null) prefix = Path.of("");
            if (prefix.toString().contains("{") || prefix.toString().contains("}")) throw rejected("path", "");
            List<String> suffix = new ArrayList<>();
            boolean dynamic = false;
            boolean identity = false;
            for (Path part : path) {
                String segment = part.toString();
                if (segment.equals("..")) throw rejected("traversal", "");
                if (segment.equals(".") || segment.isEmpty()) continue;
                var matcher = TOKEN.matcher(segment);
                int count = 0;
                boolean id = false;
                while (matcher.find()) {
                    count++;
                    String name = matcher.group(1);
                    var variable = WorkTemplateVariables.find(name);
                    if (variable == null) throw rejected("unknown-variable", name);
                    if (!variable.directory()) throw rejected("unavailable-variable", name);
                    id |= variable.id().equals("artworkId");
                }
                String literal = TOKEN.matcher(segment).replaceAll("");
                if (literal.indexOf('{') >= 0 || literal.indexOf('}') >= 0) throw rejected("syntax", "");
                requireLiteral(literal);
                identity |= id && count == 1;
                dynamic |= count > 0;
                if (dynamic) suffix.add(segment);
                else prefix = prefix.resolve(segment);
            }
            if (!identity) throw rejected("identity", "artworkId");
            return new WorkDirectoryTemplate(prefix.toString().isEmpty() ? Path.of(".") : prefix.normalize(), suffix);
        } catch (InvalidPathException failure) {
            throw rejected("path", "");
        }
    }

    /** @return 第一个变量之前的固定路径，保留相对或绝对属性 */
    public Path fixedPrefix() { return prefix; }

    /**
     * 目录迁移工具替换固定前缀，保留完整的作品模板后缀。
     * @param root 用户确认的新固定目录
     * @return 可保存的最终目录模板
     */
    public String withFixedPrefix(String root) {
        if (root == null || root.isBlank() || root.contains("{") || root.contains("}")) throw rejected("path", "");
        String result = Path.of(root).resolve(String.join("/", segments)).toString().replace('\\', '/');
        parse(result);
        return result;
    }

    /**
     * @param values 按变量稳定身份提供的当次作品快照
     * @return 实际绝对作品目录
     */
    public Path resolve(Map<String, String> values) { return resolve(prefix, values); }

    /**
     * 在明确替换固定前缀的场景展开；变量永远只占目录段，不能改变锚点。
     * @param anchor 本次明确选定的固定前缀
     * @param values 按变量稳定身份提供的当次作品快照
     * @return 实际绝对作品目录
     */
    public Path resolve(Path anchor, Map<String, String> values) {
        Path root = anchor.toAbsolutePath().normalize();
        Path result = root;
        for (String segment : segments) {
            var matcher = TOKEN.matcher(segment);
            StringBuilder expanded = new StringBuilder();
            int end = 0;
            while (matcher.find()) {
                var variable = WorkTemplateVariables.find(matcher.group(1));
                String raw = values.get(variable.id());
                String replacement = raw == null ? "" : sanitize(raw);
                if (replacement.isEmpty() && !variable.directoryOptional())
                    throw rejected("missing-variable", variable.token());
                if (variable.numeric() && !replacement.isEmpty() && !replacement.matches("[0-9]+"))
                    throw rejected("invalid-variable", variable.token());
                expanded.append(segment, end, matcher.start()).append(replacement);
                end = matcher.end();
            }
            expanded.append(segment, end, segment.length());
            String name = expanded.toString();
            if (name.isEmpty()) continue;
            if (name.equals(".") || name.equals("..") || name.endsWith(".") || name.endsWith(" "))
                throw rejected("path", "");
            if (RESERVED.matcher(name).matches()) name = "_" + name;
            result = result.resolve(name);
        }
        if (result.equals(root) || !result.normalize().startsWith(root)) throw rejected("traversal", "");
        return result.normalize();
    }

    private static String sanitize(String value) {
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(cp -> result.appendCodePoint(
                Character.isISOControl(cp) || "<>:\"/\\|?*".indexOf(cp) >= 0 ? '_' : cp));
        return result.toString().strip().replaceAll("[. ]+$", "");
    }

    private static void requireLiteral(String value) {
        if (value.codePoints().anyMatch(cp -> "<>:\"|?*".indexOf(cp) >= 0)) throw rejected("path", "");
    }

    private static DirectoryTemplateException rejected(String reason, String variable) {
        return new DirectoryTemplateException(reason, variable);
    }
}
