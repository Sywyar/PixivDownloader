package top.sywyar.pixivdownload.plugin.api.gui;

import java.awt.EventQueue;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * Pure JDK GUI theme contribution contract.
 *
 * @param themeId             globally stable theme id
 * @param displayNameProvider locale-aware display name provider
 * @param appearance          reported brightness classification
 * @param applier             theme applier, invoked only through {@link #applyOnEventDispatchThread()}
 * @param listenerFactory     factory opening a closeable listener session
 */
public record GuiThemeContribution(
        String themeId,
        Function<Locale, String> displayNameProvider,
        GuiThemeAppearance appearance,
        GuiThemeApplier applier,
        GuiThemeListenerFactory listenerFactory
) {

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
     * Creates a contribution without a listener.
     */
    public GuiThemeContribution(String themeId, Function<Locale, String> displayNameProvider,
                                GuiThemeAppearance appearance, GuiThemeApplier applier) {
        this(themeId, displayNameProvider, appearance, applier, listener -> GuiThemeListenerSession.none());
    }

    /**
     * Resolves the display name for a locale. A {@code null} locale uses {@link Locale#getDefault()}.
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
     * Applies the theme. The caller must reach the AWT event dispatch thread before invoking this method; calling it
     * from another thread is rejected before the contribution code runs.
     */
    public void applyOnEventDispatchThread() throws Exception {
        if (!EventQueue.isDispatchThread()) {
            throw new IllegalStateException("GUI theme must be applied on the AWT event dispatch thread: " + themeId);
        }
        applier.apply();
    }

    /**
     * Opens a listener session for this theme.
     */
    public GuiThemeListenerSession openListener(GuiThemeChangeListener listener) {
        Objects.requireNonNull(listener, "GUI theme change listener must not be null");
        GuiThemeListenerSession session = listenerFactory.open(listener);
        return Objects.requireNonNull(session, "GUI theme listenerFactory returned null");
    }
}
