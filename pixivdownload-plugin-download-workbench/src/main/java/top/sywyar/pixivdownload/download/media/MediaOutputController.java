package top.sywyar.pixivdownload.download.media;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/download/media")
public class MediaOutputController {
    private final MediaOutputSettings settings;
    public MediaOutputController(MediaOutputSettings settings) {
        this.settings = settings;
    }

    @GetMapping("/settings")
    public Defaults defaults() {
        return new Defaults(settings.getImageFormats(), settings.getUgoiraFormats());
    }

    public record Defaults(String imageFormats, String ugoiraFormats) {}

}
