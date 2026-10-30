package top.sywyar.pixivdownload.pageeditor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.assertj.core.api.Assertions.*;

class PageStylesTest {
    @Test @DisplayName("CSS 声明保留顺序并规范化属性名，重复声明取末项")
    void declarationsRoundTripThroughDocumentValidation() {
        var styles = PageStyles.parse(" Padding : 16px;\ncolor: var(--text); padding: 24px; background-color: rgba(0, 0, 0, 0.2);");
        assertThat(styles.path("padding").asText()).isEqualTo("24px");
        assertThat(styles.path("color").asText()).isEqualTo("var(--text)");
        assertThat(styles.size()).isEqualTo(3);
        assertThat(PageStyles.validate(styles)).isEqualTo(styles);
        assertThat(PageStyles.parse(" ;\n ")).isEmpty();
    }

    @Test @DisplayName("拒绝脚本、外部资源、选择器和不受支持的 CSS，不能忽略非法的重复声明")
    void rejectsUnsupportedAndUnsafeDeclarations() {
        for (String css : new String[]{"padding 4px", "color:", "color: red !important", "body {color:red}",
                "@import 'x';", "/* test */ color:red", "position:fixed", "display:none", "display: NoNe ",
                "color:expression(alert(1))", "color:red; color:url(https://example.test)",
                "color:url(https://example.test); color:red", "--value:red", "color:red:blue",
                "color:red; broken", "color:r\\65 d"}) {
            assertThatThrownBy(() -> PageStyles.parse(css)).as(css)
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("PAGE_STYLE_INVALID");
        }
    }

    @Test @DisplayName("CSS 输入复用文档与单值预算，边界值可用而超限拒绝")
    void enforcesExistingBudgets() {
        assertThatThrownBy(() -> PageStyles.validate(PageDocuments.parse("{\"display\":\" NoNe \"}"))).hasMessage("PAGE_STYLE_INVALID");
        assertThat(PageStyles.parse("color:" + "a".repeat(128)).path("color").textValue()).hasSize(128);
        assertThatThrownBy(() -> PageStyles.parse("color:" + "a".repeat(129))).hasMessage("PAGE_STYLE_INVALID");
        assertThat(PageStyles.parse(" ".repeat(PageDocuments.MAX_BYTES))).isEmpty();
        assertThatThrownBy(() -> PageStyles.parse(" ".repeat(PageDocuments.MAX_BYTES + 1))).hasMessage("PAGE_DOCUMENT_BUDGET");
    }
}
