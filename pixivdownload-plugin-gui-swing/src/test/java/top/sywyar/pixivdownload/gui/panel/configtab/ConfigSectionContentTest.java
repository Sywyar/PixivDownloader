package top.sywyar.pixivdownload.gui.panel.configtab;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.Box;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("配置区块嵌入共享滚动页")
class ConfigSectionContentTest {
    @Test
    @DisplayName("复用内容组件，只移除末尾的纵向伸缩留白")
    void preservesContentAndFixedSpacing() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel panel = new JPanel();
            Component leadingGlue = Box.createVerticalGlue();
            JLabel field = new JLabel("field");
            Component fixedGap = Box.createVerticalStrut(8);
            panel.add(leadingGlue);
            panel.add(field);
            panel.add(fixedGap);
            panel.add(Box.createVerticalGlue());
            panel.add(Box.createVerticalGlue());

            assertThat(ConfigSection.embeddedContent(new JScrollPane(panel))).isSameAs(panel);
            assertThat(panel.getComponents()).containsExactly(leadingGlue, field, fixedGap);
            assertThat(ConfigSection.embeddedContent(panel)).isSameAs(panel);
            assertThat(panel.getComponents()).containsExactly(leadingGlue, field, fixedGap);
        });
    }

    @Test
    @DisplayName("保留横向留白和未被区块滚动容器包裹的控件")
    void preservesOtherComponents() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel panel = new JPanel();
            Component verticalGlue = Box.createVerticalGlue();
            Component horizontalGlue = Box.createHorizontalGlue();
            panel.add(verticalGlue);
            panel.add(horizontalGlue);
            assertThat(ConfigSection.embeddedContent(panel)).isSameAs(panel);
            assertThat(panel.getComponents()).containsExactly(verticalGlue, horizontalGlue);

            JLabel label = new JLabel("field");
            assertThat(ConfigSection.embeddedContent(label)).isSameAs(label);
            JScrollPane empty = new JScrollPane();
            assertThat(ConfigSection.embeddedContent(empty)).isSameAs(empty);
        });
    }
}
