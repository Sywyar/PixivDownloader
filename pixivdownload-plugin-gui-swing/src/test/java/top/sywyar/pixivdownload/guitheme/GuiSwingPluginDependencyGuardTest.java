package top.sywyar.pixivdownload.guitheme;

import org.junit.jupiter.api.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class GuiSwingPluginDependencyGuardTest {

    @Test
    void externalSwingPluginDoesNotDependOnAppArtifact() throws Exception {
        Path pom = locateModule().resolve("pom.xml");
        var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
        var dependencies = document.getElementsByTagName("dependency");
        Map<String, String> scopes = IntStream.range(0, dependencies.getLength())
                .mapToObj(dependencies::item)
                .collect(Collectors.toMap(
                        node -> child(node, "artifactId"),
                        node -> child(node, "scope"),
                        (left, right) -> left));

        assertThat(scopes).doesNotContainKeys("pixivdownload-app", "spring-web", "sqlite-jdbc");
        assertThat(scopes).containsEntry("pixivdownload-plugin-api", "provided")
                .containsEntry("pixivdownload-core-api", "provided")
                .containsEntry("pixivdownload-plugin-signature", "provided")
                .containsEntry("pf4j", "provided")
                .containsEntry("slf4j-api", "provided");
    }

    private static String child(org.w3c.dom.Node node, String name) {
        var children = node.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (name.equals(children.item(i).getNodeName())) return children.item(i).getTextContent().trim();
        }
        return "";
    }

    private static Path locateModule() {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 8 && current != null; i++, current = current.getParent()) {
            Path direct = current.resolve("pom.xml");
            if (Files.isRegularFile(direct) && "pixivdownload-plugin-gui-swing".equals(current.getFileName().toString())) return current;
            Path nested = current.resolve("pixivdownload-plugin-gui-swing");
            if (Files.isRegularFile(nested.resolve("pom.xml"))) return nested;
        }
        throw new IllegalStateException("pixivdownload-plugin-gui-swing module not found");
    }

    @org.junit.jupiter.api.Test
    void productionSourcesStayInsideSwingUiResponsibilities() throws java.io.IOException {
        java.nio.file.Path root=java.nio.file.Path.of("src/main/java/top/sywyar/pixivdownload");
        java.util.List<String> allowed=java.util.List.of("gui/","guiswing/","guitheme/","imageclassifier/","tools/");
        try(var files=java.nio.file.Files.walk(root)){
            java.util.List<String> violations=files.filter(path->path.toString().endsWith(".java"))
                    .map(root::relativize).map(path->path.toString().replace('\\','/'))
                    .filter(path->allowed.stream().noneMatch(path::startsWith)).sorted().toList();
            org.assertj.core.api.Assertions.assertThat(violations)
                    .as("gui-swing production code must remain Swing UI only").isEmpty();
        }
    }

    @org.junit.jupiter.api.Test
    void desktopToolViewsDoNotOwnPersistenceOrTransport() throws java.io.IOException {
        java.nio.file.Path root = locateModule().resolve("src/main/java/top/sywyar/pixivdownload");
        String sources = java.nio.file.Files.readString(root.resolve("tools/FolderChecker.java"))
                + java.nio.file.Files.readString(root.resolve("imageclassifier/ImageClassifier.java"));
        org.assertj.core.api.Assertions.assertThat(sources)
                .doesNotContain("org.sqlite", "java.sql.", "DriverManager", "RestTemplate",
                        "org.springframework.web", "WorkSidecarFiles", "FileInputStream",
                        "FileOutputStream", "StandardCopyOption");
    }
}
