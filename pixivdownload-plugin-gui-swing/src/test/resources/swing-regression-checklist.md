# Swing manual regression gate

Run this checklist before a desktop provider migration release. Record the Windows version, JDK,
display scale, locale, desktop provider and result in the release evidence.

## Matrix

- Locales: Simplified Chinese, English and Traditional Chinese.
- Display scales: 100%, 125% and 150%.
- Tray modes: supported and unavailable.

## Acceptance

- The main window opens at 960 x 720 logical pixels and cannot shrink below 760 x 560.
- Closing hides the window when the tray icon was installed; tray activation restores it. Without a tray,
  closing requests application exit.
- Welcome steps, ordinary/offline/update/progress status, configuration categories, expand-all, plugin
  sections, sensitive fields, plugin recovery, tools, security and about remain reachable without clipped,
  overlapping or inaccessible controls.
- The interface category shows language, desktop provider, provider-scoped theme and expand-all preferences;
  they persist only through the page's single save action, and changing the provider requests a full restart.
- Image-classifier and folder-checker dialogs fit the usable screen area and retain their declared size.
- Locale refresh preserves the selected page, inner tab, split divider, scroll position, caret and focus.
- Text, icons, focus indicators, disabled states, tooltips and scrollbars remain legible at every scale.

Automated structure and interaction coverage lives in `MainFrameRegressionTest`, `GuiSwingPluginTest`,
`GuiThemeManagerTest`, `InterfacePreferencesPanelTest`, `ConfigPanelRestartTest` and
`GuiMessageUsageGuardTest`. Pixel screenshots are intentionally not a
cross-platform gate because Look-and-Feel, font rasterization and operating-system chrome are platform-owned.
