package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 进程生命周期内使用的窄渲染器上下文。
 * 渲染器只能观察不可变文档、解析文本、发出带修订号的事件并使用桌面外壳生命周期设置；
 * 应用业务服务和插件实例始终留在宿主内部。
 */
public final class DesktopUiContext {
    private final boolean startupLaunch;
    private final String applicationName;
    private final DesktopUiModel model;
    private final Function<DesktopUiNode.TextToken, String> textResolver;
    private final Runnable applicationExit;
    private final Supplier<String> themePreference;
    private final String providerId;
    private final Set<DesktopUiNode.Kind> supportedKinds;
    private final Set<DesktopUiCapability> supportedCapabilities;

    /**
     * 为选中的 provider 创建并校验渲染器上下文。
     *
     * @param startupLaunch 是否由操作系统启动项启动
     * @param applicationName 原生窗口标题
     * @param model 宿主拥有的文档与事件模型
     * @param textResolver 宿主统一文本解析器
     * @param applicationExit 宿主拥有的进程退出请求
     * @param themePreference 当前共享主题偏好提供者
     * @param providerId 用于诊断的已选 provider id
     * @param supportedKinds provider 已实现的节点种类
     * @param supportedCapabilities provider 已实现的语义能力
     */
    public DesktopUiContext(boolean startupLaunch, String applicationName, DesktopUiModel model,
                            Function<DesktopUiNode.TextToken, String> textResolver,
                            Runnable applicationExit, Supplier<String> themePreference,
                            String providerId, Set<DesktopUiNode.Kind> supportedKinds,
                            Set<DesktopUiCapability> supportedCapabilities) {
        this.startupLaunch = startupLaunch;
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
        this.model = Objects.requireNonNull(model, "model");
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.applicationExit = Objects.requireNonNull(applicationExit, "applicationExit");
        this.themePreference = Objects.requireNonNull(themePreference, "themePreference");
        this.providerId = Objects.requireNonNull(providerId, "providerId");
        this.supportedKinds = Set.copyOf(Objects.requireNonNull(supportedKinds, "supportedKinds"));
        this.supportedCapabilities = Set.copyOf(Objects.requireNonNull(
                supportedCapabilities, "supportedCapabilities"));
        currentSnapshot();
    }

    /** @return 是否由操作系统启动项启动 */
    public boolean startupLaunch() { return startupLaunch; }

    /** @return 原生桌面外壳使用的应用标题 */
    public String applicationName() { return applicationName; }

    /** @return 通过 provider 兼容性校验的当前原子快照 */
    public DesktopUiSnapshot currentSnapshot() {
        DesktopUiSnapshot snapshot = Objects.requireNonNull(model.snapshot(), "model returned null snapshot");
        validate(snapshot.document());
        return snapshot;
    }

    /**
     * 通过宿主统一解析器解析一个宿主或插件拥有的文本 token。
     *
     * @param token 待解析的文本 token
     * @return 已解析的显示文本
     */
    public String resolveText(DesktopUiNode.TextToken token) {
        return Objects.requireNonNull(textResolver.apply(Objects.requireNonNull(token, "token")),
                "textResolver returned null");
    }

    /**
     * 派发已经携带渲染文档修订号的事件。
     *
     * @param event 已盖修订号的渲染器事件
     */
    public void dispatchEvent(DesktopUiNode.Event event) {
        DesktopUiNode.Event value = Objects.requireNonNull(event, "event");
        if (value.documentRevision() < 0) {
            throw new IllegalArgumentException("renderer event must carry a document revision");
        }
        if (value.type() == DesktopUiNode.EventType.ACTIVATE) {
            if (value.interactionRevision() >= 0L) {
                throw new IllegalArgumentException("activation event must not carry an interaction revision");
            }
        } else if (value.interactionRevision() < 0L) {
            throw new IllegalArgumentException("value event must carry an interaction revision");
        }
        model.dispatch(value);
    }

    /**
     * 使用产生控件的同一份快照给事件意图盖章。
     *
     * @param snapshot 产生控件的原子快照
     * @param event 渲染器事件意图
     */
    public void dispatchEvent(DesktopUiSnapshot snapshot, DesktopUiNode.Event event) {
        DesktopUiSnapshot observed = Objects.requireNonNull(snapshot, "snapshot");
        DesktopUiNode.Event value = Objects.requireNonNull(event, "event");
        if (value.type() == DesktopUiNode.EventType.ACTIVATE) {
            dispatchEvent(value.atRevision(observed.revision()));
            return;
        }
        Long interactionRevision = observed.interactionRevisions().get(value.nodeId());
        if (interactionRevision == null) {
            throw new IllegalArgumentException("snapshot has no interaction revision for node " + value.nodeId());
        }
        dispatchEvent(value.atRevisions(observed.revision(), interactionRevision));
    }

    /**
     * 给动作事件意图盖上产生控件时观察到的精确文档修订号。
     *
     * @param documentRevision 产生控件时观察到的文档修订号
     * @param event 渲染器动作事件意图
     */
    public void dispatchEvent(long documentRevision, DesktopUiNode.Event event) {
        DesktopUiNode.Event value = Objects.requireNonNull(event, "event");
        if (value.type() != DesktopUiNode.EventType.ACTIVATE) {
            throw new IllegalArgumentException("value event must be stamped from a desktop UI snapshot");
        }
        dispatchEvent(value.atRevision(documentRevision));
    }

    /** 请求宿主拥有的进程退出路径。 */
    public void requestApplicationExit() { applicationExit.run(); }

    /** @return 当前共享的 SYSTEM/LIGHT/DARK 主题偏好 */
    public String themePreference() {
        String value = themePreference.get();
        return value == null || value.isBlank() ? "system" : value.trim().toLowerCase(java.util.Locale.ROOT);
    }

    private void validate(DesktopUiDocument document) {
        Set<DesktopUiNode.Kind> missingKinds = new LinkedHashSet<>(document.requiredNodeKinds());
        missingKinds.removeAll(supportedKinds);
        Set<DesktopUiCapability> missingCapabilities = new LinkedHashSet<>(document.requiredCapabilities());
        missingCapabilities.removeAll(supportedCapabilities);
        if (!missingKinds.isEmpty() || !missingCapabilities.isEmpty()) {
            throw new IllegalStateException("Desktop UI provider '" + providerId
                    + "' cannot render document; missing kinds=" + missingKinds
                    + ", missing capabilities=" + missingCapabilities);
        }
    }
}
