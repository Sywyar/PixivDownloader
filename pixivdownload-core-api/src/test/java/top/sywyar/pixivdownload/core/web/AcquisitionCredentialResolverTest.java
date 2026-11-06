package top.sywyar.pixivdownload.core.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("取得凭证请求头解析器")
class AcquisitionCredentialResolverTest {
    @Test
    @DisplayName("去除凭证首尾空白，空值与空白视为未提供")
    void normalizesCredential() {
        assertThat(AcquisitionCredentialResolver.resolve(" generic ")).isEqualTo("generic");
        assertThat(AcquisitionCredentialResolver.resolve(null)).isNull();
        assertThat(AcquisitionCredentialResolver.resolve(" \t ")).isNull();
    }

    @Test
    @DisplayName("接受声明上限长度并拒绝更长的取得凭证")
    void enforcesCredentialLengthLimit() {
        String maximum = "a".repeat(AcquisitionCredentialResolver.MAX_LENGTH);
        assertThat(AcquisitionCredentialResolver.resolve(maximum)).isEqualTo(maximum);
        assertThatIllegalArgumentException().isThrownBy(() ->
                AcquisitionCredentialResolver.resolve(maximum + "a"));
    }
}
