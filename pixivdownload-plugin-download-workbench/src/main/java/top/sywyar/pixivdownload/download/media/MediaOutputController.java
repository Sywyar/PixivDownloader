package top.sywyar.pixivdownload.download.media;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/api/download/media")
public class MediaOutputController {
    private final MediaOutputSettings settings;
    private final MediaMaintenanceService maintenance;
    private final MediaCapabilityService capabilities;

    public MediaOutputController(MediaOutputSettings settings, MediaMaintenanceService maintenance, MediaCapabilityService capabilities) {
        this.settings = settings;
        this.maintenance = maintenance;
        this.capabilities = capabilities;
    }

    @GetMapping("/settings")
    public Defaults defaults() {
        return new Defaults(settings.getImageFormats(), settings.getUgoiraFormats());
    }

    public record Defaults(String imageFormats, String ugoiraFormats) {}

    @PostMapping("/preview")
    public MediaMaintenanceService.Preview preview(@RequestBody MediaMaintenanceService.Request request) throws java.io.IOException {
        return maintenance.preview(request);
    }

    public record Start(String token) {}

    @PostMapping("/start")
    public MediaMaintenanceService.Status start(@RequestBody Start request) { return maintenance.start(request.token()); }

    @GetMapping("/status")
    public MediaMaintenanceService.Status status() { return maintenance.status(); }

    @PostMapping("/cancel")
    public void cancel() { maintenance.cancel(); }

    @GetMapping("/capabilities")
    public MediaCapabilityService.Report capabilities() throws java.io.IOException { return capabilities.check(); }
}
