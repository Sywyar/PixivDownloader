package top.sywyar.pixivdownload.gui.panel.configtab;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryConfigEntry;
import top.sywyar.pixivdownload.plugin.api.gui.RepositoryImportPreview;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** 描述符导入仅向设置弹窗返回已确认的草稿。 */
final class RepositoryImportPanel extends JPanel {
    private final JTextField url = new JTextField(36);
    private final JButton previewButton = new JButton(SwingHost.host().message("gui.config.market.repo.import.preview"));
    private final JButton acceptButton = new JButton(SwingHost.host().message("gui.config.market.repo.import.accept"));
    private final JCheckBox confirm = new JCheckBox();
    private final JPanel facts = new JPanel();
    private final JTextArea status = text("");
    private final Predicate<String> availableId;
    private final Consumer<RepositoryConfigEntry> accept;
    private RepositoryImportPreview preview;
    private SwingWorker<?, ?> worker;
    private long revision;

    RepositoryImportPanel(RepositoryConfigEntry existing, Predicate<String> availableId,
                          Consumer<RepositoryConfigEntry> accept) {
        super(new BorderLayout(8, 12));
        this.availableId = availableId;
        this.accept = accept;
        setBorder(BorderFactory.createEmptyBorder(16, 16, 8, 16));
        url.setText(existing == null ? "" : String.valueOf(existing.extraFields().getOrDefault("descriptor-url", "")));
        JLabel label = new JLabel(SwingHost.host().message("gui.config.market.repo.import.url"));
        label.setLabelFor(url);
        JPanel entry = new JPanel(new BorderLayout(8, 6));
        entry.add(label, BorderLayout.NORTH);
        entry.add(url, BorderLayout.CENTER);
        entry.add(previewButton, BorderLayout.EAST);
        JPanel header = new JPanel(new BorderLayout(8, 8));
        header.add(text(SwingHost.host().message("gui.config.market.repo.import.description")), BorderLayout.NORTH);
        header.add(entry, BorderLayout.CENTER);
        add(header, BorderLayout.NORTH);
        facts.setLayout(new BoxLayout(facts, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(facts);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        add(scroll, BorderLayout.CENTER);
        JPanel consent = new JPanel(new BorderLayout(8, 8));
        confirm.getAccessibleContext().setAccessibleName(SwingHost.host().message("gui.config.market.repo.import.confirm"));
        consent.add(confirm, BorderLayout.WEST);
        JTextArea consentLabel = text(SwingHost.host().message("gui.config.market.repo.import.confirm"));
        consentLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                if (confirm.isEnabled()) confirm.doClick();
            }
        });
        consent.add(consentLabel, BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(8, 8));
        footer.add(status, BorderLayout.NORTH);
        footer.add(consent, BorderLayout.CENTER);
        footer.add(acceptButton, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        previewButton.addActionListener(event -> preview());
        acceptButton.addActionListener(event -> confirm());
        confirm.addActionListener(event -> refreshButtons());
        url.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent event) { invalidatePreview(); }
            public void removeUpdate(DocumentEvent event) { invalidatePreview(); }
            public void changedUpdate(DocumentEvent event) { invalidatePreview(); }
        });
        refreshButtons();
    }

    private void invalidatePreview() {
        revision++;
        preview = null;
        confirm.setSelected(false);
        facts.removeAll();
        status.setText("");
        refreshButtons();
        revalidate();
        repaint();
    }

    private void refreshButtons() {
        boolean busy = worker != null;
        boolean allowed = preview != null && !preview.repositoryIdConflict() && availableId.test(preview.repositoryId());
        url.setEnabled(!busy);
        previewButton.setEnabled(!busy && !url.getText().isBlank());
        confirm.setEnabled(!busy && allowed);
        acceptButton.setEnabled(!busy && allowed && confirm.isSelected());
    }

    private void preview() {
        String descriptorUrl = url.getText().trim();
        invalidatePreview();
        run(() -> SwingHost.host().previewPluginRepository(descriptorUrl), value -> {
            preview = value;
            fact("repository", value.displayName() + " (" + value.repositoryId() + ")");
            fact("publisher", value.publisherDisplayName() + " (" + value.publisherId() + ")");
            fact("homepage", value.publisherHomepageUrl());
            fact("descriptor", value.descriptorUrl());
            fact("digest", value.descriptorSha256());
            fact("catalog", value.catalogProtocol() + " · " + value.catalogEndpoint());
            fact("network", String.join(", ", value.networkHosts()));
            fact("policy", value.effectiveProxyPolicy() + " · " + value.redirectBoundary());
            fact("revocations", value.revocationsUrl());
            fact("update-proof", value.updateProofUrl());
            fact("proof-status", SwingHost.host().message("gui.config.market.repo.import.status." + value.updateProofStatus()));
            fact("directory", SwingHost.host().message("gui.config.market.repo.import.status." + value.communityDirectoryStatus()));
            value.trustedKeys().forEach(key -> fact("key", key.keyId() + " · " + key.algorithm()
                    + " · " + SwingHost.host().message("gui.config.market.repo.trust.state." + key.state().toLowerCase(java.util.Locale.ROOT))
                    + " · " + key.publisher() + " · " + key.trustLabel() + "\n" + key.fingerprint()));
            facts.add(text(SwingHost.host().message("gui.config.market.repo.import.executable-warning")));
            if (value.repositoryIdConflict() || !availableId.test(value.repositoryId())) status.setText(SwingHost.host().message("gui.config.market.repo.import.conflict"));
        });
    }

    private void confirm() {
        RepositoryImportPreview value = preview;
        if (worker != null || value == null || !confirm.isSelected()
                || value.repositoryIdConflict() || !availableId.test(value.repositoryId())) return;
        run(() -> SwingHost.host().preparePluginRepository(value.descriptorUrl(), value.descriptorSha256(), true), accept);
    }

    private <T> void run(java.util.concurrent.Callable<T> task, Consumer<T> done) {
        if (worker != null) return;
        long expected = ++revision;
        status.setText(SwingHost.host().message("gui.config.market.repo.import.loading"));
        worker = new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                if (revision != expected || !isDisplayable()) return;
                worker = null;
                status.setText("");
                try { done.accept(get()); }
                catch (Exception failure) {
                    preview = null;
                    confirm.setSelected(false);
                    Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                    status.setText(cause.getMessage());
                }
                refreshButtons();
                revalidate();
                repaint();
            }
        };
        refreshButtons();
        worker.execute();
    }

    void cancelRequest() {
        revision++;
        if (worker != null) worker.cancel(true);
        worker = null;
        status.setText("");
        refreshButtons();
    }

    @Override public void removeNotify() {
        cancelRequest();
        super.removeNotify();
    }

    private void fact(String name, String value) {
        JLabel label = new JLabel(SwingHost.host().message("gui.config.market.repo.import.field." + name));
        label.setAlignmentX(LEFT_ALIGNMENT);
        facts.add(label);
        facts.add(text(value == null || value.isBlank() ? "—" : value));
        facts.add(Box.createVerticalStrut(10));
    }

    private static JTextArea text(String value) {
        JTextArea area = new JTextArea(value);
        area.setEditable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("Label.font"));
        area.setAlignmentX(LEFT_ALIGNMENT);
        return area;
    }

}
