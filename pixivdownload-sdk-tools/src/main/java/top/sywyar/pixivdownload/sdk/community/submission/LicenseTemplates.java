package top.sywyar.pixivdownload.sdk.community.submission;

import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/** 内置 LICENSE 文本仅供显式选择；既有文件永不自动覆盖。 */
public final class LicenseTemplates {
    public record Template(String id, String path, String source, long size, String sha256) { }
    private static final List<Template> TEMPLATES = templates();
    private LicenseTemplates() { }

    public static List<Template> available() { return TEMPLATES; }

    public static byte[] bytes(String id) throws IOException {
        Template template = TEMPLATES.stream().filter(item -> item.id().equals(id)).findFirst()
                .orElseThrow(() -> ContractException.invalid("CATALOG_VALUE_UNKNOWN", "/license/template"));
        try (var input = LicenseTemplates.class.getResourceAsStream("/community/v1/" + template.path())) {
            if (input == null) throw new IOException("missing license template");
            byte[] bytes = input.readAllBytes();
            CommunityValues.verifyBytes(bytes, template.size(), template.sha256(), template.path());
            return bytes;
        }
    }

    public static void create(String id, Path file) throws IOException {
        Files.write(file, bytes(id), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static List<Template> templates() {
        var values = new java.util.ArrayList<Template>();
        for (var item : ControlledCatalogs.resource("spdx.json").get("templates")) {
            values.add(new Template(item.get("id").textValue(), item.get("path").textValue(),
                    item.get("source").textValue(), item.get("size").longValue(), item.get("sha256").textValue()));
        }
        return List.copyOf(values);
    }
}
