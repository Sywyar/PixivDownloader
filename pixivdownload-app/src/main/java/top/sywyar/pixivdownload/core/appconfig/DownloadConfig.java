package top.sywyar.pixivdownload.core.appconfig;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.config.DownloadSettings;
import top.sywyar.pixivdownload.core.work.directory.WorkDirectoryTemplate;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Configuration
@ConfigurationProperties(prefix = "download")
public class DownloadConfig implements DownloadSettings {
    public static final String DEFAULT_TEMPLATE = "pixiv-download/{artwork_id}";
    public static final String DEFAULT_USER_TEMPLATE = "pixiv-download/{username}/{R18}/{artwork_id}";
    private volatile String rootFolder = DEFAULT_TEMPLATE;
    private Map<String, String> modeTemplates = new LinkedHashMap<>(Map.of("user", DEFAULT_USER_TEMPLATE));

    public String getRootFolder() {
        return WorkDirectoryTemplate.parse(rootFolder, "download.root-folder").fixedPrefix().toString();
    }

    @Override public Map<String, String> directoryOverrides() { return Map.copyOf(modeTemplates); }

    @Override
    public String directoryTemplate(String mode) {
        String override = mode == null ? null : modeTemplates.get(mode);
        boolean useOverride = override != null && !override.isBlank();
        String selected = useOverride ? override : rootFolder;
        WorkDirectoryTemplate.parse(selected, useOverride ? "download.mode-templates." + mode : "download.root-folder");
        return selected;
    }

    @PostConstruct
    public void validateTemplates() {
        WorkDirectoryTemplate.parse(rootFolder, "download.root-folder");
        modeTemplates.forEach((mode, value) -> {
            boolean known = java.util.Arrays.stream(top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode.values())
                    .anyMatch(candidate -> candidate.code().equals(mode));
            if (!known) throw new IllegalArgumentException("unknown download acquisition mode");
            if (value != null && !value.isBlank()) WorkDirectoryTemplate.parse(value, "download.mode-templates." + mode);
        });
    }

    /** 设置保存与启动共用模板解析；空模式覆盖表示回退，不代表错误。 */
    public static void validateValue(String key, String value) {
        if ("download.root-folder".equals(key)) {
            WorkDirectoryTemplate.parse(value, key);
        } else if (key.startsWith("download.mode-templates.")) {
            String mode = key.substring("download.mode-templates.".length());
            if (java.util.Arrays.stream(top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode.values())
                    .noneMatch(candidate -> candidate.code().equals(mode)))
                throw new top.sywyar.pixivdownload.core.work.directory.DirectoryTemplateException("unknown-mode", "");
            if (value != null && !value.isBlank()) WorkDirectoryTemplate.parse(value, key);
        }
    }

    /**
     * 同时下载的图片 / 作品数上限。图片下载任务跑在专用的 {@code downloadTaskExecutor} 线程池上，
     * 超出该上限的作品最多排队 100 个，队列已满时拒绝新任务。调小可降低被 Pixiv 限流的概率。
     * 线程池大小在启动时确定，修改后需重启服务才能生效。
     */
    private int maxConcurrent = 10;

    public int getMaxConcurrent() {
        return Math.max(1, maxConcurrent);
    }

}
