package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Narrow process-lifetime renderer context.
 * Renderers can observe immutable documents, resolve text, emit revisioned events, and use only desktop chrome
 * lifecycle settings. Application business services and plugin instances deliberately remain host-internal.
 */
public final class DesktopUiContext {
    private final boolean startupLaunch;
    private final String applicationName;
    private final DesktopUiModel model;
    private final Function<DesktopUiNode.TextToken, String> textResolver;
    private final Runnable applicationExit;
    private final Supplier<String> themePreference;

    /** Creates a validated renderer context for the selected provider. */
    public DesktopUiContext(boolean startupLaunch, String applicationName, DesktopUiModel model,
                            Function<DesktopUiNode.TextToken, String> textResolver,
                            Runnable applicationExit, Supplier<String> themePreference) {
        this.startupLaunch = startupLaunch;
        this.applicationName = Objects.requireNonNull(applicationName, "applicationName");
        this.model = Objects.requireNonNull(model, "model");
        this.textResolver = Objects.requireNonNull(textResolver, "textResolver");
        this.applicationExit = Objects.requireNonNull(applicationExit, "applicationExit");
        this.themePreference = Objects.requireNonNull(themePreference, "themePreference");
    }

    /** @return whether startup was initiated by an operating-system startup entry */
    public boolean startupLaunch() { return startupLaunch; }

    /** @return application title used by native desktop chrome */
    public String applicationName() { return applicationName; }

    /** @return current immutable document */
    public DesktopUiDocument currentDocument() {
        return Objects.requireNonNull(model.document(), "model returned null document");
    }

    /** @return current monotonic document revision */
    public long currentDocumentRevision() { return model.revision(); }

    /** Resolves one host- or plugin-owned text token through the host's unified resolver. */
    public String resolveText(DesktopUiNode.TextToken token) {
        return Objects.requireNonNull(textResolver.apply(Objects.requireNonNull(token, "token")),
                "textResolver returned null");
    }

    /** Dispatches an event that already carries the revision of the rendered document. */
    public void dispatchEvent(DesktopUiNode.Event event) {
        DesktopUiNode.Event value = Objects.requireNonNull(event, "event");
        if (value.documentRevision() < 0) {
            throw new IllegalArgumentException("renderer event must carry a document revision");
        }
        model.dispatch(value);
    }

    /** Stamps an event intent with the exact revision from which its control was rendered. */
    public void dispatchEvent(long documentRevision, DesktopUiNode.Event event) {
        dispatchEvent(Objects.requireNonNull(event, "event").atRevision(documentRevision));
    }

    /** Requests the host-owned process exit path. */
    public void requestApplicationExit() { applicationExit.run(); }

    /** @return current shared SYSTEM/LIGHT/DARK theme preference */
    public String themePreference() {
        String value = themePreference.get();
        return value == null || value.isBlank() ? "system" : value.trim().toLowerCase(Locale.ROOT);
    }
}
