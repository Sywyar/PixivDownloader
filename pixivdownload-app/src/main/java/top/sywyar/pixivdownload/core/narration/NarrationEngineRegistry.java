package top.sywyar.pixivdownload.core.narration;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.tts.narration.engine.NarrationVoiceEngine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 朗读引擎<b>注册工厂</b>：把自动发现的 {@code List<NarrationVoiceEngine>} 建成「小写 id → 引擎」索引，供
 * {@code NarrationAudioService} 按 {@code narration-tts.engine} 取用（仿 {@code AiPresetRegistry} /
 * {@code List<PushChannel>} 惯例）。id 冲突在启动时即抛 {@link IllegalStateException}，避免「同名两个引擎、
 * 谁生效不确定」的隐患。
 */
@Component
public class NarrationEngineRegistry {

    private final Object lock = new Object();
    private final Map<String, List<NarrationVoiceEngine>> byPlugin = new LinkedHashMap<>();
    private volatile Map<String, NarrationVoiceEngine> byId = Map.of();
    private volatile List<NarrationVoiceEngine> all = List.of();

    public NarrationEngineRegistry(List<NarrationVoiceEngine> engines) {
        if (engines != null && !engines.isEmpty()) {
            byPlugin.put("core", List.copyOf(engines));
            rebuild();
        }
    }

    public void register(String pluginId, List<NarrationVoiceEngine> engines) {
        synchronized (lock) {
            if (engines == null || engines.isEmpty()) {
                byPlugin.remove(pluginId);
            } else {
                byPlugin.put(pluginId, List.copyOf(engines));
            }
            rebuild();
        }
    }

    public void unregister(String pluginId) {
        synchronized (lock) {
            byPlugin.remove(pluginId);
            rebuild();
        }
    }

    private void rebuild() {
        Map<String, NarrationVoiceEngine> map = new LinkedHashMap<>();
        List<NarrationVoiceEngine> engines = new ArrayList<>();
        for (List<NarrationVoiceEngine> list : byPlugin.values()) {
            for (NarrationVoiceEngine engine : list) {
                if (engine == null) {
                    continue;
                }
                String rawId = engine.id();
                if (rawId == null || rawId.isBlank()) {
                    throw new IllegalStateException(
                            "narration engine has blank id: " + engine.getClass().getName());
                }
                String id = rawId.trim().toLowerCase(Locale.ROOT);
                NarrationVoiceEngine prev = map.putIfAbsent(id, engine);
                if (prev != null) {
                    throw new IllegalStateException("duplicate narration engine id '" + id + "': "
                            + prev.getClass().getName() + " vs " + engine.getClass().getName());
                }
                engines.add(engine);
            }
        }
        this.byId = Map.copyOf(map);
        this.all = List.copyOf(engines);
    }

    /** 按 id 取引擎（大小写不敏感）；空 id / 无匹配返回 {@link Optional#empty()}。 */
    public Optional<NarrationVoiceEngine> byId(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /** 解析 {@code narration-tts.engine} 配置值对应的引擎（语义化别名，等价于 {@link #byId(String)}）。 */
    public Optional<NarrationVoiceEngine> selected(String engineId) {
        return byId(engineId);
    }

    /** 全部已注册引擎（注册顺序，不可变）。 */
    public List<NarrationVoiceEngine> all() {
        return all;
    }

    /** 已注册引擎数量（供日志 / 诊断）。 */
    public int count() {
        return all.size();
    }
}
