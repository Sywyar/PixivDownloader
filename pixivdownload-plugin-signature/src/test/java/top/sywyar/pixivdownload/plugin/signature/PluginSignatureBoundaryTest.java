package top.sywyar.pixivdownload.plugin.signature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("plugin-signature 模块边界")
class PluginSignatureBoundaryTest {

    @Test
    @DisplayName("生产代码中的签名原语只出现在 signature 模块 internal 实现包")
    void signaturePrimitivesStayInternal() throws Exception {
        Path root = Path.of("src/main/java");
        try (var files = Files.walk(root)) {
            assertThat(files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> {
                        try {
                            String text = Files.readString(path);
                            return text.contains("Signature.getInstance") || text.contains("KeyFactory.getInstance");
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(path -> path.toString().replace('\\', '/'))
                    .toList())
                    .containsExactlyInAnyOrder(
                            "src/main/java/top/sywyar/pixivdownload/plugin/signature/internal/ed25519/Ed25519Verifier.java",
                            "src/main/java/top/sywyar/pixivdownload/plugin/signature/internal/ed25519/Ed25519Signer.java",
                            "src/main/java/top/sywyar/pixivdownload/plugin/signature/internal/trust/KeyParsing.java");
        }
    }
}
