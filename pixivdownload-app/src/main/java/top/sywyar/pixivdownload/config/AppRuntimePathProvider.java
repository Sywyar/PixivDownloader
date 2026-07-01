package top.sywyar.pixivdownload.config;

import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class AppRuntimePathProvider implements RuntimePathProvider {

    @Override
    public Path resolveEdgeTtsVersionPath() {
        return RuntimeFiles.resolveEdgeTtsVersionPath();
    }
}
