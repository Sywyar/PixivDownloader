package top.sywyar.pixivdownload.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

/** 构造旧版恢复清单，不借助生产解析器过滤损坏输入。 */
public final class DeleteStagingManifestFixture {
    private DeleteStagingManifestFixture() {}

    public static void write(Path directory, List<DeleteStagingManifest.Entry> entries) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("version", "1");
        properties.setProperty("count", Integer.toString(entries.size()));
        for (int index = 0; index < entries.size(); index++) {
            properties.setProperty(index + ".original", entries.get(index).originalFile().toString());
            properties.setProperty(index + ".staged", entries.get(index).stagedFileName());
        }
        try (var writer = Files.newBufferedWriter(directory.resolve("manifest.properties"), StandardCharsets.UTF_8)) {
            properties.store(writer, "legacy recovery fixture");
        }
    }
}
