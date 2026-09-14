package top.sywyar.pixivdownload.sdk.community.project;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

/** 只解析 Maven 已求值的有效模型；不把源 POM 当作最终构建事实。 */
public final class BuildModels {
    public static Object maven(JsonNode input) throws Exception {
        Path file = Path.of(input.get("file").textValue());
        Element project = project(file);
        var xpath = XPathFactory.newInstance().newXPath();
        String version = xpath.evaluate("version", project);
        String directory = xpath.evaluate("build/directory", project);
        String name = xpath.evaluate("build/finalName", project);
        Node jar = (Node) xpath.evaluate("build/plugins/plugin[artifactId='maven-jar-plugin']/configuration", project,
                javax.xml.xpath.XPathConstants.NODE);
        if (jar != null) {
            String customDirectory = xpath.evaluate("outputDirectory", jar);
            String customName = xpath.evaluate("finalName", jar);
            String classifier = xpath.evaluate("classifier", jar);
            if (!customDirectory.isEmpty()) directory = customDirectory;
            if (!customName.isEmpty()) name = customName;
            if (!classifier.isEmpty()) name += "-" + classifier;
        }
        var dependencies = new ArrayList<Map<String, String>>();
        NodeList nodes = (NodeList) xpath.evaluate("dependencies/dependency", project, javax.xml.xpath.XPathConstants.NODESET);
        for (int i = 0; i < nodes.getLength(); i++) {
            Node dependency = nodes.item(i);
            dependencies.add(Map.of("group", xpath.evaluate("groupId", dependency),
                    "name", xpath.evaluate("artifactId", dependency), "version", xpath.evaluate("version", dependency),
                    "scope", xpath.evaluate("scope", dependency)));
        }
        if (version.isBlank() || directory.isBlank() || name.isBlank() || (version + directory + name).contains("${")) {
            throw new ContractException("BUILD_OUTPUT_MISMATCH", "/model");
        }
        return Map.of("version", version, "artifacts", List.of(Path.of(directory).resolve(name + ".jar").toString()),
                "classDirectories", List.of(xpath.evaluate("build/outputDirectory", project)),
                "dependencies", dependencies);
    }

    // 仅报告实际依赖元数据中的声明，不自动判定许可证兼容或把缺项补成允许。
    public static Object dependencies(JsonNode input) throws Exception {
        Path root = Path.of(input.get("root").textValue());
        var metadata = new ArrayList<Object>();
        long total = 0;
        for (JsonNode entry : input.get("files")) {
            var reference = new Reference(entry.get("path").textValue(), entry.get("size").longValue(), entry.get("sha256").textValue());
            if (!reference.path().endsWith(".pom") || reference.size() < 0 || reference.size() > 32 * 1024 * 1024 - total) {
                throw new ContractException("LIMIT_EXCEEDED", "/dependencyMetadata");
            }
            total += reference.size();
            reference.verify(root);
            Element project = project(CommunityPaths.resolve(root, reference.path(), false, true));
            var xpath = XPathFactory.newInstance().newXPath();
            var licenses = new ArrayList<Object>();
            var nodes = (NodeList) xpath.evaluate("licenses/license", project, javax.xml.xpath.XPathConstants.NODESET);
            for (int i = 0; i < nodes.getLength(); i++) licenses.add(Map.of("name", xpath.evaluate("name", nodes.item(i)),
                    "url", xpath.evaluate("url", nodes.item(i))));
            metadata.add(Map.of("reference", reference, "group", xpath.evaluate("groupId", project),
                    "name", xpath.evaluate("artifactId", project), "version", xpath.evaluate("version", project),
                    "licenses", licenses, "licenseStatus", licenses.isEmpty() ? "NOT_DECLARED" : "DECLARED"));
        }
        return metadata;
    }

    private static Element project(Path file) throws Exception {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 32 * 1024 * 1024) {
            throw new ContractException("LIMIT_EXCEEDED", "/model");
        }
        var factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Element project;
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            project = factory.newDocumentBuilder().parse(stream).getDocumentElement();
        }
        if (!project.getTagName().equals("project")) throw new ContractException("SCHEMA_INVALID", "/model");
        return project;
    }
}
