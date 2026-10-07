package top.sywyar.pixivdownload.config.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import top.sywyar.pixivdownload.config.SslConfig;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("实例地址的统一配置与运行时事实")
class ServerAddressProviderTest {
    @Test
    @DisplayName("实际端口优先且域名热更新不改变配置端口")
    void usesActualPortAndCurrentDomain() {
        var environment = new MockEnvironment().withProperty("server.port", "6999")
                .withProperty("local.server.port", "7000");
        var ssl = new SslConfig();
        var address = new ServerAddressProvider(environment, ssl);
        assertThat(address.uri("/invite?code=ABC").toString()).isEqualTo("http://localhost:7000/invite?code=ABC");
        ssl.setDomain("gallery.example.test");
        assertThat(address.uri("setup.html").toString()).isEqualTo("http://gallery.example.test:7000/setup.html");
        assertThat(environment.getProperty("server.port")).isEqualTo("6999");
    }

    @Test
    @DisplayName("默认端口省略，IPv6 正确加括号，非法域名不能改变协议和端口")
    void formatsStandardPortsAndIpv6() {
        var environment = new MockEnvironment().withProperty("server.port", "443")
                .withProperty("server.ssl.enabled", "true");
        var ssl = new SslConfig();
        var address = new ServerAddressProvider(environment, ssl);
        ssl.setDomain("::1");
        assertThat(address.uri("/invite?code=ABC").toString()).isEqualTo("https://[::1]/invite?code=ABC");
        environment.setProperty("server.port", "7443");
        ssl.setDomain("[::1]");
        assertThat(address.baseUri().toString()).isEqualTo("https://[::1]:7443");
        for (String invalid : new String[]{"https://evil.test/path", "host@evil.test", "evil.test/path", "evil.test#fragment"}) {
            ssl.setDomain(invalid);
            assertThat(address.baseUri().toString()).isEqualTo("https://localhost:7443");
        }
        environment.setProperty("server.ssl.enabled", "false");
        environment.setProperty("server.port", "80");
        ssl.setDomain(" ");
        assertThat(address.baseUri().toString()).isEqualTo("http://localhost");
    }
}
