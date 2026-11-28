package top.sywyar.pixivdownload.openapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验随源码发布的 Agent skill 能被 Agent Skills 规范的安装与加载方接受，且内部链接不会断开。
 */
@DisplayName("Agent skill 格式")
class AgentSkillFormatTest {

    /** Agent Skills 规范允许的 frontmatter 字段；claude.ai 遇到其它字段会拒绝上传。 */
    private static final Set<String> FIELDS = Set.of(
            "name", "description", "license", "compatibility", "metadata", "allowed-tools");

    @Test
    @DisplayName("frontmatter 只使用规范字段，名称与目录一致且描述在长度上限内")
    void frontmatterFollowsSpecification() throws Exception {
        AgentSkillDocument skill = AgentSkillDocument.load();
        Map<String, Object> frontmatter = skill.frontmatter();

        assertThat(frontmatter).as("SKILL.md frontmatter").isNotNull();
        assertThat(FIELDS).containsAll(frontmatter.keySet());
        assertThat(frontmatter.get("name")).isInstanceOf(String.class);
        String name = (String) frontmatter.get("name");
        assertThat(name).matches("[a-z0-9]+(-[a-z0-9]+)*").hasSizeLessThanOrEqualTo(64)
                .isEqualTo(skill.directory().getFileName().toString());
        assertThat(frontmatter.get("description")).isInstanceOf(String.class);
        assertThat((String) frontmatter.get("description")).isNotBlank().hasSizeLessThanOrEqualTo(1024);
        if (frontmatter.containsKey("compatibility")) {
            assertThat(frontmatter.get("compatibility")).isInstanceOf(String.class);
            assertThat((String) frontmatter.get("compatibility")).hasSizeLessThanOrEqualTo(500);
        }
    }

    @Test
    @DisplayName("相对链接指向 skill 内已有的文件和标题")
    void relativeLinksResolve() throws Exception {
        AgentSkillDocument skill = AgentSkillDocument.load();
        List<String> broken = new ArrayList<>();
        for (AgentSkillDocument.Link link : skill.relativeLinks()) {
            int hash = link.target().indexOf('#');
            String file = hash < 0 ? link.target() : link.target().substring(0, hash);
            Path target = file.isEmpty() ? link.file() : link.file().getParent().resolve(file).normalize();
            if (!target.startsWith(skill.directory()) || !Files.isRegularFile(target)) {
                broken.add(link.target() + " in " + link.file().getFileName());
            } else if (hash >= 0 && !AgentSkillDocument.anchors(Files.readString(target, StandardCharsets.UTF_8))
                    .contains(link.target().substring(hash + 1))) {
                broken.add(link.target() + " in " + link.file().getFileName() + " (no such heading)");
            }
        }

        assertThat(skill.relativeLinks()).isNotEmpty();
        assertThat(broken).isEmpty();
    }
}
