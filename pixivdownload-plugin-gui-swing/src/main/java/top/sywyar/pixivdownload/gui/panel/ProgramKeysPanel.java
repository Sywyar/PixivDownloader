package top.sywyar.pixivdownload.gui.panel;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.GuiResponse;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.GuiValue;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiText;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 安全页内的程序访问密钥：概览中列出密钥，创建与编辑切换为同一标签页内的整页编辑器。
 * 网络和持久化请求不占用 EDT，完整秘密只在创建成功后显示一次，离开页面即清除。
 */
final class ProgramKeysPanel extends SwingPage.Block {
    /** 由承载页切换概览与编辑器。 */
    interface Navigator {
        void showEditor();
        void showOverview();
    }

    private enum Mode { LIST, EDIT, REVIEW, CREATED, PROMPT }
    private static final String REFERENCE_PERMISSION = "core:api-reference.read";
    private static final String SELF_PATH = "/api/security/program-keys/self";

    private final Navigator navigator;
    private final SwingPage.RowList list = new SwingPage.RowList();
    private final SwingPage.Text notice = SwingPage.text(SwingPage.Style.BODY, "");
    private final JButton refresh = new JButton(text("refresh"));
    private final JButton create = SwingPage.primary(new JButton(text("create")));

    private final JPanel editor = new JPanel(new BorderLayout());
    private final ScrollablePagePanel editorContent = SwingPage.page();
    private final JPanel editorActions = SwingPage.actions();
    private final SwingPage.Text editorNotice = SwingPage.text(SwingPage.Style.BODY, "");

    private final JTextField name = new JTextField(24);
    private final JTextArea networks = new JTextArea(4, 24);
    private final JRadioButton anySource = new JRadioButton(text("source.any"));
    private final JRadioButton restrictedSource = new JRadioButton(text("source.restricted"));
    private final Map<String, JCheckBox> permissions = new LinkedHashMap<>();
    private final Map<String, String> ownerLabels = new LinkedHashMap<>();
    private JComponent editForm;
    private boolean unavailablePermissions;

    private List<GuiValue> catalog = List.of();
    private List<GuiValue> keys = List.of();
    private GuiValue editing;
    private Mode mode = Mode.LIST;
    private boolean busy;
    private boolean uncertain;
    private boolean ready;
    private boolean shown;
    private long visit;
    private JTextField secret;
    private Component returnFocus;

    ProgramKeysPanel(Navigator navigator) {
        this.navigator = navigator;
        refresh.setName("program-keys.refresh");
        create.setName("program-keys.create");
        add(SwingPage.section(text("keys.title"), text("keys.description"), refresh, create));
        add(list);
        notice.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        add(notice);
        refresh.addActionListener(event -> load());
        create.setEnabled(false);
        create.addActionListener(event -> { returnFocus = create; edit(null); });
        ButtonGroup sources = new ButtonGroup();
        sources.add(anySource);
        sources.add(restrictedSource);
        anySource.setOpaque(false);
        restrictedSource.setOpaque(false);
        anySource.addActionListener(event -> updateSourceInputs());
        restrictedSource.addActionListener(event -> updateSourceInputs());
        networks.setLineWrap(true);
        networks.getAccessibleContext().setAccessibleName(text("source.addresses"));
        name.getAccessibleContext().setAccessibleName(text("name"));
        buildEditorFrame();
        showList(text("keys.loading"));
    }

    JComponent editorPage() {
        return editor;
    }

    void pageShown() {
        shown = true;
        load();
    }

    void pageHidden() {
        shown = false;
        visit++;
        clearSecret();
        returnToList();
    }

    /** 放弃编辑器中的临时状态并回到概览；已发出的写入仍按原流程完成。 */
    void returnToList() {
        clearSecret();
        if (mode != Mode.LIST) {
            mode = Mode.LIST;
            editing = null;
            navigator.showOverview();
        }
    }

    void focusPrimaryAction() {
        if (create.isEnabled()) create.requestFocusInWindow();
        else refresh.requestFocusInWindow();
    }

    private static String text(String key, Object... args) {
        return SwingHost.context().resolveText(new DesktopUiText("security", key, key,
                Arrays.stream(args).map(String::valueOf).toList()));
    }

