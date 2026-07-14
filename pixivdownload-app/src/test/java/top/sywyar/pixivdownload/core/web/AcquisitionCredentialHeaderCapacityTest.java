package top.sywyar.pixivdownload.core.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("取得凭证请求头真实服务器容量")
class AcquisitionCredentialHeaderCapacityTest {

    @Test
    @DisplayName("嵌入式服务器接受前端声明上限长度的取得凭证请求头")
    void embeddedServerAcceptsSupportedCredentialHeader() throws Exception {
        SpringApplication application = new SpringApplication(TestWebApplication.class);
        try (ConfigurableApplicationContext context = application.run(
                "--server.port=0",
                "--spring.config.import=",
                "--spring.main.banner-mode=off",
                "--spring.jmx.enabled=false")) {
            int port = ((ServletWebServerApplicationContext) context)
                    .getWebServer().getPort();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + port + "/credential-capacity-probe"))
                    .header(AcquisitionCredentialResolver.HEADER_NAME,
                            "a".repeat(AcquisitionCredentialResolver.MAX_LENGTH))
                    .GET()
                    .build();

            HttpResponse<Void> response = HttpClient.newHttpClient().send(
                    request, HttpResponse.BodyHandlers.discarding());

            assertThat(response.statusCode()).isEqualTo(204);
        }
    }

    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration(exclude = DataSourceAutoConfiguration.class)
    @Import(ProbeController.class)
    static class TestWebApplication {
    }

    @RestController
    static class ProbeController {

        @GetMapping("/credential-capacity-probe")
        ResponseEntity<Void> probe(
                @RequestHeader(AcquisitionCredentialResolver.HEADER_NAME) String credential) {
            return credential.length() == AcquisitionCredentialResolver.MAX_LENGTH
                    ? ResponseEntity.noContent().build()
                    : ResponseEntity.badRequest().build();
        }
    }
}
