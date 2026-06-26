package top.sywyar.pixivdownload.plugin.catalog.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CatalogPresentationToken} 单测：受控图标 / 颜色 token 净化——合法 token 原样保留（含大小写规整），任何含越界
 * 字符（可用于注入 HTML / SVG / CSS / 脚本）或未知 / 空值回退到稳定默认 token。
 */
@DisplayName("CatalogPresentationToken 受控展示 token 净化")
class CatalogPresentationTokenTest {

    @Test
    @DisplayName("合法图标 token：原样（大小写规整为小写）")
    void validIcon() {
        assertThat(CatalogPresentationToken.sanitizeIcon("chart-line")).isEqualTo("chart-line");
        assertThat(CatalogPresentationToken.sanitizeIcon("  Cloud-Arrow-Down ")).isEqualTo("cloud-arrow-down");
    }

    @Test
    @DisplayName("合法颜色 token：原样")
    void validColor() {
        assertThat(CatalogPresentationToken.sanitizeColor("green")).isEqualTo("green");
        assertThat(CatalogPresentationToken.sanitizeColor("pixiv")).isEqualTo("pixiv");
    }

    @Test
    @DisplayName("空 / null → 默认 token")
    void blankFallsBack() {
        assertThat(CatalogPresentationToken.sanitizeIcon(null)).isEqualTo(CatalogPresentationToken.DEFAULT_ICON);
        assertThat(CatalogPresentationToken.sanitizeIcon("   ")).isEqualTo(CatalogPresentationToken.DEFAULT_ICON);
        assertThat(CatalogPresentationToken.sanitizeColor(null)).isEqualTo(CatalogPresentationToken.DEFAULT_COLOR);
    }

    @Test
    @DisplayName("注入 / 越界字符（标签 / 引号 / 冒号 / 分号 / 空格 / 括号 / 井号）→ 回退默认，不外泄原值")
    void injectionFallsBack() {
        assertThat(CatalogPresentationToken.sanitizeIcon("<svg onload=alert(1)>"))
                .isEqualTo(CatalogPresentationToken.DEFAULT_ICON);
        assertThat(CatalogPresentationToken.sanitizeColor("red;background:url(http://evil)"))
                .isEqualTo(CatalogPresentationToken.DEFAULT_COLOR);
        assertThat(CatalogPresentationToken.sanitizeIcon("fa fa-bolt")).isEqualTo(CatalogPresentationToken.DEFAULT_ICON);
        assertThat(CatalogPresentationToken.sanitizeColor("#0096fa")).isEqualTo(CatalogPresentationToken.DEFAULT_COLOR);
        assertThat(CatalogPresentationToken.sanitizeIcon("javascript:alert(1)"))
                .isEqualTo(CatalogPresentationToken.DEFAULT_ICON);
    }

    @Test
    @DisplayName("非法形态：前导数字 / 前导连字符 / 超长 → 回退；isSafe 与之一致")
    void shapeRules() {
        assertThat(CatalogPresentationToken.sanitizeIcon("1abc")).isEqualTo(CatalogPresentationToken.DEFAULT_ICON);
        assertThat(CatalogPresentationToken.sanitizeIcon("-abc")).isEqualTo(CatalogPresentationToken.DEFAULT_ICON);
        assertThat(CatalogPresentationToken.sanitizeIcon("a".repeat(41))).isEqualTo(CatalogPresentationToken.DEFAULT_ICON);
        assertThat(CatalogPresentationToken.isSafe("chart-line")).isTrue();
        assertThat(CatalogPresentationToken.isSafe("<svg>")).isFalse();
        assertThat(CatalogPresentationToken.isSafe(null)).isFalse();
    }
}
