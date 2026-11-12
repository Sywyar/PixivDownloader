package top.sywyar.pixivdownload.update;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import top.sywyar.pixivdownload.common.AppInfo;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.catalog.PluginCatalogHttpClient;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogErrorCode;
import top.sywyar.pixivdownload.plugin.catalog.error.PluginCatalogException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@DisplayName("更新下载的官方地址与代理边界")
class UpdateHttpClientProviderTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("官方清单与安装包经代理读取，不受本机 Fake-IP 干扰并允许两次跳转")
    void officialDownloadsUseProxyDns(boolean installer) throws Exception {
        var provider = new UpdateHttpClientProvider(new ProxyConfig());
        String url = installer ? AppInfo.RELEASES_URL + "/download/test/installer.exe" : UpdateConfig.DEFAULT_MANIFEST_URL;
        var client = provider.clientFor(url);
        var transport = mock(HttpClient.class);
        var tagged = URI.create(AppInfo.RELEASES_URL + "/download/test/update.json");
        var asset = URI.create("https://release-assets.githubusercontent.com/fixture");
        var first = response(302, tagged.toString(), new byte[0]);
        var second = response(302, asset.toString(), new byte[0]);
        var content = response(200, null, new byte[]{1, 2, 3});
        when(transport.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(first, second, content);
        ReflectionTestUtils.setField(client, "httpClient", transport);
        InetAddress fake = InetAddress.getByName("fc00::52");
        try (var dns = mockStatic(InetAddress.class, CALLS_REAL_METHODS)) {
            dns.when(() -> InetAddress.getAllByName("github.com")).thenReturn(new InetAddress[]{fake});
            dns.when(() -> InetAddress.getAllByName("release-assets.githubusercontent.com"))
                    .thenReturn(new InetAddress[]{fake});
            if (installer) {
                var progress = new AtomicLong();
                Path file = temp.resolve("installer");
                assertThat(client.streamToFile(url, 3, file, progress::set)).isEqualTo(3);
                assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3);
                assertThat(progress.get()).isEqualTo(3);
            } else {
                assertThat(client.fetchBytes(url, 3)).containsExactly(1, 2, 3);
            }
            dns.verify(() -> InetAddress.getAllByName("github.com"), never());
            dns.verify(() -> InetAddress.getAllByName("release-assets.githubusercontent.com"), never());
        }
        var requests = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport, times(3)).send(requests.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(requests.getAllValues()).extracting(HttpRequest::uri).containsExactly(URI.create(url), tagged, asset);
    }

    @Test
    @DisplayName("关闭代理时官方地址继续拒绝非公网解析结果")
    void directOfficialDownloadsKeepAddressChecks() throws Exception {
        var config = new ProxyConfig();
        config.setEnabled(false);
        var client = new UpdateHttpClientProvider(config).clientFor(UpdateConfig.DEFAULT_MANIFEST_URL);
        var transport = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "httpClient", transport);
        InetAddress fake = InetAddress.getByName("fc00::52");
        try (var dns = mockStatic(InetAddress.class, CALLS_REAL_METHODS)) {
            dns.when(() -> InetAddress.getAllByName("github.com")).thenReturn(new InetAddress[]{fake});
            assertBlocked(client, UpdateConfig.DEFAULT_MANIFEST_URL);
        }
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://mirror.invalid/update.json", "https://github.com/another/repository/releases/update.json",
            "https://github.com.attacker.invalid/Sywyar/PixivDownloader/releases/update.json"})
    @DisplayName("自定义更新地址即使走代理也保留本机地址检查")
    void customDownloadsKeepAddressChecks(String url) throws Exception {
        var client = new UpdateHttpClientProvider(new ProxyConfig()).clientFor(url);
        var transport = mock(HttpClient.class);
        ReflectionTestUtils.setField(client, "httpClient", transport);
        String host = URI.create(url).getHost();
        InetAddress fake = InetAddress.getByName("fc00::52");
        try (var dns = mockStatic(InetAddress.class, CALLS_REAL_METHODS)) {
            dns.when(() -> InetAddress.getAllByName(host)).thenReturn(new InetAddress[]{fake});
            assertBlocked(client, url);
        }
        verifyNoInteractions(transport);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://github.com/update.json", "https://127.0.0.1/update.json", "https://[fc00::52]/update.json",
            "https://github.com.attacker.invalid/update.json", "https://evil-githubusercontent.com/update.json"})
    @DisplayName("官方链路拒绝跳出 HTTPS 与 GitHub 域名边界，并关闭被拒响应")
    void rejectsRedirectOutsideBoundary(String target) throws Exception {
        var client = new UpdateHttpClientProvider(new ProxyConfig()).clientFor(UpdateConfig.DEFAULT_MANIFEST_URL);
        var transport = mock(HttpClient.class);
        var body = spy(new ByteArrayInputStream(new byte[0]));
        var redirect = response(302, target, new byte[0]);
        when(redirect.body()).thenReturn(body);
        when(transport.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(redirect);
        ReflectionTestUtils.setField(client, "httpClient", transport);
        assertThatThrownBy(() -> client.fetchBytes(UpdateConfig.DEFAULT_MANIFEST_URL, 100))
                .isInstanceOf(PluginCatalogException.class);
        verify(transport).send(any(), any(HttpResponse.BodyHandler.class));
        verify(body).close();
    }

    @Test
    @DisplayName("正式版、每夜版和签名共用客户端，代理变化生效且不复用自定义策略")
    void reusesClientsAndRefreshesProxySettings() {
        var config = new ProxyConfig();
        var provider = new UpdateHttpClientProvider(config);
        var first = provider.clientFor(UpdateConfig.DEFAULT_MANIFEST_URL);
        assertThat(provider.clientFor(UpdateConfig.DEFAULT_NIGHTLY_MANIFEST_URL)).isSameAs(first);
        assertThat(provider.clientFor(UpdateConfig.DEFAULT_MANIFEST_URL + ".sig")).isSameAs(first);
        assertThat(provider.clientFor("https://mirror.invalid/update.json")).isNotSameAs(first);
        config.setPort(7891);
        var changed = provider.clientFor(UpdateConfig.DEFAULT_MANIFEST_URL);
        assertThat(changed).isNotSameAs(first);
        HttpClient transport = (HttpClient) ReflectionTestUtils.getField(changed, "httpClient");
        assertThat(transport.proxy().orElseThrow().select(URI.create(UpdateConfig.DEFAULT_MANIFEST_URL)))
                .extracting(Proxy::address).containsExactly(new InetSocketAddress("127.0.0.1", 7891));
        config.setEnabled(false);
        var direct = provider.clientFor(UpdateConfig.DEFAULT_MANIFEST_URL);
        assertThat(direct).isNotSameAs(changed);
        HttpClient directTransport = (HttpClient) ReflectionTestUtils.getField(direct, "httpClient");
        assertThat(directTransport.proxy().orElseThrow().select(URI.create(UpdateConfig.DEFAULT_MANIFEST_URL)))
                .containsExactly(Proxy.NO_PROXY);
    }

    @ParameterizedTest
    @ValueSource(ints = {5, 6})
    @DisplayName("官方链路允许五次跳转，第六次拒绝且不继续发送")
    void enforcesRedirectBudget(int redirects) throws Exception {
        var client = new UpdateHttpClientProvider(new ProxyConfig()).clientFor(UpdateConfig.DEFAULT_MANIFEST_URL);
        var transport = mock(HttpClient.class);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        when(transport.send(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation ->
                count.getAndIncrement() < redirects
                        ? response(302, "https://release-assets.githubusercontent.com/" + count.get(), new byte[0])
                        : response(200, null, new byte[]{1}));
        ReflectionTestUtils.setField(client, "httpClient", transport);
        if (redirects == 5) {
            assertThat(client.fetchBytes(UpdateConfig.DEFAULT_MANIFEST_URL, 1)).containsExactly(1);
        } else {
            assertThatThrownBy(() -> client.fetchBytes(UpdateConfig.DEFAULT_MANIFEST_URL, 1))
                    .isInstanceOfSatisfying(PluginCatalogException.class,
                            e -> assertThat(e.code()).isEqualTo(PluginCatalogErrorCode.DOWNLOAD_FAILED));
        }
        assertThat(count.get()).isEqualTo(6);
    }

    private static void assertBlocked(PluginCatalogHttpClient client, String url) {
        assertThatThrownBy(() -> client.fetchBytes(url, 100)).isInstanceOfSatisfying(PluginCatalogException.class,
                e -> assertThat(e.code()).isEqualTo(PluginCatalogErrorCode.BLOCKED_ADDRESS));
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<InputStream> response(int status, String location, byte[] bytes) {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.headers()).thenReturn(HttpHeaders.of(location == null ? Map.of() : Map.of("Location", List.of(location)),
                (name, value) -> true));
        when(response.body()).thenReturn(new ByteArrayInputStream(bytes));
        when(response.uri()).thenReturn(URI.create("https://release-assets.githubusercontent.com/fixture"));
        return response;
    }
}
