package top.sywyar.pixivdownload.plugin.api.gui;

import java.awt.EventQueue;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * 纯 JDK GUI 主题贡献契约。
 *
 * @param themeId 全局稳定的主题 ID
 * @param displayNameProvider 感知 locale 的展示名称提供者
 * @param appearance 报告的明暗分类
 * @param applier 仅通过 {@link #applyOnEventDispatchThread()} 调用的主题应用器
 * @param listenerFactory 创建可关闭监听会话的工厂
 */
public record GuiThemeContribution(
        String themeId,
        Function<Locale, String> displayNameProvider,
        GuiThemeAppearance appearance,
        GuiThemeApplier applier,
        GuiThemeListenerFactory listenerFactory
) {

    /**
     * 校验主题 ID 和全部行为入口。
     *
     * @param themeId 主题标识
     * @param displayNameProvider 显示名称提供器
     * @param appearance 外观
     * @param applier 应用器
     * @param listenerFactory 监听器工厂
     */
    public GuiThemeContribution {
        if (themeId == null || themeId.isBlank()) {
            throw new IllegalArgumentException("GUI theme contribution requires a non-blank theme id");
        }
        displayNameProvider = Objects.requireNonNull(displayNameProvider,
                "GUI theme displayNameProvider must not be null");
        appearance = Objects.requireNonNull(appearance, "GUI theme appearance must not be null");
        applier = Objects.requireNonNull(applier, "GUI theme applier must not be null");
        listenerFactory = Objects.requireNonNull(listenerFactory, "GUI theme listenerFactory must not be null");
    }

    /**
     * 创建不带监听器的主题贡献。
     *
     * @param themeId 全局稳定的主题 ID
     * @param displayNameProvider 感知 locale 的展示名称提供者
     * @param appearance 报告的明暗分类
     * @param applier 主题应用器
     */
    public GuiThemeContribution(String themeId, Function<Locale, String> displayNameProvider,
                                GuiThemeAppearance appearance, GuiThemeApplier applier) {
        this(themeId, displayNameProvider, appearance, applier, listener -> GuiThemeListenerSession.none());
    }

    /**
     * 解析指定 locale 下的展示名称。locale 为 {@code null} 时使用 {@link Locale#getDefault()}。
     *
     * @param locale 目标 locale，或 {@code null}
     * @return 非空白展示名称
     */
    public String displayName(Locale locale) {
        Locale effectiveLocale = locale == null ? Locale.getDefault() : locale;
        String name = displayNameProvider.apply(effectiveLocale);
        if (name == null || name.isBlank()) {
            throw new IllegalStateException("GUI theme displayNameProvider returned a blank name for theme: "
                    + themeId);
        }
        return name;
    }

    /**
     * 应用主题。调用方必须先到达 AWT 事件分派线程；从其它线程调用会在执行贡献代码前被拒绝。
     *
     * @throws Exception 主题应用器无法应用主题时抛出
     */
    public void applyOnEventDispatchThread() throws Exception {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("GUI theme must be applied on the AWT event dispatch thread: " + themeId);
        }
        applier.apply();
    }

    /**
     * 为该主题创建监听会话。
     *
     * @param listener 接收外观变化的回调
     * @return 非空的可关闭监听会话
     */
    public GuiThemeListenerSession openListener(GuiThemeChangeListener listener) {
        Objects.requireNonNull(listener, "GUI theme change listener must not be null");
        GuiThemeListenerSession session = listenerFactory.open(listener);
        return Objects.requireNonNull(session, "GUI theme listenerFactory returned null");
    }
}
