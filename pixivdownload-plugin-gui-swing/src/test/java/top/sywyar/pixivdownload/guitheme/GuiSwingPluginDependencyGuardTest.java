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
}
