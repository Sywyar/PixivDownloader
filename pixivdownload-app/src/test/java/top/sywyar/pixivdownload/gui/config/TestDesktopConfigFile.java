package top.sywyar.pixivdownload.gui.config;

import top.sywyar.pixivdownload.gui.DesktopUiHost;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/** Test-only adapter for GUI configuration codec tests. */
public final class TestDesktopConfigFile implements DesktopUiHost.ConfigFile {
    private final ConfigFileEditor editor;

    public TestDesktopConfigFile(Path path) {
        editor = new ConfigFileEditor(path);
    }

    @Override
    public Map<String, String> readAll(Collection<String> keys) throws IOException {
        return editor.readAll(keys);
    }

    @Override
    public void writeAll(Map<String, String> values) throws IOException {
        editor.writeAll(values);
    }

    @Override
    public void removeAll(Collection<String> keys) throws IOException {
        editor.removeAll(keys);
    }

    @Override
    public DesktopUiHost.ConfigSnapshot snapshot() throws IOException {
        ConfigFileEditor.FileSnapshot snapshot = editor.snapshot();
        return new DesktopUiHost.ConfigSnapshot(snapshot.existed(), snapshot.lines());
    }

    @Override
    public void restore(DesktopUiHost.ConfigSnapshot snapshot) throws IOException {
        editor.restore(new ConfigFileEditor.FileSnapshot(snapshot.existed(), snapshot.lines()));
    }
}
