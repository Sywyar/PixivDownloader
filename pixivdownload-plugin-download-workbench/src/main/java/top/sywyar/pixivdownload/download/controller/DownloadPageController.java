package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.download.state.DownloadPagePreference;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.plugin.api.web.ApiErrorResponse;

import java.io.IOException;
import java.net.URI;

@RestController
public final class DownloadPageController {
    private final DownloadPagePreference preference;
    private final MessageResolver messages;

    public DownloadPageController(DownloadPagePreference preference, MessageResolver messages) {
        this.preference = preference;
        this.messages = messages;
    }

    @GetMapping({"/pixiv-batch.html", "/pixiv-batch-alt.html"})
    public ResponseEntity<?> page(HttpServletRequest request) throws IOException {
        String page = preference.currentPage();
        String target = request.getContextPath() + "/" + page;
        if (!target.equals(request.getRequestURI())) {
            String query = request.getQueryString();
            return ResponseEntity.status(HttpStatus.FOUND)
                    .cacheControl(CacheControl.noStore())
                    .location(URI.create(target + (query == null ? "" : "?" + query)))
                    .build();
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .body(new ClassPathResource("static/" + page, getClass().getClassLoader()));
    }

    public record Selection(String page) {}

    @PostMapping(value = "/api/batch/page", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> select(@RequestBody Selection selection) throws IOException {
        if (!DownloadPagePreference.supports(selection.page())) {
            return ResponseEntity.badRequest().body(ApiErrorResponse.of(
                    "error.request.param.invalid", messages.get("error.request.param.invalid")));
        }
        preference.save(selection.page());
        return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
    }
}
