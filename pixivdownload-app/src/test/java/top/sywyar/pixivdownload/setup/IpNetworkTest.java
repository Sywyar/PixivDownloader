package top.sywyar.pixivdownload.setup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@DisplayName("数值来源 IP 网段")
class IpNetworkTest {
    @Test
    @DisplayName("IPv4 与 IPv6 前缀在边界正确匹配且不混用地址族")
    void matchesPrefixBoundaries() {
        var subnet = IpNetwork.parse("192.0.2.129/25");
        assertThat(subnet.contains(IpNetwork.parseNumericAddress("192.0.2.128"))).isTrue();
        assertThat(subnet.contains(IpNetwork.parseNumericAddress("192.0.2.255"))).isTrue();
        assertThat(subnet.contains(IpNetwork.parseNumericAddress("192.0.2.127"))).isFalse();
        var v6 = IpNetwork.parse("2001:db8::1/64");
        assertThat(v6.contains(IpNetwork.parseNumericAddress("2001:db8::ffff"))).isTrue();
        assertThat(v6.contains(IpNetwork.parseNumericAddress("2001:db8:0:1::"))).isFalse();
        assertThat(v6.contains(IpNetwork.parseNumericAddress("192.0.2.1"))).isFalse();
    }

    @Test
    @DisplayName("主机名、区域标记与越界输入均不能变成网络授权")
    void rejectsNonNumericAndMalformedInputs() {
        for (String input : new String[] {"localhost", "example.com", "127.1", "256.0.0.1", "fe80::1%1", "[::1]"})
            assertThat(IpNetwork.parseNumericAddress(input)).isNull();
        for (String input : new String[] {"localhost/0", "0.0.0.0/33", "::/-1", "::/129", "1.2.3.4", "::/0/0"})
            assertThatIllegalArgumentException().isThrownBy(() -> IpNetwork.parse(input));
    }
}
