package top.sywyar.pixivdownload.sdk.development;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.sdk.community.submission.MarketPublicationFiles;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SdkMarketContentDownloadTest {
    @TempDir Path root;

    @Test @DisplayName("真实下载命令首次创建全部文件，拒绝覆盖已有输出及被篡改的附件")
    void commandDownloadsAndVerifies() throws Exception {
        Files.writeString(root.resolve("README.md"), "# Original\n", StandardCharsets.UTF_8);
        Path curation = root.resolve("curation.json");
        Files.writeString(curation, """
                {"example":{"defaultLocale":"en","links":[],"documentationSources":{"readme":{"en":"README.md"}}}}
                """, StandardCharsets.UTF_8);
        String base = "https://github.com/example/plugins/releases/download/example-v7.4.2/";
        Path server = root.resolve("responses");
        MarketPublicationFiles.prepare(root, curation, "example", "7.4.2", base, server);
        Path output = root.resolve("download");
        assertThat(run(server, base, output)).isZero();
        var publication = MarketPublicationFiles.verify(output.resolve(MarketPublicationFiles.METADATA), output, base);
        String document = publication.content().readme().get("en").asset().name();
        assertThat(Files.readAllBytes(output.resolve(document))).isEqualTo(Files.readAllBytes(root.resolve("README.md")));
        assertThat(run(server, base, output)).isNotZero();
        Files.writeString(server.resolve(document), "# Modified\n", StandardCharsets.UTF_8);
        assertThat(run(server, base, root.resolve("corrupted"))).isNotZero();
    }

    private int run(Path server, String base, Path output) throws Exception {
        Path log = Files.createTempFile(root, "download-", ".log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                TransportFixture.class.getName(), server.toString(), base, output.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            return process.exitValue();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    /** 只在隔离子 JVM 替代 URL 传输；真实命令、文件创建及合同复验仍执行。 */
    public static final class TransportFixture {
        public static void main(String[] args) throws Exception {
            Path server = Path.of(args[0]);
            URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL url) throws java.io.IOException {
                    byte[] bytes = Files.readAllBytes(server.resolve(Path.of(url.getPath()).getFileName()));
                    return new HttpURLConnection(url) {
                        @Override public int getResponseCode() { return 200; }
                        @Override public long getContentLengthLong() { return bytes.length; }
                        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
                        @Override public void disconnect() { }
                        @Override public boolean usingProxy() { return false; }
                        @Override public void connect() { }
                    };
                }
            } : null);
            SdkTools.execute(new String[] {"market-content-download", args[1], args[2]});
        }
    }
}