    private void load() {
        if (busy) return;
        clearSecret();
        ready = false;
        run(() -> {
            var response = SwingHost.host().guiGet("program-keys/permissions", 5000);
            check(response);
            var nextCatalog = values(response.body());
            response = SwingHost.host().guiGet("program-keys", 5000);
            check(response);
            return List.of(nextCatalog, values(response.body()));
        }, result -> {
            catalog = result.get(0);
            keys = result.get(1);
            ready = true;
            uncertain = false;
            returnToList();
            showKeys();
        }, false);
    }

    private void showKeys() {
        if (keys.isEmpty()) showList(text("empty"), text("empty.description"));
        else showList(null);
        for (var key : keys) list.add(keyRow(key));
        list.revalidate();
        list.repaint();
        updateControls();
    }

    private void showList(String placeholder) {
        showList(placeholder, null);
    }

    private void showList(String placeholder, String detail) {
        list.removeAll();
        if (placeholder != null) {
            SwingPage.Block empty = new SwingPage.Block();
            empty.setBorder(BorderFactory.createEmptyBorder(SwingPage.ROW_PADDING, 0, SwingPage.ROW_PADDING, 0));
            empty.add(SwingPage.text(SwingPage.Style.SECONDARY, placeholder));
            if (detail != null) {
                empty.add(Box.createVerticalStrut(4));
                empty.add(SwingPage.text(SwingPage.Style.SECONDARY, detail));
            }
            list.add(empty);
        }
        list.revalidate();
        list.repaint();
    }

