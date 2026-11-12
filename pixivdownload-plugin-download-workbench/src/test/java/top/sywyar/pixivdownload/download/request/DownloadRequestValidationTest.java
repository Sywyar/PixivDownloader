package top.sywyar.pixivdownload.download.request;

import jakarta.validation.Validation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("下载来源输入边界")
class DownloadRequestValidationTest {
    @Test
    @DisplayName("请求递归校验来源模式和来源用户名")
    void validatesNestedSource() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            var request = new DownloadRequest();
            request.setArtworkId(42L);
            request.setTitle("Test");
            request.setImageUrls(List.of("https://i.pximg.net/image.jpg"));
            assertThat(validator.validate(request)).isEmpty();
            request.getOther().setAcquisitionMode("user");
            request.getOther().setUsername("x".repeat(512));
            assertThat(validator.validate(request)).isEmpty();
            request.getOther().setAcquisitionMode("from-series-metadata");
            request.getOther().setUsername("x".repeat(513));
            assertThat(validator.validate(request)).extracting(v -> v.getPropertyPath().toString())
                    .containsExactlyInAnyOrder("other.acquisitionMode", "other.username");
        }
    }
}
