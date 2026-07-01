package top.sywyar.pixivdownload.gui.plugin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import java.util.ArrayList;
import java.util.List;

/**
 * GUI 插件管理面板的<b>纯展示模型</b>：把 {@code /api/gui/plugins/status} 的响应（可达性 + HTTP 状态码 + JSON 正文）
 * 解析为面板可直接渲染的不可变模型，并提供来源 / 状态 / 阶段的本地化标签工具方法。
 *
 * <p>本类<b>不</b>依赖 Swing、<b>不</b>发起网络请求，便于无界面（headless）下做纯逻辑测试——HTTP 调用由
 * {@code GuiConfigTestClient} 负责、UI 渲染由 {@code PluginsPanel} 负责，三者职责分离。它<b>不复制</b>任何插件扫描或
 * 状态判断逻辑：状态语义全部来自后端投影，本类只做「响应 → 展示行」的结构化映射与标签本地化。
 */
public final class GuiPluginStatusModel {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一次状态读取的结果分类。 */
    public enum Outcome {
        /** 成功取得并解析了状态。 */
        OK,
        /** 后端连接不上（未启动 / 正在启动 / 已停止）。 */
        OFFLINE,
        /** 后端可达但拒绝（本机校验未通过 / 缺 GUI token）。 */
        FORBIDDEN,
        /** 其它读取失败（非 2xx，或正文无法解析）。 */
        ERROR
    }

    /** 插件来源。 */
    public enum Source {
        BUILT_IN, EXTERNAL, NOT_INSTALLED, UNKNOWN
    }

    /**
     * 单个插件的展示行。
     *
     * @param id        插件 id
     * @param name      展示名称（后端已解析；至少为 id）
     * @param source    来源
     * @param statusCode 状态码（{@code PluginStatus} 名，如 {@code STARTED}；可空）
     * @param phaseCode  运行期阶段码（{@code PluginRuntimePhase} 名；仅受管外置插件有，否则为 {@code null}）
     * @param managed   是否受运行期生命周期管理
     * @param required  是否必选
     * @param version   版本（可空）
     * @param verificationStatus 验签状态码（可空）
     */
    public record Row(String id, String name, Source source, String statusCode,
                      String phaseCode, boolean managed, boolean required, String version,
                      String verificationStatus) {
    }

    private final Outcome outcome;
    private final boolean recoveryMode;
    private final List<Row> rows;

    private GuiPluginStatusModel(Outcome outcome, boolean recoveryMode, List<Row> rows) {
        this.outcome = outcome;
        this.recoveryMode = recoveryMode;
        this.rows = List.copyOf(rows);
    }

    /** 后端连接不上时的模型（GUI 在后端未运行时使用）。 */
    public static GuiPluginStatusModel offline() {
        return new GuiPluginStatusModel(Outcome.OFFLINE, false, List.of());
    }

    /**
     * 从一次 {@code /api/gui/plugins/status} 调用的结果构造模型。
     *
     * @param reachable  后端是否可达（{@code GuiConfigTestClient.Response.reachable()}）
     * @param httpStatus HTTP 状态码（可达时有效）
     * @param body       响应正文（JSON；可空）
     */
    public static GuiPluginStatusModel fromResponse(boolean reachable, int httpStatus, String body) {
        if (!reachable) {
            return offline();
        }
        if (httpStatus == 403) {
            return new GuiPluginStatusModel(Outcome.FORBIDDEN, false, List.of());
        }
        if (httpStatus < 200 || httpStatus >= 300) {
            return new GuiPluginStatusModel(Outcome.ERROR, false, List.of());
        }
        if (body == null || body.isBlank()) {
            return new GuiPluginStatusModel(Outcome.ERROR, false, List.of());
        }
        try {
            JsonNode root = MAPPER.readTree(body);
            boolean recovery = root.path("recoveryMode").asBoolean(false);
            List<Row> rows = new ArrayList<>();
            JsonNode plugins = root.path("plugins");
            if (plugins.isArray()) {
                for (JsonNode node : plugins) {
                    rows.add(toRow(node));
                }
            }
            return new GuiPluginStatusModel(Outcome.OK, recovery, rows);
        } catch (Exception e) {
            return new GuiPluginStatusModel(Outcome.ERROR, false, List.of());
        }
    }

    private static Row toRow(JsonNode node) {
        String id = textOrNull(node, "id");
        String name = textOrNull(node, "name");
        return new Row(
                id,
                (name == null || name.isBlank()) ? id : name,
                parseSource(textOrNull(node, "source")),
                textOrNull(node, "status"),
                textOrNull(node, "runtimePhase"),
                node.path("managed").asBoolean(false),
                node.path("required").asBoolean(false),
                textOrNull(node, "version"),
                textOrNull(node.path("verification"), "status"));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return (value == null || value.isNull()) ? null : value.asText();
    }

    /** 后端来源字符串 → {@link Source}；未知串归 {@link Source#UNKNOWN}。 */
    public static Source parseSource(String source) {
        if (source == null) {
            return Source.UNKNOWN;
        }
        return switch (source) {
            case "built-in" -> Source.BUILT_IN;
            case "external" -> Source.EXTERNAL;
            case "not-installed" -> Source.NOT_INSTALLED;
            default -> Source.UNKNOWN;
        };
    }

    public Outcome outcome() {
        return outcome;
    }

    public boolean recoveryMode() {
        return recoveryMode;
    }

    public List<Row> rows() {
        return rows;
    }

    // ── 本地化标签（GUI i18n；未知码优雅回退到原始码）────────────────────────────────

    /** 来源标签。 */
    public static String sourceLabel(Source source) {
        return GuiMessages.get("gui.plugins.source." + sourceToken(source));
    }

    private static String sourceToken(Source source) {
        return switch (source) {
            case BUILT_IN -> "built-in";
            case EXTERNAL -> "external";
            case NOT_INSTALLED -> "not-installed";
            case UNKNOWN -> "unknown";
        };
    }

    /** 状态标签；未知状态码回退到原始码。 */
    public static String statusLabel(String statusCode) {
        return localizedOr("gui.plugins.status.", statusCode);
    }

    /** 运行期阶段标签；未知阶段码回退到原始码；{@code null} / 空返回空串（不展示阶段）。 */
    public static String phaseLabel(String phaseCode) {
        return localizedOr("gui.plugins.phase.", phaseCode);
    }

    /** 验签状态标签；未知状态码回退到原始码。 */
    public static String verificationLabel(String statusCode) {
        return localizedOr("gui.plugins.verification.", statusCode);
    }

    /**
     * 在指定前缀下解析码对应文案；码为空返回空串；GUI bundle 缺该 key（{@link GuiMessages#get} 回显 key 本身）时
     * 回退到原始码，避免在界面上暴露 {@code gui.plugins.*} 这样的内部 key。
     */
    private static String localizedOr(String keyPrefix, String code) {
        if (code == null || code.isBlank()) {
            return "";
        }
        String key = keyPrefix + code;
        String text = GuiMessages.get(key);
        return key.equals(text) ? code : text;
    }
}
