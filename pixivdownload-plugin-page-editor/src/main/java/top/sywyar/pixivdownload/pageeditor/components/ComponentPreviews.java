package top.sywyar.pixivdownload.pageeditor.components;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.time.*;
import java.util.*;

/** 预览能力只存在于当前插件实例，关闭、到期或卸载后不能再次读取。 */
public final class ComponentPreviews implements AutoCloseable {
    private record Preview(ObjectNode source, Instant expires) { }
    private final Map<String, Preview> previews = new HashMap<>();
    private final Clock clock;
    public ComponentPreviews() { this(Clock.systemUTC()); }
    ComponentPreviews(Clock clock) { this.clock = clock; }
    private void expire() { previews.values().removeIf(value -> !value.expires().isAfter(clock.instant())); }
    public synchronized String create(JsonNode source) {
        ObjectNode valid = ComponentLibrary.source(source);
        expire();
        if (previews.size() >= 8) throw ComponentLibrary.invalid("PAGE_COMPONENT_PREVIEW_LIMIT");
        String token = UUID.randomUUID().toString();
        previews.put(token, new Preview(valid, clock.instant().plusSeconds(600)));
        return token;
    }
    public synchronized ObjectNode read(String token) {
        expire();
        Preview preview = previews.get(token);
        if (preview == null) throw ComponentLibrary.invalid("PAGE_COMPONENT_UNAVAILABLE");
        return preview.source().deepCopy();
    }
    public synchronized void remove(String token) { previews.remove(token); }
    public synchronized boolean exists(String token) { expire(); return previews.containsKey(token); }
    @Override public synchronized void close() { previews.clear(); }
}
