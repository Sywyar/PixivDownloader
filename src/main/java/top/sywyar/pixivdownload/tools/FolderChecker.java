package top.sywyar.pixivdownload.tools;

import org.sqlite.SQLiteConfig;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
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
        String pathType() {
            return moved ? "Moved To" : "Original Path";
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

    // ---- 表格列索引 ----
    private static final int COL_ID     = 0;
    private static final int COL_TITLE  = 1;
    private static final int COL_TYPE   = 2;
    private static final int COL_PATH   = 3;
    private static final int COL_STATUS = 4;
    private static final int COL_COPY   = 5;

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception ignored) {}
        SwingUtilities.invokeLater(() -> new FolderChecker().buildUI());
    }

    private void buildUI() {
        frame = new JFrame("Pixiv Folder Checker");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(1000, 600);
        frame.setLayout(new BorderLayout(5, 5));

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
        dbRow.add(new JLabel("Database: "), BorderLayout.WEST);
        dbPathField = new JTextField("pixiv-download/pixiv_download.db");
        dbRow.add(dbPathField, BorderLayout.CENTER);

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton browseBtn = new JButton("Browse...");
        browseBtn.addActionListener(e -> browseDatabase());
        JButton checkBtn = new JButton("Check Folders");
        checkBtn.addActionListener(e -> checkFolders());
        btnRow.add(browseBtn);
        btnRow.add(checkBtn);
        dbRow.add(btnRow, BorderLayout.EAST);

        statusLabel = new JLabel("Please select a database file and click \"Check Folders\".");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 0));

        panel.add(dbRow, BorderLayout.CENTER);
        panel.add(statusLabel, BorderLayout.SOUTH);
        return panel;
    }

    // ---- 中部：结果表格 ----
    private JScrollPane buildTablePanel() {
        String[] columns = {"Artwork ID", "Title", "Path Type", "Path", "Status", "Copy ID"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int col) { return col == COL_COPY; }
            @Override public Class<?> getColumnClass(int col) { return String.class; }
        };

        table = new JTable(tableModel);
        table.setRowHeight(24);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // 列宽
        table.getColumnModel().getColumn(COL_ID).setPreferredWidth(90);
        table.getColumnModel().getColumn(COL_TITLE).setPreferredWidth(200);
        table.getColumnModel().getColumn(COL_TYPE).setPreferredWidth(100);
        table.getColumnModel().getColumn(COL_PATH).setPreferredWidth(380);
        table.getColumnModel().getColumn(COL_STATUS).setPreferredWidth(90);
        table.getColumnModel().getColumn(COL_COPY).setPreferredWidth(80);

        // Status 列：NOT FOUND 染红
        table.getColumnModel().getColumn(COL_STATUS).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (!sel) c.setForeground("NOT FOUND".equals(value) ? Color.RED.darker() : Color.GREEN.darker());
                return c;
            }
        });

        // Path 列：tooltip 显示完整路径
        table.getColumnModel().getColumn(COL_PATH).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, value, sel, focus, row, col);
                if (c instanceof JLabel lbl && value != null) lbl.setToolTipText(value.toString());
                return c;
            }
        });

        // Copy ID 列：按钮渲染与编辑
        table.getColumnModel().getColumn(COL_COPY).setCellRenderer(new ButtonRenderer());
        table.getColumnModel().getColumn(COL_COPY).setCellEditor(new CopyButtonEditor());

        // 行选中：同步到底部面板
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                selectedRow = table.getSelectedRow();
                if (selectedRow >= 0 && selectedRow < brokenArtworks.size()) {
                    ArtworkInfo info = brokenArtworks.get(selectedRow);
                    selectedIdLabel.setText("Selected ID: " + info.artworkId());
                    //newPathField.setText(info.checkPath() != null ? info.checkPath() : "");
                } else {
                    selectedIdLabel.setText("Selected ID: (none)");
                }
            }
        });

        return new JScrollPane(table);
    }

    // ---- 底部：选中信息 + 修复路径 ----
    private JPanel buildBottomPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        panel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.LIGHT_GRAY));

        selectedIdLabel = new JLabel("Selected ID: (none)");
        panel.add(selectedIdLabel);

        JButton copyIdBtn = new JButton("Copy ID");
        copyIdBtn.setToolTipText("Copy the selected artwork's ID to clipboard");
        copyIdBtn.addActionListener(e -> copySelectedId());
        panel.add(copyIdBtn);

        panel.add(Box.createHorizontalStrut(12));
        panel.add(new JLabel("New Path:"));

        newPathField = new JTextField(28);
        newPathField.setToolTipText("Enter the correct folder path for the selected artwork");
        panel.add(newPathField);

        JButton browsePathBtn = new JButton("Browse...");
        browsePathBtn.addActionListener(e -> browsePath());
        panel.add(browsePathBtn);

        JButton updateBtn = new JButton("Update DB");
        updateBtn.setToolTipText("Update the selected artwork's path in the database");
        updateBtn.addActionListener(e -> updatePath());
        panel.add(updateBtn);

        return panel;
    }

    // ---- 操作：选择数据库文件 ----
    private void browseDatabase() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select SQLite Database");
        chooser.setFileFilter(new FileNameExtensionFilter("SQLite Database (*.db)", "db"));
        chooser.setCurrentDirectory(new File(dbPathField.getText()).getParentFile());
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            dbPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    // ---- 操作：选择目录 ----
    private void browsePath() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Artwork Folder");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        String current = newPathField.getText().trim();
        if (!current.isEmpty()) chooser.setCurrentDirectory(new File(current));
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            newPathField.setText(stripTrailingSlash(chooser.getSelectedFile().getAbsolutePath()));
        }
    }

    // ---- 操作：检查所有文件夹 ----
    private void checkFolders() {
        String dbPath = dbPathField.getText().trim();
        if (dbPath.isEmpty()) {
            showError("Please enter or select a database file path.");
            return;
        }

        try {
            if (conn != null && !conn.isClosed()) conn.close();

            SQLiteConfig cfg = new SQLiteConfig();
            cfg.setBusyTimeout(5000);
            cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
            cfg.setReadOnly(false);
            conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath, cfg.toProperties());

            List<ArtworkInfo> all = loadAllArtworks();
            brokenArtworks.clear();
            tableModel.setRowCount(0);
            selectedRow = -1;
            selectedIdLabel.setText("Selected ID: (none)");
            newPathField.setText("");

            for (ArtworkInfo info : all) {
                String path = info.checkPath();
                boolean accessible = path != null && new File(path).isDirectory();
                if (!accessible) {
                    brokenArtworks.add(info);
                    tableModel.addRow(new Object[]{
                            String.valueOf(info.artworkId()),
                            info.title(),
                            info.pathType(),
                            path != null ? path : "(null)",
                            "NOT FOUND",
                            "Copy ID"
                    });
                }
            }

            int broken = brokenArtworks.size();
            int total  = all.size();
            if (broken == 0) {
                statusLabel.setText("All " + total + " artworks have accessible folders.");
                statusLabel.setForeground(new Color(0, 130, 0));
            } else {
                statusLabel.setText(broken + " artwork(s) with inaccessible folders  (out of " + total + " total)");
                statusLabel.setForeground(Color.RED.darker());
            }

        } catch (SQLException ex) {
            showError("Database error: " + ex.getMessage());
        }
    }

    private List<ArtworkInfo> loadAllArtworks() throws SQLException {
        List<ArtworkInfo> list = new ArrayList<>();
        String sql = "SELECT artwork_id, title, folder, moved, move_folder FROM artworks ORDER BY time DESC";
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
            showError("Please select a row first.");
            return;
        }
        String id = String.valueOf(brokenArtworks.get(selectedRow).artworkId());
        copyToClipboard(id);
        JOptionPane.showMessageDialog(frame, "Copied: " + id, "Copied", JOptionPane.INFORMATION_MESSAGE);
    }

    // ---- 操作：更新数据库中的路径 ----
    private void updatePath() {
        if (selectedRow < 0 || selectedRow >= brokenArtworks.size()) {
            showError("Please select a row first.");
            return;
        }
        if (conn == null) {
            showError("No database connection. Please run Check Folders first.");
            return;
        }
        String newPath = stripTrailingSlash(newPathField.getText().trim());
        if (newPath.isEmpty()) {
            showError("Please enter a new folder path.");
            return;
        }
        if (!new File(newPath).isDirectory()) {
            int confirm = JOptionPane.showConfirmDialog(frame,
                    "The path does not exist as a directory:\n" + newPath + "\n\nUpdate anyway?",
                    "Path Not Found", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (confirm != JOptionPane.YES_OPTION) return;
        }

        ArtworkInfo info = brokenArtworks.get(selectedRow);
        String column = info.moved() ? "move_folder" : "folder";
        try {
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE artworks SET " + column + " = ? WHERE artwork_id = ?")) {
                ps.setString(1, newPath);
                ps.setLong(2, info.artworkId());
                ps.executeUpdate();
            }
            JOptionPane.showMessageDialog(frame,
                    "Updated artwork " + info.artworkId() + "\n" + column + " → " + newPath,
                    "Success", JOptionPane.INFORMATION_MESSAGE);
            checkFolders(); // 刷新列表
        } catch (SQLException ex) {
            showError("Update failed: " + ex.getMessage());
        }
    }

    // ---- 工具方法 ----
    private static String stripTrailingSlash(String path) {
        return path == null ? null : path.replaceAll("[/\\\\]+$", "");
    }

    private void copyToClipboard(String text) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
    }

    private void showError(String msg) {
        JOptionPane.showMessageDialog(frame, msg, "Error", JOptionPane.ERROR_MESSAGE);
    }

    // ---- 表格按钮渲染器 ----
    static class ButtonRenderer extends JButton implements TableCellRenderer {
        public ButtonRenderer() {
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
            button = new JButton("Copy ID");
            button.setMargin(new Insets(1, 4, 1, 4));
            button.addActionListener(e -> {
                if (editingRow >= 0 && editingRow < brokenArtworks.size()) {
                    String id = String.valueOf(brokenArtworks.get(editingRow).artworkId());
                    copyToClipboard(id);
                    // 更新选中行
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
            return "Copy ID";
        }
    }
}
