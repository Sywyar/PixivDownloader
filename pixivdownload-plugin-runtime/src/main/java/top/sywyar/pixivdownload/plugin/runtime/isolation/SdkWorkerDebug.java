package top.sywyar.pixivdownload.plugin.runtime.isolation;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

import java.time.Duration;
import java.util.List;

/** SDK 显式调试的单个已准入产物；不参与插件信任或执行模式判断。 */
record SdkWorkerDebug(String pluginId, String sha256, int port, boolean connect) {

    static final String ID_PROPERTY = "pixivdownload.sdk.debug.plugin-id";
    static final String SHA256_PROPERTY = "pixivdownload.sdk.debug.artifact-sha256";
    static final String PORT_PROPERTY = "pixivdownload.sdk.debug.port";
    static final String CONNECT_PROPERTY = "pixivdownload.sdk.debug.connect";
    static final Duration TIMEOUT = Duration.ofMinutes(5);

    static SdkWorkerDebug fromSystemProperties() {
        String id = System.getProperty(ID_PROPERTY);
        String digest = System.getProperty(SHA256_PROPERTY);
        String port = System.getProperty(PORT_PROPERTY);
        String connect = System.getProperty(CONNECT_PROPERTY);
        if (id == null && digest == null && port == null && connect == null) {
            return null;
        }
        if (id == null || !PluginDescriptor.ID_PATTERN.matcher(id).matches()
                || digest == null || !digest.matches("[a-f0-9]{64}")
                || port == null || !port.matches("[0-9]{1,5}")
                || connect != null && !List.of("true", "false").contains(connect)) {
            throw new IllegalArgumentException("invalid SDK worker debug target");
        }
        int parsedPort = Integer.parseInt(port);
        if (parsedPort < 1 || parsedPort > 65535) {
            throw new IllegalArgumentException("SDK worker debug port must be between 1 and 65535");
        }
        return new SdkWorkerDebug(id, digest, parsedPort, Boolean.parseBoolean(connect));
    }

    boolean matches(String id, String verifiedSha256) {
        return pluginId.equals(id) && sha256.equals(verifiedSha256);
    }

    String agentArgument() {
        // worker stdout 承载二进制 IPC，JDWP 的监听提示必须关闭。
        return "-agentlib:jdwp=transport=dt_socket,server=" + (connect ? "n" : "y")
                + ",suspend=y,quiet=y,address=127.0.0.1:"
                + port + ",timeout=" + TIMEOUT.toMillis();
    }
}
