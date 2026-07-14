package top.sywyar.pixivdownload.core.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

@DisplayName("取得凭证请求头解析器")
class AcquisitionCredentialResolverTest {

    @Test
    @DisplayName("通用凭证存在时应优先返回通用值")
    void prefersGenericCredential() {
        assertThat(AcquisitionCredentialResolver.resolve(" generic ", null))
                .isEqualTo("generic");
    }

    @Test
    @DisplayName("通用凭证缺失时应回退到旧凭证")
    void fallsBackToLegacyCredential() {
        assertThat(AcquisitionCredentialResolver.resolve(null, " legacy "))
                .isEqualTo("legacy");
    }

    @Test
    @DisplayName("空白通用凭证应视为缺失并回退")
    void treatsBlankGenericCredentialAsMissing() {
        assertThat(AcquisitionCredentialResolver.resolve(" \t ", "legacy"))
                .isEqualTo("legacy");
    }

    @Test
    @DisplayName("两个空白凭证应解析为空")
    void resolvesBlankCredentialsToNull() {
        assertThat(AcquisitionCredentialResolver.resolve(" ", "\n"))
                .isNull();
    }

    @Test
    @DisplayName("去除首尾空白后相等的双凭证应被允许")
    void allowsEquivalentCredentialsAfterTrimming() {
        assertThat(AcquisitionCredentialResolver.resolve(" same ", "\tsame\n"))
                .isEqualTo("same");
    }

    @Test
    @DisplayName("去除首尾空白后不同的双凭证应明确拒绝")
    void rejectsConflictingCredentials() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AcquisitionCredentialResolver.resolve("generic", "legacy"))
                .withMessage("Conflicting acquisition credential headers");
    }
}
