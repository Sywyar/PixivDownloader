package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiDocument;
import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
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
    private final String providerId;
    private final Set<DesktopUiNode.Kind> supportedKinds;
    private final Set<DesktopUiCapability> supportedCapabilities;

    /**
     * Creates a validated renderer context for the selected provider.
     *
     * @param startupLaunch whether startup came from an operating-system startup entry
     * @param applicationName native window title
     * @param model host-owned document and event model
     * @param textResolver unified host text resolver
     * @param applicationExit host-owned process exit request
     * @param themePreference current shared theme preference supplier
     * @param providerId selected provider id used in diagnostics
     * @param supportedKinds node kinds implemented by the provider
     * @param supportedCapabilities semantic capabilities implemented by the provider
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

    /** @return whether startup was initiated by an operating-system startup entry */
    public boolean startupLaunch() { return startupLaunch; }

    /** @return application title used by native desktop chrome */
    public String applicationName() { return applicationName; }

    /** @return 通过 provider 兼容性校验的当前原子快照 */
    public DesktopUiSnapshot currentSnapshot() {
        DesktopUiSnapshot snapshot = Objects.requireNonNull(model.snapshot(), "model returned null snapshot");
        validate(snapshot.document());
        return snapshot;
    }

    /**
     * Resolves one host- or plugin-owned text token through the host's unified resolver.
     *
     * @param token text token to resolve
     * @return resolved display text
     */
    public String resolveText(DesktopUiNode.TextToken token) {
        return Objects.requireNonNull(textResolver.apply(Objects.requireNonNull(token, "token")),
                "textResolver returned null");
    }

    /**
     * Dispatches an event that already carries the revision of the rendered document.
     *
     * @param event revision-stamped renderer event
     */
    public void dispatchEvent(DesktopUiNode.Event event) {
        DesktopUiNode.Event value = Objects.requireNonNull(event, "event");
        if (value.documentRevision() < 0) {
            throw new IllegalArgumentException("renderer event must carry a document revision");
        }
        model.dispatch(value);
    }

    /**
     * Stamps an event intent with the exact revision from which its control was rendered.
     *
     * @param documentRevision revision from which the control was rendered
     * @param event renderer event intent
     */
    public void dispatchEvent(long documentRevision, DesktopUiNode.Event event) {
        dispatchEvent(Objects.requireNonNull(event, "event").atRevision(documentRevision));
    }

    /** Requests the host-owned process exit path. */
    public void requestApplicationExit() { applicationExit.run(); }

    /** @return current shared SYSTEM/LIGHT/DARK theme preference */
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
