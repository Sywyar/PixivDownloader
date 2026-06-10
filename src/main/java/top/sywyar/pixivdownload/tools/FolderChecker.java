package top.sywyar.pixivdownload.tools;

import org.sqlite.SQLiteConfig;
import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.i18n.GuiMessages;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 独立可视化工具：检查数据库中记录的作品文件夹是否可以正常访问。
 * <ul>
 *   <li>未移动的作品：检查 folder（Original Path）</li>
 *   <li>已移动的作品：检查 move_folder（Moved To）</li>
 * </ul>
 * 界面显示异常作品数量，支持复制作品ID、输入并更新新的目录路径。
 */
public class FolderChecker {

    // ---- 数据模型 ----
    record ArtworkInfo(long artworkId, String title, String folder, boolean moved, String moveFolder) {
        String checkPath() {
            return moved && moveFolder != null ? moveFolder : folder;
        }

        String pathTypeKey() {
            return moved
                    ? "gui.folder-checker.path-type.moved"
                    : "gui.folder-checker.path-type.original";
        }
    }

    // ---- UI 组件 ----
    private JFrame frame;
    private JTextField dbPathField;
    private JLabel statusLabel;
    private JTable table;
    private DefaultTableModel tableModel;
    private JTextField newPathField;
    private JLabel selectedIdLabel;
    private Connection conn;
    private final List<ArtworkInfo> brokenArtworks = new ArrayList<>();
    private int selectedRow = -1;
    private final int closeOperation;
    private final Runnable onClose;

    // ---- 表格列索引 ----
    private static final int COL_ID = 0;
    private static final int COL_TITLE = 1;
    private static final int COL_TYPE = 2;
    private static final int COL_PATH = 3;
    private static final int COL_STATUS = 4;
    private static final int COL_COPY = 5;

    public FolderChecker() {
        this(JFrame.EXIT_ON_CLOSE, null);
    }

    public FolderChecker(int closeOperation, Runnable onClose) {
        this.closeOperation = closeOperation;
        this.onClose = onClose;
    }

    public static void main(String[] args) {
        Utf8ConsoleStreams.install();
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {
        }
        SwingUtilities.invokeLater(() -> new FolderChecker().showWindow());
    }

