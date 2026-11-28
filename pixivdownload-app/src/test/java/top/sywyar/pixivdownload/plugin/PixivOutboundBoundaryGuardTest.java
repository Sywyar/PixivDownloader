package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pixiv 出站边界 lint：插件访问 {@code www.pixiv.net} / {@code *.pximg.net} 必须经宿主
 * {@link PixivAjaxClient} 与 {@code PixivMediaClient}，不能用通用 {@code plugin.api.http} 客户端自行请求。
 *
 * <p>本检查逐个扫描官方插件类文件的常量池：同一个类既引用 {@code plugin.api.http}、又含 Pixiv 主机字面量时判为违规。
 * 编译期常量会内联到使用它的类，因此直接拼接 Pixiv 地址的请求代码会被发现；只在别处出现的 Pixiv 链接（例如通知占位示例）不算违规。
 * 它是启发式 lint，看不到运行期从上游 JSON 或非常量字段得到的地址，不能单独证明边界；实际请求头与出口行为由宿主端口的行为测试负责。
 */
@DisplayName("Pixiv 出站边界 lint：插件经宿主 Pixiv 端口访问 Pixiv")
class PixivOutboundBoundaryGuardTest {

    private static final String GENERIC_HTTP_PACKAGE = "top/sywyar/pixivdownload/plugin/api/http/";
    private static final String CLASSES_PROPERTY_SUFFIX = ".plugin.classes";

    @Test
    @DisplayName("官方插件不得用通用出站 HTTP 客户端访问 Pixiv 主机")
    void officialPluginsReachPixivOnlyThroughHostPorts() throws IOException {
        Map<String, Path> modules = new TreeMap<>();
        System.getProperties().stringPropertyNames().stream()
                .filter(name -> name.endsWith(CLASSES_PROPERTY_SUFFIX))
                .forEach(name -> {
                    Path classes = Path.of(System.getProperty(name));
                    if (Files.isDirectory(classes)) {
                        modules.put(name.substring(0, name.length() - CLASSES_PROPERTY_SUFFIX.length()), classes);
                    }
                });
        Assumptions.assumeFalse(modules.isEmpty(), "官方插件 target/classes 尚未构建，跳过 Pixiv 出站边界 lint");

        List<String> violations = new ArrayList<>();
        for (var module : modules.entrySet()) {
            for (byte[] bytes : classFiles(module.getValue())) {
                if (violates(bytes)) {
                    violations.add(module.getKey() + ":" + ClassFile.of().parse(bytes).thisClass().asInternalName().replace('/', '.'));
                }
            }
        }
        assertThat(violations).as("这些插件用通用出站 HTTP 访问 Pixiv，应改用 PixivAjaxClient / PixivMediaClient").isEmpty();
    }

    @Test
    @DisplayName("lint 只拒绝同一个类里通用客户端与 Pixiv 主机同时出现")
    void lintDistinguishesGenericPixivAccessFromHostPortsAndOtherHosts() {
        assertThat(violates(bytes(GenericClientToPixiv.class))).isTrue();
        assertThat(violates(bytes(GenericClientWithInlinedPixivConstant.class))).isTrue();
        assertThat(violates(bytes(HostPortToPixiv.class))).isFalse();
        assertThat(violates(bytes(GenericClientElsewhere.class))).isFalse();
        assertThat(violates(bytes(PixivLinkWithoutRequest.class))).isFalse();
    }

    static boolean violates(byte[] classFile) {
        boolean genericHttp = false;
        boolean pixivHost = false;
        for (PoolEntry entry : ClassFile.of().parse(classFile).constantPool()) {
            switch (entry) {
                case ClassEntry type when type.asInternalName().startsWith(GENERIC_HTTP_PACKAGE) -> genericHttp = true;
                case Utf8Entry text when text.stringValue().contains("L" + GENERIC_HTTP_PACKAGE) -> genericHttp = true;
                case StringEntry text when isPixivHost(text.stringValue()) -> pixivHost = true;
                default -> { }
            }
        }
        return genericHttp && pixivHost;
    }

    private static boolean isPixivHost(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("pixiv.net") || lower.contains("pximg.net");
    }

    private static List<byte[]> classFiles(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(path -> path.toString().endsWith(".class")).map(path -> {
                try {
                    return Files.readAllBytes(path);
                } catch (IOException failure) {
                    throw new UncheckedIOException(failure);
                }
            }).toList();
        }
    }

    private static byte[] bytes(Class<?> type) {
        String resource = "/" + type.getName().replace('.', '/') + ".class";
        try (InputStream input = type.getResourceAsStream(resource)) {
            assertThat(input).as(resource).isNotNull();
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    // --- lint 夹具：只解析字节码，不会被实例化或调用 ---

    private static final class GenericClientToPixiv {
        Object request(OutboundHttpClient client) {
            return client.exchangeStream(new OutboundHttpRequest(
                    URI.create("https://www.pixiv.net/ranking.php"), "GET", Map.of(), null));
        }
    }

    private static final class GenericClientElsewhere {
        Object request(OutboundHttpClient client) {
            return client.exchangeStream(new OutboundHttpRequest(URI.create("https://example.com/"), "GET", Map.of(), null));
        }
    }

    private static final class PixivHosts {
        static final String WEB = "https://www.pixiv.net";
    }

    /** 引用其它类的编译期常量时，拼接结果内联进本类常量池。 */
    private static final class GenericClientWithInlinedPixivConstant {
        Object request(OutboundHttpClient client, long id) {
            return client.exchangeStream(new OutboundHttpRequest(
                    URI.create(PixivHosts.WEB + "/artworks/" + id), "GET", Map.of(), null));
        }
    }

    private static final class PixivLinkWithoutRequest {
        Map<String, String> placeholders() {
            return Map.of("work_url", "https://www.pixiv.net/artworks/12345678");
        }
    }

    private static final class HostPortToPixiv {
        String read(PixivAjaxClient ajax) {
            return ajax.get(URI.create("https://www.pixiv.net/ajax/illust/1"), null);
        }
    }
}
