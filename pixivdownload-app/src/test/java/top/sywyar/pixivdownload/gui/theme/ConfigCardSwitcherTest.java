package top.sywyar.pixivdownload.gui.theme;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JFormattedTextField;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("配置卡片切换主题刷新")
class ConfigCardSwitcherTest {

    @Test
    @DisplayName("手动换卡会刷新 detached card 并归一化输入样式")
    void showCardRefreshesDetachedCardAndNormalizesInputs() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JPanel host = new JPanel(new BorderLayout());
            TrackingPanel card = new TrackingPanel();
            JTextField textField = new JTextField("value");
            textField.setHorizontalAlignment(JTextField.RIGHT);
            JSpinner spinner = new JSpinner(new SpinnerNumberModel(1000, 0, 10_000, 1));
            spinner.setEditor(new JSpinner.NumberEditor(spinner, "#,##0"));
            JFormattedTextField spinnerText = ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField();
            spinnerText.setHorizontalAlignment(JTextField.RIGHT);
            spinnerText.setValue(1000);
            card.add(textField, BorderLayout.NORTH);
            card.add(spinner, BorderLayout.CENTER);
            int updateUiCallsBefore = card.updateUiCalls;

            GuiThemeRefresh.showCard(host, card);

            assertThat(host.getComponentCount()).isEqualTo(1);
            assertThat(host.getComponent(0)).isSameAs(card);
            assertThat(card.updateUiCalls).isGreaterThan(updateUiCallsBefore);
            assertThat(textField.getHorizontalAlignment()).isEqualTo(JTextField.LEFT);
            assertThat(spinnerText.getHorizontalAlignment()).isEqualTo(JTextField.LEFT);
            assertThat(spinnerText.getText()).isEqualTo("1000");
        });
    }

    private static final class TrackingPanel extends JPanel {
        private int updateUiCalls;

        private TrackingPanel() {
            super(new BorderLayout());
        }

        @Override
        public void updateUI() {
            updateUiCalls++;
            super.updateUI();
        }
    }
}