    private JComponent keyRow(GuiValue key) {
        String id = key.path("id").asText();
        String keyName = key.path("name").asText();
        JButton edit = new JButton(text("edit"));
        JButton revoke = new JButton(text("revoke"));
        edit.getAccessibleContext().setAccessibleName(text("edit") + " · " + keyName);
        revoke.getAccessibleContext().setAccessibleName(text("revoke") + " · " + keyName);
        SwingPage.Row row = SwingPage.row(keyName, null, edit, revoke);
        row.setName("program-keys.row");
        String transport = key.path("transport").asText("HTTPS_REQUIRED");
        JPanel titleLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        titleLine.setOpaque(false);
        titleLine.setBorder(BorderFactory.createEmptyBorder(0, -10, 0, 0));
        JComponent title = (JComponent) row.texts.getComponent(0);
        row.texts.remove(0);
        titleLine.add(title);
        titleLine.add(new SwingPage.Badge(text("transport.state." + transport), switch (transport) {
            case "HTTP_ALLOWED" -> SwingPage::warningColor;
            case "REVIEWED" -> SwingPage::secondaryColor;
            default -> SwingPage::successColor;
        }));
        row.texts.add(SwingPage.capped(titleLine, Integer.MAX_VALUE), 0);

        String sources = key.path("anyIp").asBoolean() ? text("source.any") :
                String.join(", ", values(key.path("networks")).stream().map(GuiValue::asText).toList());
        var dates = java.text.DateFormat.getDateTimeInstance();
        long used = key.path("lastUsedAt").asLong();
        row.texts.add(Box.createVerticalStrut(4));
        row.texts.add(SwingPage.text(SwingPage.Style.SECONDARY,
                text("summary").replace("{count}", key.path("permissionCount").asText()) + " · " + sources));
        row.texts.add(SwingPage.text(SwingPage.Style.SECONDARY,
                text("times").replace("{created}", dates.format(new Date(key.path("createdAt").asLong())))
                        .replace("{used}", used > 0 ? dates.format(new Date(used)) : text("unused"))));

        String next = switch (transport) {
            case "REVIEWED" -> "HTTP_ALLOWED";
            case "HTTP_ALLOWED" -> "HTTPS_REQUIRED";
            default -> "REVIEWED";
        };
        String action = "transport." + next;
        JButton transportButton = SwingPage.link(text(action));
        transportButton.setName("program-keys.transport");
        JButton promptButton = SwingPage.link(text("instructions.title"));
        promptButton.setName("program-keys.prompt");
        promptButton.getAccessibleContext().setAccessibleName(text("instructions.title") + " · " + keyName);
        row.texts.add(Box.createVerticalStrut(2));
        row.texts.add(SwingPage.links(transportButton, promptButton));

        edit.addActionListener(event -> {
            if (!ready || uncertain || busy) return;
            returnFocus = edit;
            run(() -> {
                var result = SwingHost.host().guiGet("program-keys/" + id, 5000);
                check(result);
                if (result.body() == null || !id.equals(result.body().path("id").asText()))
                    throw new RequestFailure(text("request.failed"), false);
                return result.body();
            }, this::edit, false);
        });
        promptButton.addActionListener(event -> {
            if (!ready || uncertain || busy) return;
            returnFocus = promptButton;
            run(() -> {
                var result = SwingHost.host().guiGet("program-keys/" + id, 5000);
                check(result);
                if (result.body() == null || !id.equals(result.body().path("id").asText()))
                    throw new RequestFailure(text("request.failed"), false);
                return result.body();
            }, this::showPrompt, false);
        });
        revoke.addActionListener(event -> {
            if (busy || uncertain || !ready) return;
            if (JOptionPane.showConfirmDialog(this, text("revoke.help").replace("{name}", keyName),
                    text("revoke"), JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            run(() -> SwingHost.host().guiPostJson("program-keys/" + id + "/revoke",
                    Map.of("revision", key.path("revision").asLong()), 5000), response -> { check(response); load(); }, true);
        });
        transportButton.addActionListener(event -> {
            if (busy || uncertain || !ready) return;
            if (JOptionPane.showConfirmDialog(this, text(action + ".help"), text(action),
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            run(() -> SwingHost.host().guiPostJson("program-keys/" + id + "/transport",
                    Map.of("revision", key.path("revision").asLong(), "transport", next), 5000),
                    response -> { check(response); load(); }, true);
        });
        return row;
    }

    private void buildEditorFrame() {
        editor.setOpaque(false);
        editor.add(SwingPage.scroll(editorContent), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(16, 0)) {
            @Override protected void paintComponent(Graphics graphics) {
                super.paintComponent(graphics);
                graphics.setColor(SwingPage.separatorColor());
                graphics.fillRect(0, 0, getWidth(), 1);
            }
        };
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(12, 32, 14, 32));
        footer.add(editorNotice, BorderLayout.CENTER);
        footer.add(editorActions, BorderLayout.EAST);
        editor.add(footer, BorderLayout.SOUTH);
        editor.registerKeyboardAction(event -> {
            if (busy) return;
            if (mode == Mode.REVIEW) showEditForm();
            else if (mode == Mode.EDIT) cancelEditing();
            else if (mode == Mode.PROMPT) { returnToList(); restoreFocus(); }
        }, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
    }

    private void edit(GuiValue key) {
        if (busy || uncertain || !ready) return;
        clearSecret();
        editing = key;
        var policy = key == null ? GuiValue.of(Map.of("permissions", List.of(), "networks", List.of())) : key.path("policy");
        name.setText(policy.path("name").asText());
        (policy.path("anyIp").asBoolean() ? anySource : restrictedSource).setSelected(true);
        networks.setText(String.join("\n", values(policy.path("networks")).stream().map(GuiValue::asText).toList()));
        Set<String> selected = new HashSet<>(values(policy.path("permissions")).stream().map(GuiValue::asText).toList());
        permissions.clear();
        ownerLabels.clear();
        Map<String, List<JCheckBox>> groups = new LinkedHashMap<>();
        for (var permission : catalog) {
            String id = permission.path("id").asText();
            String owner = permission.path("owner").asText();
            ownerLabels.putIfAbsent(owner, permission.path("ownerLabel").asText(owner));
            JCheckBox choice = new JCheckBox();
            choice.putClientProperty("html.disable", true);
            choice.setText(permission.path("label").asText(id));
            choice.setToolTipText(id);
            choice.setOpaque(false);
            choice.setSelected(selected.contains(id));
            permissions.put(id, choice);
            groups.computeIfAbsent(owner, ignored -> new ArrayList<>()).add(choice);
        }
        unavailablePermissions = !permissions.keySet().containsAll(selected);

        SwingPage.Block form = new SwingPage.Block();
        form.add(SwingPage.text(SwingPage.Style.STRONG, text("name")));
        form.add(Box.createVerticalStrut(6));
        form.add(SwingPage.capped(name, 480));
        form.add(Box.createVerticalStrut(26));

        form.add(SwingPage.text(SwingPage.Style.STRONG, text("source")));
        form.add(Box.createVerticalStrut(4));
        form.add(anySource);
        form.add(restrictedSource);
        SwingPage.Block addresses = new SwingPage.Block();
        addresses.setBorder(BorderFactory.createEmptyBorder(4, 26, 0, 0));
        JScrollPane networksScroll = new JScrollPane(networks);
        addresses.add(SwingPage.capped(networksScroll, 454));
        addresses.add(Box.createVerticalStrut(6));
        addresses.add(SwingPage.text(SwingPage.Style.SECONDARY, text("source.help")));
        form.add(addresses);
        form.add(Box.createVerticalStrut(6));
        form.add(SwingPage.text(SwingPage.Style.SECONDARY, text("transport.advice")));
        form.add(Box.createVerticalStrut(26));

        form.add(SwingPage.text(SwingPage.Style.STRONG, text("permissions")));
        form.add(Box.createVerticalStrut(4));
        form.add(SwingPage.text(SwingPage.Style.SECONDARY, text("permissions.help")));
        if (unavailablePermissions) {
            form.add(Box.createVerticalStrut(6));
            form.add(SwingPage.text(SwingPage.Style.BODY, text("permissions.unavailable")).color(SwingPage::warningColor));
        }
        form.add(Box.createVerticalStrut(6));
        SwingPage.RowList groupList = new SwingPage.RowList();
        groups.forEach((owner, choices) -> groupList.add(permissionGroup(ownerLabels.get(owner), choices)));
        form.add(groupList);
        editForm = form;
        showEditForm();
        navigator.showEditor();
        updateSourceInputs();
        name.requestFocusInWindow();
    }

    private JComponent permissionGroup(String ownerLabel, List<JCheckBox> choices) {
        SwingPage.Block group = new SwingPage.Block();
        group.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        JButton disclosure = SwingPage.plain(new JButton(ownerLabel));
        disclosure.setName("program-keys.group");
        disclosure.putClientProperty("html.disable", true);
        disclosure.setFont(disclosure.getFont().deriveFont(Font.BOLD));
        disclosure.setHorizontalAlignment(SwingConstants.LEFT);
        SwingPage.Text count = new SwingPage.Text(SwingPage.Style.SECONDARY, "");
        JButton select = SwingPage.link(text("select.current"));
        select.getAccessibleContext().setAccessibleName(ownerLabel + " · " + text("select.current"));
        JPanel header = new JPanel(new BorderLayout(10, 0));
        header.setOpaque(false);
        header.add(disclosure, BorderLayout.WEST);
        JPanel trailing = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        trailing.setOpaque(false);
        trailing.add(count);
        trailing.add(select);
        header.add(trailing, BorderLayout.EAST);
        group.add(header);
        SwingPage.Block items = new SwingPage.Block();
        items.setBorder(BorderFactory.createEmptyBorder(2, 28, 4, 0));
        for (JCheckBox choice : choices) {
            items.add(choice);
            choice.addItemListener(event -> count.setText(selectedCount(choices)));
        }
        group.add(items);
        boolean[] expanded = {choices.stream().anyMatch(AbstractButton::isSelected)};
        Runnable apply = () -> {
            items.setVisible(expanded[0]);
            disclosure.setIcon(UIManager.getIcon(expanded[0] ? "Tree.expandedIcon" : "Tree.collapsedIcon"));
            disclosure.getAccessibleContext().setAccessibleDescription(selectedCount(choices));
            group.revalidate();
            group.repaint();
        };
        disclosure.addActionListener(event -> { expanded[0] = !expanded[0]; apply.run(); });
        apply.run();
        count.setText(selectedCount(choices));
        select.addActionListener(event -> {
            boolean allow = !choices.stream().allMatch(AbstractButton::isSelected);
            choices.forEach(item -> item.setSelected(allow));
            if (!expanded[0]) { expanded[0] = true; apply.run(); }
        });
        return group;
    }

    private static String selectedCount(List<JCheckBox> choices) {
        return text("permissions.selected", choices.stream().filter(AbstractButton::isSelected).count(), choices.size());
    }

    private void updateSourceInputs() {
        boolean restricted = restrictedSource.isSelected();
        networks.setEnabled(restricted && mode == Mode.EDIT && !busy);
    }

    private void showEditForm() {
        mode = Mode.EDIT;
        editorNotice.setText("");
        JButton cancel = new JButton(text("cancel"));
        cancel.addActionListener(event -> cancelEditing());
        JButton review = SwingPage.primary(new JButton(text("review")));
        review.setName("program-keys.review");
        review.addActionListener(event -> review());
        showEditorView(editing == null ? text("create") : text("edit"), text("keys.description"),
                editForm, cancel, review);
        updateSourceInputs();
    }

    private void cancelEditing() {
        if (busy) return;
        returnToList();
        restoreFocus();
    }

    private void review() {
        if (busy || uncertain) return;
        var addresses = selectedNetworks();
        if (restrictedSource.isSelected() && addresses.isEmpty()) {
            editorNotice.color(SwingPage::errorColor);
            editorNotice.setText(text("source.required"));
            networks.requestFocusInWindow();
            return;
        }
        mode = Mode.REVIEW;
        SwingPage.Form summary = new SwingPage.Form(SwingPage.PAGE_WIDTH)
                .field(text("name"), SwingPage.text(SwingPage.Style.BODY, name.getText().trim()))
                .field(text("source"), SwingPage.text(SwingPage.Style.BODY,
                        anySource.isSelected() ? text("source.any") : String.join("\n", addresses)));
        SwingPage.Block selected = new SwingPage.Block();
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (var permission : catalog) {
            String id = permission.path("id").asText();
            if (permissions.get(id) != null && permissions.get(id).isSelected())
                grouped.computeIfAbsent(permission.path("owner").asText(), ignored -> new ArrayList<>()).add(permissions.get(id).getText());
        }
        if (grouped.isEmpty()) {
            selected.add(SwingPage.text(SwingPage.Style.BODY, text("permissions.none")).color(SwingPage::warningColor));
        }
        grouped.forEach((owner, labels) -> {
            selected.add(SwingPage.text(SwingPage.Style.STRONG, ownerLabels.getOrDefault(owner, owner)));
            SwingPage.Text items = SwingPage.text(SwingPage.Style.BODY, String.join("\n", labels));
            items.setBorder(BorderFactory.createEmptyBorder(2, 14, 10, 0));
            selected.add(items);
        });
        summary.field(text("permissions"), selected);
        JButton back = new JButton(text("review.back"));
        back.addActionListener(event -> { if (!busy) showEditForm(); });
        JButton save = SwingPage.primary(new JButton(text("save")));
        save.setName("program-keys.save");
        save.addActionListener(event -> save());
        showEditorView(text("review.title"), null, summary, back, save);
        save.requestFocusInWindow();
    }

    private List<String> selectedNetworks() {
        return anySource.isSelected() ? List.of() : Arrays.stream(networks.getText().split("[\\n,]"))
                .map(String::trim).filter(value -> !value.isEmpty()).toList();
    }

    private void save() {
        if (busy || uncertain || mode != Mode.REVIEW) return;
        var selected = permissions.entrySet().stream().filter(entry -> entry.getValue().isSelected()).map(Map.Entry::getKey).toList();
        var policy = Map.of("name", name.getText().trim(), "permissions", selected, "anyIp", anySource.isSelected(),
                "networks", selectedNetworks());
        var target = editing;
        run(() -> SwingHost.host().guiPostJson(target == null ? "program-keys" : "program-keys/" + target.path("id").asText() + "/update",
                target == null ? policy : Map.of("revision", target.path("revision").asLong(), "policy", policy), 5000), response -> {
            check(response);
            if (target != null) { load(); return; }
            if (response.body() == null || response.body().path("secret").asText().isEmpty())
                throw new RequestFailure(text("request.check-before-retry"), true);
            showCreated(response.body().path("secret").asText(), selected.contains(REFERENCE_PERMISSION));
        }, true);
    }

    private void showCreated(String value, boolean canReadReference) {
        mode = Mode.CREATED;
        secret = new JTextField(value);
        secret.setEditable(false);
        secret.setName("program-keys.secret");
        secret.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize() + 1));
        secret.getAccessibleContext().setAccessibleName(text("created.secret"));
        JButton copy = new JButton(text("copy"));
        copy.addActionListener(event -> {
            if (secret != null && !busy) copyField(secret, "copied", "copy.manual");
        });
        SwingPage.Block body = new SwingPage.Block();
        body.add(SwingPage.section(text("created.secret"), text("created.help")));
        JPanel field = new JPanel(new BorderLayout(8, 0));
        field.setOpaque(false);
        field.add(secret, BorderLayout.CENTER);
        field.add(copy, BorderLayout.EAST);
        body.add(SwingPage.capped(field, 760));
        addVerification(body);
        body.add(Box.createVerticalStrut(18));
        body.add(SwingPage.section(text("instructions.title"), null));
        addAgentPrompt(body, canReadReference, field.getFont());
        JButton done = SwingPage.primary(new JButton(text("done")));
        done.addActionListener(event -> { clearSecret(); load(); });
        showEditorView(text("created.title"), null, body, done);
        copy.requestFocusInWindow();
    }

    /** 提示词不含秘密，可随时为已有密钥重新生成；能否读取目录取决于该密钥当前的权限。 */
    private void showPrompt(GuiValue key) {
        mode = Mode.PROMPT;
        boolean canReadReference = false;
        for (var permission : values(key.path("policy").path("permissions")))
            canReadReference |= REFERENCE_PERMISSION.equals(permission.asText());
        SwingPage.Block body = new SwingPage.Block();
        body.add(SwingPage.text(SwingPage.Style.SECONDARY, text("instructions.help")));
        body.add(Box.createVerticalStrut(14));
        addAgentPrompt(body, canReadReference, UIManager.getFont("Label.font"));
        JButton done = SwingPage.primary(new JButton(text("done")));
        done.addActionListener(event -> { returnToList(); restoreFocus(); });
        showEditorView(text("instructions.title"), key.path("policy").path("name").asText(), body, done);
        navigator.showEditor();
        done.requestFocusInWindow();
    }

    /** 验证命令只引用环境变量，不把完整密钥拼进可复制的文本。 */
    private void addVerification(SwingPage.Block body) {
        String self;
        try {
            self = SwingHost.host().backendUri(SELF_PATH).toASCIIString();
        } catch (RuntimeException unavailable) {
            return;
        }
        body.add(Box.createVerticalStrut(18));
        body.add(SwingPage.section(text("verify.title"), text("verify.help")));
        var commands = new JTextArea(verificationCommands(self));
        commands.setName("program-keys.verify");
        commands.setEditable(false);
        commands.setLineWrap(true);
        commands.setOpaque(false);
        commands.setFont(new Font(Font.MONOSPACED, Font.PLAIN, UIManager.getFont("Label.font").getSize()));
        commands.getAccessibleContext().setAccessibleName(text("verify.title"));
        body.add(SwingPage.capped(commands, 760));
    }

    static String verificationCommands(String selfUrl) {
        return "curl -H \"Authorization: Bearer $PIXIV_PROGRAM_KEY\" " + selfUrl + "\n"
                + "curl.exe -H \"Authorization: Bearer $env:PIXIV_PROGRAM_KEY\" " + selfUrl;
    }

    private void addAgentPrompt(SwingPage.Block body, boolean canReadReference, Font font) {
        String url;
        try {
            url = SwingHost.host().backendUri("/api/openapi.json").toASCIIString();
        } catch (RuntimeException unavailable) {
            body.add(SwingPage.text(SwingPage.Style.SECONDARY, text("instructions.unavailable")));
            return;
        }
        var instructions = new JTextArea(text(canReadReference ? "created.instructions" : "instructions.restricted", url));
        instructions.setEditable(false);
        instructions.setLineWrap(true);
        instructions.setWrapStyleWord(true);
        instructions.setOpaque(false);
        instructions.setFont(font);
        instructions.getAccessibleContext().setAccessibleName(text("instructions.title"));
        body.add(SwingPage.capped(instructions, 760));
        JButton copyInstructions = new JButton(text("copy.instructions"));
        copyInstructions.setName("program-keys.copy-instructions");
        copyInstructions.addActionListener(event -> copyField(instructions, "instructions.copied", "instructions.manual"));
        body.add(Box.createVerticalStrut(6));
        body.add(SwingPage.links(copyInstructions));
    }

    private void copyField(javax.swing.text.JTextComponent field, String success, String fallback) {
        try {
            SwingHost.host().copyText(field.getText());
            editorNotice.color(SwingPage::successColor);
            editorNotice.setText(text(success));
        } catch (Exception failure) {
            field.requestFocusInWindow();
            field.selectAll();
            editorNotice.color(SwingPage::warningColor);
            editorNotice.setText(text(fallback));
        }
    }

    private void showEditorView(String title, String description, JComponent body, JButton... actions) {
        editorContent.removeAll();
        JButton back = SwingPage.plain(new JButton(text("keys.back")));
        back.setIcon(new BackIcon());
        back.setName("program-keys.back");
        back.addActionListener(event -> {
            if (busy) return;
            clearSecret();
            if (mode == Mode.CREATED) load();
            returnToList();
            restoreFocus();
        });
        editorContent.add(SwingPage.links(back));
        editorContent.add(Box.createVerticalStrut(8));
        editorContent.add(SwingPage.header(title, description));
        editorContent.add(body);
        editorContent.add(Box.createVerticalGlue());
        editorActions.removeAll();
        for (JButton action : actions) editorActions.add(action);
        editorContent.revalidate();
        editorContent.repaint();
        editorActions.revalidate();
        editorActions.repaint();
        SwingUtilities.invokeLater(() -> editorContent.scrollRectToVisible(new Rectangle(0, 0, 1, 1)));
        updateControls();
    }

    private void restoreFocus() {
        Component target = returnFocus != null && returnFocus.isShowing() ? returnFocus : create;
        SwingUtilities.invokeLater(target::requestFocusInWindow);
    }

    private <T> void run(Supplier<T> action, Consumer<T> success, boolean write) {
        if (busy) return;
        busy = true;
        long started = visit;
        SwingPage.Text target = mode == Mode.LIST ? notice : editorNotice;
        target.setText("");
        updateControls();
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() { return action.get(); }
            @Override protected void done() {
                busy = false;
                if (started != visit) {
                    updateControls();
                    if (shown) load();
                    return;
                }
                try { success.accept(get()); }
                catch (Exception failure) {
                    Throwable cause = failure instanceof java.util.concurrent.ExecutionException ? failure.getCause() : failure;
                    uncertain |= write && (!(cause instanceof RequestFailure api) || api.uncertain);
                    String message = cause instanceof RequestFailure ? cause.getMessage() : text("request.failed");
                    if (!ready && keys.isEmpty()) showList(null);
                    if (mode == Mode.REVIEW && !uncertain) showEditForm();
                    SwingPage.Text current = mode == Mode.LIST ? notice : editorNotice;
                    current.color(SwingPage::errorColor);
                    current.setText(message + (uncertain ? " " + text("request.check-before-retry") : ""));
                } finally {
                    updateControls();
                }
            }
        }.execute();
    }

    private void updateControls() {
        refresh.setEnabled(!busy);
        create.setEnabled(ready && !uncertain && !busy);
        boolean editable = mode == Mode.EDIT && !busy;
        name.setEnabled(editable);
        anySource.setEnabled(editable);
        restrictedSource.setEnabled(editable);
        permissions.values().forEach(choice -> choice.setEnabled(editable));
        updateSourceInputs();
        for (Component action : editorActions.getComponents()) action.setEnabled(!busy && !(uncertain && mode != Mode.CREATED));
        for (Component row : list.getComponents())
            setButtonsEnabled(row, ready && !uncertain && !busy);
    }

    private static void setButtonsEnabled(Component component, boolean enabled) {
        if (component instanceof AbstractButton button) button.setEnabled(enabled);
        if (component instanceof Container container)
            for (Component child : container.getComponents()) setButtonsEnabled(child, enabled);
    }

    private static void check(GuiResponse response) {
        if (!response.reachable() || response.bodyLimitExceeded()) throw new RequestFailure(text("request.failed"), true);
        if (!response.is2xx()) throw new RequestFailure(response.body() == null ? text("request.failed")
                : response.body().path("error").asText(text("request.failed")), response.status() >= 500);
    }

    private static final class RequestFailure extends RuntimeException {
        final boolean uncertain;
        RequestFailure(String reason, boolean uncertain) { super(reason); this.uncertain = uncertain; }
    }

    private static List<GuiValue> values(GuiValue value) {
        var result = new ArrayList<GuiValue>();
        if (value == null || !value.isArray()) throw new RequestFailure(text("request.failed"), true);
        value.forEach(result::add);
        return List.copyOf(result);
    }

    private void clearSecret() { if (secret != null) { secret.setText(""); secret = null; } }

    /** 返回箭头，按当前文字颜色绘制。 */
    private static final class BackIcon implements Icon {
        @Override public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(component.getForeground());
                int mid = y + getIconHeight() / 2;
                g.drawLine(x + 2, mid, x + getIconWidth() - 1, mid);
                g.drawLine(x + 2, mid, x + 6, mid - 4);
                g.drawLine(x + 2, mid, x + 6, mid + 4);
            } finally {
                g.dispose();
            }
        }

        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 12; }
    }
}