    public void showWindow() {
        if (frame != null) {
            frame.setVisible(true);
            frame.toFront();
            frame.requestFocus();
            return;
        }

        frame = new JFrame(message("gui.tools.card.folder-checker.title"));
        frame.setDefaultCloseOperation(closeOperation);
        frame.setSize(1000, 600);
        frame.setLayout(new BorderLayout(5, 5));
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                closeConnection();
                if (onClose != null) {
                    SwingUtilities.invokeLater(onClose);
                }
            }
        });

        frame.add(buildTopPanel(), BorderLayout.NORTH);
        frame.add(buildTablePanel(), BorderLayout.CENTER);
        frame.add(buildBottomPanel(), BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    // ---- 顶部：数据库路径 + 检查按钮 ----
    private JPanel buildTopPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

        JPanel dbRow = new JPanel(new BorderLayout(5, 0));
        dbRow.add(new JLabel(message("gui.tools.form.database-path") + message("gui.punctuation.colon")), BorderLayout.WEST);
        dbPathField = new JTextField(RuntimeFiles.dataDirectory().resolve(RuntimeFiles.PIXIV_DOWNLOAD_DB).toString());
        dbRow.add(dbPathField, BorderLayout.CENTER);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton browseBtn = new JButton(message("gui.button.browse"));
        browseBtn.addActionListener(e -> browseDatabase());
        JButton checkBtn = new JButton(message("gui.folder-checker.button.check-folders"));
        checkBtn.addActionListener(e -> checkFolders());
        btnRow.add(browseBtn);
        btnRow.add(checkBtn);
        dbRow.add(btnRow, BorderLayout.EAST);

        statusLabel = new JLabel(message("gui.folder-checker.status.initial"));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 0));

        panel.add(dbRow, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    // ---- 中部：结果表格 ----
    private JScrollPane buildTablePanel() {
        String[] columns = {
                message("gui.folder-checker.column.artwork-id"),
                message("gui.folder-checker.column.title"),
                message("gui.folder-checker.column.path-type"),
                message("gui.folder-checker.column.path"),
                message("gui.folder-checker.column.status"),
                message("gui.folder-checker.column.copy-id")
        };
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) {
                return col == COL_COPY;
            }

            @Override
            public Class<?> getColumnClass(int col) {
                return String.class;
            }
        };

        table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        table.getColumnModel().getColumn(COL_ID).setPreferredWidth(90);
        table.getColumnModel().getColumn(COL_TITLE).setPreferredWidth(200);
        table.getColumnModel().getColumn(COL_TYPE).setPreferredWidth(100);
        table.getColumnModel().getColumn(COL_PATH).setPreferredWidth(380);
        table.getColumnModel().getColumn(COL_STATUS).setPreferredWidth(90);
        table.getColumnModel().getColumn(COL_COPY).setPreferredWidth(80);

        table.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                                                           boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (!sel) {
                    c.setForeground(message("gui.folder-checker.status.not-found").equals(value)
                            ? Color.RED.darker()
                            : new Color(0, 130, 0));
                }
                return c;
            }
        });

        table.getColumnModel().getColumn(COL_PATH).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                                                           boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (c instanceof JLabel lbl && value != null) {
                    lbl.setToolTipText(value.toString());
                }
                return c;
            }
        });

        table.getColumnModel().getColumn(COL_COPY).setCellRenderer(new ButtonRenderer());
        table.getColumnModel().getColumn(COL_COPY).setCellEditor(new CopyButtonEditor());

        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectedRow = table.getSelectedRow();
                if (selectedRow >= 0 && selectedRow < brokenArtworks.size()) {
                    ArtworkInfo info = brokenArtworks.get(selectedRow);
                    selectedIdLabel.setText(selectedIdText(String.valueOf(info.artworkId())));
                } else {
                    selectedIdLabel.setText(selectedIdText(message("gui.value.none")));
                }
            }
        });

        return new JScrollPane(table);
    }

    // ---- 底部：选中信息 + 修复路径 ----
    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        selectedIdLabel = new JLabel(selectedIdText(message("gui.value.none")));
        panel.add(selectedIdLabel);

        JButton copyIdBtn = new JButton(message("gui.folder-checker.button.copy-id"));
        copyIdBtn.setToolTipText(message("gui.folder-checker.tooltip.copy-selected-id"));
        copyIdBtn.addActionListener(e -> copySelectedId());
        panel.add(copyIdBtn);

        panel.add(Box.createHorizontalStrut(12));
        panel.add(new JLabel(message("gui.folder-checker.label.new-path") + message("gui.punctuation.colon")));

        newPathField = new JTextField(28);
        newPathField.setToolTipText(message("gui.folder-checker.tooltip.new-path"));
        panel.add(newPathField);

        JButton browsePathBtn = new JButton(message("gui.button.browse"));
        browsePathBtn.addActionListener(e -> browsePath());
        panel.add(browsePathBtn);

        JButton updateBtn = new JButton(message("gui.folder-checker.button.update-db"));
        updateBtn.setToolTipText(message("gui.folder-checker.tooltip.update-db"));
        updateBtn.addActionListener(e -> updatePath());
        panel.add(updateBtn);

        return panel;
    }

    // ---- 操作：选择数据库文件 ----
    private void browseDatabase() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(message("gui.tools.dialog.select-sqlite-database.title"));
        chooser.setFileFilter(new FileNameExtensionFilter(
                message("gui.folder-checker.file-filter.sqlite"),
                "db"
        ));
        File currentFile = new File(dbPathField.getText());
        File currentDir = currentFile.getParentFile();
        if (currentDir != null && currentDir.exists()) {
            chooser.setCurrentDirectory(currentDir);
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            dbPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ---- 操作：选择目录 ----
    private void browsePath() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(message("gui.folder-checker.dialog.select-artwork-folder.title"));
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String current = newPathField.getText().trim();
        if (!current.isEmpty()) {
            chooser.setCurrentDirectory(new File(current));
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            newPathField.setText(stripTrailingSlash(chooser.getSelectedFile().getAbsolutePath()));
        }
    }

    // ---- 操作：检查所有文件夹 ----
    private void checkFolders() {
        String dbPath = dbPathField.getText().trim();
        if (dbPath.isEmpty()) {
            showError(message("gui.folder-checker.error.database-path.required"));
            return;
        }

        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
            }

            SQLiteConfig cfg = new SQLiteConfig();
            cfg.setBusyTimeout(5000);
            cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
            cfg.setReadOnly(false);
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, cfg.toProperties());

            List<ArtworkInfo> all = loadAllArtworks();
            brokenArtworks.clear();
            tableModel.setRowCount(0);
            selectedRow = -1;
            selectedIdLabel.setText(selectedIdText(message("gui.value.none")));
            newPathField.setText("");

            for (ArtworkInfo info : all) {
                String path = info.checkPath();
                boolean accessible = path != null && new File(path).isDirectory();
                if (!accessible) {
                    brokenArtworks.add(info);
                    tableModel.addRow(new Object[]{
                            String.valueOf(info.artworkId()),
                            info.title(),
                            message(info.pathTypeKey()),
                            path != null ? path : message("gui.folder-checker.value.null-path"),
                            message("gui.folder-checker.status.not-found"),
                            message("gui.folder-checker.button.copy-id")
                    });
                }
            }

            int broken = brokenArtworks.size();
            int total = all.size();
            if (broken == 0) {
                statusLabel.setText(message("gui.folder-checker.status.all-accessible", total));
                statusLabel.setForeground(new Color(0, 130, 0));
            } else {
                statusLabel.setText(message("gui.folder-checker.status.inaccessible-count", broken, total));
                statusLabel.setForeground(Color.RED.darker());
            }

        } catch (SQLException ex) {
            showError(message("gui.folder-checker.error.database", ex.getMessage()));
        }
    }

    private List<ArtworkInfo> loadAllArtworks() throws SQLException {
        // 软删除的作品磁盘文件已删，目录必然不可达，不应作为「文件夹异常」误报；
        // 旧库可能还没有 deleted 列（由后端启动迁移补齐），缺列时回退为全量查询。
        String sql = "SELECT artwork_id, title, folder, moved, move_folder FROM artworks"
                + " WHERE deleted = 0 ORDER BY time DESC";
        try {
            return loadArtworks(sql);
        } catch (SQLException e) {
            String message = String.valueOf(e.getMessage());
            if (!message.contains("no such column")) {
                throw e;
            }
        }
        return loadArtworks("SELECT artwork_id, title, folder, moved, move_folder FROM artworks ORDER BY time DESC");
    }

    private List<ArtworkInfo> loadArtworks(String sql) throws SQLException {
        List<ArtworkInfo> list = new ArrayList<>();
        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new ArtworkInfo(
                        rs.getLong("artwork_id"),
                        rs.getString("title"),
                        rs.getString("folder"),
                        rs.getInt("moved") == 1,
                        rs.getString("move_folder")
                ));
            }
        }
        return list;
    }

    // ---- 操作：复制选中行的 ID ----
    private void copySelectedId() {
        if (selectedRow < 0 || selectedRow >= brokenArtworks.size()) {
            showError(message("gui.folder-checker.error.row-required"));
            return;
        }
        String id = String.valueOf(brokenArtworks.get(selectedRow).artworkId());
        copyToClipboard(id);
        JOptionPane.showMessageDialog(
                frame,
                message("gui.folder-checker.dialog.copied.message", id),
                message("gui.folder-checker.dialog.copied.title"),
                JOptionPane.INFORMATION_MESSAGE
        );
    }

    // ---- 操作：更新数据库中的路径 ----
    private void updatePath() {
        if (selectedRow < 0 || selectedRow >= brokenArtworks.size()) {
            showError(message("gui.folder-checker.error.row-required"));
            return;
        }
        if (conn == null) {
            showError(message("gui.folder-checker.error.no-connection"));
            return;
        }
        String newPath = stripTrailingSlash(newPathField.getText().trim());
        if (newPath.isEmpty()) {
            showError(message("gui.folder-checker.error.new-path.required"));
            return;
        }
        if (!new File(newPath).isDirectory()) {
            int confirm = JOptionPane.showConfirmDialog(
                    frame,
                    message("gui.folder-checker.dialog.path-not-found.message", newPath),
                    message("gui.folder-checker.dialog.path-not-found.title"),
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.WARNING_MESSAGE
            );
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }

        ArtworkInfo info = brokenArtworks.get(selectedRow);
        String column = info.moved() ? "move_folder" : "folder";
        String columnLabel = info.moved()
                ? message("gui.folder-checker.column-name.move-folder")
                : message("gui.folder-checker.column-name.folder");
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE artworks SET " + column + " = ? WHERE artwork_id = ?")) {
                ps.setString(1, newPath);
                ps.setLong(2, info.artworkId());
                ps.executeUpdate();
            }
            JOptionPane.showMessageDialog(
                    frame,
                    message("gui.folder-checker.dialog.update-success.message", info.artworkId(), columnLabel, newPath),
                    message("gui.folder-checker.dialog.update-success.title"),
                    JOptionPane.INFORMATION_MESSAGE
            );
            checkFolders();
        } catch (SQLException ex) {
            showError(message("gui.folder-checker.error.update-failed", ex.getMessage()));
        }
    }

    // ---- 工具方法 ----
    private static String stripTrailingSlash(String path) {
        return path == null ? null : path.replaceAll("[/\\\\]+$", "");
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void closeConnection() {
        if (conn == null) {
            return;
        }
        try {
            if (!conn.isClosed()) {
                conn.close();
            }
        } catch (SQLException ignored) {
        } finally {
            conn = null;
        }
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(frame, msg, message("gui.dialog.error.title"), JOptionPane.ERROR_MESSAGE);
    }

    private static String selectedIdText(String value) {
        return message("gui.folder-checker.label.selected-id", value);
    }

    private static String message(String code, Object... args) {
        return GuiMessages.get(code, args);
    }

    // ---- 表格按钮渲染器 ----
    static class ButtonRenderer extends JButton implements TableCellRenderer {
        ButtonRenderer() {
            setOpaque(true);
            setMargin(new Insets(1, 4, 1, 4));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                                                       boolean isSelected, boolean hasFocus, int row, int col) {
            setText(value != null ? value.toString() : "");
            setBackground(isSelected ? table.getSelectionBackground() : UIManager.getColor("Button.background"));
            return this;
        }
    }

    // ---- 表格按钮编辑器（点击触发复制） ----
    class CopyButtonEditor extends DefaultCellEditor {
        private final JButton button;
        private int editingRow = -1;

        CopyButtonEditor() {
            super(new JCheckBox());
            setClickCountToStart(1);
            button = new JButton(message("gui.folder-checker.button.copy-id"));
            button.setMargin(new Insets(1, 4, 1, 4));
            button.addActionListener(e -> {
                if (editingRow >= 0 && editingRow < brokenArtworks.size()) {
                    String id = String.valueOf(brokenArtworks.get(editingRow).artworkId());
                    copyToClipboard(id);
                    table.setRowSelectionInterval(editingRow, editingRow);
                }
                fireEditingStopped();
            });
        }

        @Override
        public Component getTableCellEditorComponent(JTable t, Object value,
                                                     boolean isSelected, int row, int col) {
            editingRow = row;
            return button;
        }

        @Override
        public Object getCellEditorValue() {
            return message("gui.folder-checker.button.copy-id");
        }
    }
}
