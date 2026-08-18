package top.sywyar.pixivdownload.config.credential;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

public class PluginCredentialStore {
    public Map<String, String> readAll(String owner) throws IOException { return SwingHost.host().readCredentials(owner); }
    public void update(String owner, Map<String, String> updates) throws IOException { SwingHost.host().updateCredentials(owner, updates); }
    public void withOwnerLocks(Collection<String> owners, IoOperation operation) throws IOException {
        SwingHost.host().withCredentialLocks(owners, operation::run);
    }
    public Snapshot snapshot(String owner) throws IOException {
        DesktopUiHost.CredentialSnapshot value = SwingHost.host().snapshotCredentials(owner);
        return new Snapshot(value.existed(), value.content());
    }
    public void restore(String owner, Snapshot snapshot) throws IOException {
        SwingHost.host().restoreCredentials(owner, new DesktopUiHost.CredentialSnapshot(snapshot.existed(), snapshot.content()));
    }
    public record Snapshot(boolean existed, byte[] content) {
        public Snapshot { content = content == null ? new byte[0] : content.clone(); }
        @Override public byte[] content() { return content.clone(); }
    }
    @FunctionalInterface public interface IoOperation { void run() throws IOException; }
}
