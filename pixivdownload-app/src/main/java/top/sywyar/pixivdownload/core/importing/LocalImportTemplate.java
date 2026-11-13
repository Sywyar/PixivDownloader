package top.sywyar.pixivdownload.core.importing;

import top.sywyar.pixivdownload.core.work.filename.WorkTemplateVariables;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.VariableException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;

/** 将目录或文件模板反向解析为本地事实；不推断没有出现在模板中的页码。 */
public final class LocalImportTemplate {
    private static final Pattern NUMBER = Pattern.compile("[0-9]+");
    private final String template;
    private final Path root;
    private final List<Segment> segments;
    private final boolean directory;

    public LocalImportTemplate(String input) {
        if (input == null || input.isBlank() || input.length() > 4096 || input.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("INVALID_IMPORT_TEMPLATE");
        template = input.replace('\\', '/');
        directory = template.endsWith("/");
        int first = template.indexOf('{');
        int slash = first < 0 ? -1 : template.lastIndexOf('/', first);
        if (slash < 0) throw new IllegalArgumentException("IMPORT_TEMPLATE_REQUIRES_ID");
        root = Path.of(template.substring(0, slash + 1)).normalize();
        if (!root.isAbsolute()) throw new IllegalArgumentException("INVALID_SOURCE_ROOT");
        String remainder = template.substring(slash + 1);
        if (directory) remainder = remainder.substring(0, remainder.length() - 1);
        List<Segment> parsed = new ArrayList<>();
        for (String part : remainder.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) throw new IllegalArgumentException("INVALID_IMPORT_TEMPLATE");
            parsed.add(new Segment(part));
        }
        if (parsed.size() > 64 || parsed.stream().flatMap(s -> s.parts.stream()).noneMatch(p -> "artworkId".equals(p.variable)))
            throw new IllegalArgumentException("IMPORT_TEMPLATE_REQUIRES_ID");
        // 规范化之前也拒绝父目录跳转，避免模板的文字前缀改变扫描范围。
        if (Arrays.asList(template.split("/")).contains("..")) throw new IllegalArgumentException("INVALID_IMPORT_TEMPLATE");
        segments = List.copyOf(parsed);
    }

    public Path root() { return root; }
    public String template() { return template; }

    /** 能从 ID 直接确定第一层目录时，不扫描其兄弟目录。 */
    public List<Path> searchRoots(Set<Long> ids) {
        if (!ids.isEmpty() && (directory || segments.size() > 1) && segments.getFirst().onlyArtworkId())
            return ids.stream().sorted().map(id -> root.resolve(Long.toString(id))).toList();
        return List.of(root);
    }

    /** 只遍历模板仍可能命中的目录；命中目录模板后，其下文件可以继续递归。 */
    public boolean acceptsDirectory(Path path) {
        Path absolute = path.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) return false;
        if (absolute.equals(root)) return true;
        Path relative = root.relativize(absolute);
        int count = directory ? segments.size() : segments.size() - 1;
        if (!directory && relative.getNameCount() > count) return false;
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(relative.getNameCount(), count); i++) {
            values = segments.get(i).match(relative.getName(i).toString(), values);
            if (values == null) return false;
        }
        return true;
    }

    public Match match(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        if (!absolute.startsWith(root)) return null;
        Path relative = root.relativize(absolute);
        if (directory ? relative.getNameCount() <= segments.size() : relative.getNameCount() != segments.size()) return null;
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < segments.size(); i++) {
            var next = segments.get(i).match(relative.getName(i).toString(), values);
            if (next == null) return null;
            values = next;
        }
        try {
            long id = Long.parseLong(values.get("artworkId"));
            Integer page = values.containsKey("page") ? Integer.valueOf(values.get("page")) : null;
            if (id <= 0 || page != null && page < 0) return null;
            if (values.containsKey("authorId") && Long.parseLong(values.get("authorId")) <= 0) return null;
            return new Match(id, page, Map.copyOf(values));
        } catch (NumberFormatException invalid) { return null; }
    }

    public record Match(long artworkId, Integer page, Map<String, String> hints) {}
    private record Part(String literal, String variable, boolean numeric) {}

    private static final class Segment {
        private final List<Part> parts = new ArrayList<>();
        Segment(String text) {
            int start = 0;
            while (start < text.length()) {
                int open = text.indexOf('{', start);
                if (open < 0) { literal(text.substring(start)); break; }
                if (open > start) literal(text.substring(start, open));
                int close = text.indexOf('}', open);
                if (close < 0) throw new IllegalArgumentException("INVALID_IMPORT_TEMPLATE");
                String variable = text.substring(open + 1, close);
                var definition = WorkTemplateVariables.find(variable);
                if (definition == null || !definition.importing()) throw new VariableException(variable, definition != null);
                if (!parts.isEmpty() && parts.getLast().variable != null) throw new IllegalArgumentException("AMBIGUOUS_IMPORT_TEMPLATE");
                parts.add(new Part(null, definition.id(), definition.numeric()));
                if (parts.size() > 32) throw new IllegalArgumentException("INVALID_IMPORT_TEMPLATE");
                start = close + 1;
            }
        }
        private void literal(String value) {
            if (value.indexOf('}') >= 0) throw new IllegalArgumentException("INVALID_IMPORT_TEMPLATE");
            parts.add(new Part(value, null, false));
        }
        boolean onlyArtworkId() { return parts.size() == 1 && "artworkId".equals(parts.getFirst().variable); }
        Map<String, String> match(String text, Map<String, String> known) {
            List<Map<String, String>> results = new ArrayList<>(2);
            collect(text, 0, 0, new LinkedHashMap<>(known), results, new int[]{0});
            if (results.size() > 1) throw new IllegalArgumentException("AMBIGUOUS_IMPORT_MATCH");
            return results.isEmpty() ? null : results.getFirst();
        }
        private void collect(String text, int offset, int part, Map<String, String> values,
                             List<Map<String, String>> results, int[] attempts) {
            if (++attempts[0] > 20000) throw new IllegalArgumentException("IMPORT_MATCH_LIMIT");
            if (results.size() > 1) return;
            if (part == parts.size()) {
                if (offset == text.length() && !results.contains(values)) results.add(Map.copyOf(values));
                return;
            }
            Part item = parts.get(part);
            if (item.literal != null) {
                if (text.startsWith(item.literal, offset)) collect(text, offset + item.literal.length(), part + 1, values, results, attempts);
                return;
            }
            String previous = values.get(item.variable);
            for (int end = offset + 1; end <= text.length(); end++) {
                String value = text.substring(offset, end);
                if (item.numeric && !NUMBER.matcher(value).matches()) break;
                if (item.variable.equals("extension") && !value.matches("[A-Za-z0-9]+")) break;
                if (previous != null && !previous.equals(value)) continue;
                values.put(item.variable, value);
                collect(text, end, part + 1, values, results, attempts);
                if (previous == null) values.remove(item.variable); else values.put(item.variable, previous);
            }
        }
    }
}
