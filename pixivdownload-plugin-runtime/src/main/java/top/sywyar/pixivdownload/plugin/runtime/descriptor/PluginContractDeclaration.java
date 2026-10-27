package top.sywyar.pixivdownload.plugin.runtime.descriptor;

/** 中性契约原始 API JAR 的包内位置及字节身份；外层插件包仍须通过正常信任准入。 */
public record PluginContractDeclaration(String apiJar, String sha256) {
    public PluginContractDeclaration {
        if (apiJar == null || !apiJar.matches("lib/[A-Za-z0-9][A-Za-z0-9._-]*\\.jar")
                || sha256 == null || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("invalid contract API jar or SHA-256");
        }
    }
}
