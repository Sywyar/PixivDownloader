package top.sywyar.pixivdownload.imageclassifier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import javax.swing.*;
import javax.swing.Timer;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;
import java.util.Properties;

@Slf4j
public class ImageClassifier extends JFrame {

    // =========================================================================
    // 常量
    // =========================================================================

    private static final int    GROUP_SIZE         = 10;
    private static final String CONFIG_FILE        = "image_classifier.properties";
    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp"};

    // UI 配色
    private static final Color C_BG          = new Color(242, 243, 247);
    private static final Color C_PANEL       = Color.WHITE;
    private static final Color C_BORDER      = new Color(218, 220, 226);
    private static final Color C_PRIMARY     = new Color(59, 120, 231);
    private static final Color C_DANGER      = new Color(211, 77, 42);
    private static final Color C_NEUTRAL     = new Color(100, 108, 122);
    private static final Color C_TEXT        = new Color(30, 34, 40);
    private static final Color C_TEXT_MUTED  = new Color(110, 118, 132);
    private static final Color C_THUMB_BG    = new Color(232, 233, 238);
    private static final Color C_ROW_ALT     = new Color(249, 250, 252);

    // =========================================================================
    // 状态
    // =========================================================================

    private File       parentFolder;
    private List<File> subFolders;
    private int        currentFolderIndex = 0;
    private List<File> currentImages;
    private int        currentGroupIndex  = 0;
    private boolean    serverRunning      = false;
    private Long       currentArtworkId   = null;

    // =========================================================================
    // 配置
    // =========================================================================

    private Properties   config;
    private List<String> targetFolders;
    private List<String> folderRemarks;

    // =========================================================================
    // UI 组件
    // =========================================================================

    private JTextField folderPathField;
    private JTextField targetFolderField;
    private JLabel     remarkLabel;
    private JLabel     statusLabel;
    private JLabel     serverStatusLabel;
    private JLabel[]   thumbnailLabels;
    private JPanel     thumbnailsPanel;
    private JPanel     categoriesPanel;
    private JButton    openFolderButton;
    private JButton    browseFolderButton;
    private JButton    settingsButton;
    private JButton    classifyButton;
    private JButton    skipFolderButton;
    private JButton    prevFolderButton;
    private JButton    prevGroupButton;
    private JButton    nextGroupButton;
    private JButton    refreshButton;

    // =========================================================================
    // 工具
    // =========================================================================

    private final ThumbnailManager thumbnailManager = new ThumbnailManager();
    private final RestTemplate     restTemplate     = new RestTemplate();

    // =========================================================================
    // 构造 & 入口
    // =========================================================================

    public ImageClassifier() {
        loadConfig();
        initUI();
        checkServerStatus();
        autoOpenDefaultFolder();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ImageClassifier classifier = new ImageClassifier();
            classifier.setVisible(true);
        });
    }

    // =========================================================================
    // 配置管理
    // =========================================================================

    private void loadConfig() {
        config = new Properties();

        String[] defaultTargetFolders = {
                "S:\\z\\Ovo\\小特",
                "S:\\z\\Ovo\\气象学家",
                "S:\\z\\Ovo\\小女孩",
                "S:\\z\\Ovo\\0",
                "S:\\z\\Ovo\\0\\idv",
                "S:\\Temp"
        };

        String[] defaultFolderRemarks = {
                "类别0 - 小特",
                "类别1 - 气象学家",
                "类别2 - 小女孩",
                "类别3 - 默认",
                "类别4 - idv",
                "类别5 - 删除",
        };

        try {
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                config.load(new FileInputStream(configFile));
            }
        } catch (IOException e) {
            log.warn("无法加载配置文件，使用默认配置: {}", e.getMessage());
        }

        targetFolders = new ArrayList<>();
        folderRemarks = new ArrayList<>();

        for (int i = 0; i < 20; i++) {
            String folder = config.getProperty("target.folder." + i);
            String remark = config.getProperty("folder.remark." + i);
            if (folder != null && remark != null) {
                targetFolders.add(stripTrailingSlash(folder));
                folderRemarks.add(remark);
            }
        }

        if (targetFolders.isEmpty()) {
            for (int i = 0; i < defaultTargetFolders.length; i++) {
                targetFolders.add(defaultTargetFolders[i]);
                folderRemarks.add(defaultFolderRemarks[i]);
            }
        }
    }

    private void saveConfig() {
        try {
            for (int i = 0; i < 20; i++) {
                config.remove("target.folder." + i);
                config.remove("folder.remark." + i);
            }
            for (int i = 0; i < targetFolders.size(); i++) {
                config.setProperty("target.folder." + i, targetFolders.get(i));
                config.setProperty("folder.remark." + i, folderRemarks.get(i));
            }
            config.store(new FileOutputStream(CONFIG_FILE), "Image Classifier Configuration");
            log.info("配置已保存");
        } catch (IOException e) {
            log.error("保存配置失败: {}", e.getMessage());
            JOptionPane.showMessageDialog(this, "保存配置失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =========================================================================
    // UI 初始化
    // =========================================================================

    private void initUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        setTitle("图片分类工具");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1340, 800);
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(0, 0));
        mainPanel.setBackground(C_BG);
        mainPanel.add(buildTopPanel(),    BorderLayout.NORTH);
        mainPanel.add(buildCenterPanel(), BorderLayout.CENTER);
        mainPanel.add(buildRightPanel(),  BorderLayout.EAST);
        mainPanel.add(buildStatusPanel(), BorderLayout.SOUTH);

        add(mainPanel);
        setupEventListeners();
    }

    /** 顶部工具栏：路径输入 + 操作按钮 + 服务器状态 */
    private JPanel buildTopPanel() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(C_PANEL);
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));

        // 左侧
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);

        JLabel pathLabel = new JLabel("文件夹路径");
        pathLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        pathLabel.setForeground(C_TEXT_MUTED);
        left.add(pathLabel);

        folderPathField = new JTextField(32);
        folderPathField.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        folderPathField.setForeground(C_TEXT);
        folderPathField.setText(config.getProperty("default.folder", ""));
        left.add(folderPathField);

        openFolderButton    = styledButton("打 开", C_PRIMARY);
        browseFolderButton  = styledButton("浏 览", C_NEUTRAL);
        settingsButton      = styledButton("设 置", C_NEUTRAL);
        left.add(openFolderButton);
        left.add(browseFolderButton);
        left.add(settingsButton);

        // 右侧：服务器状态
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        serverStatusLabel = new JLabel("● 检测中...");
        serverStatusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        serverStatusLabel.setForeground(C_TEXT_MUTED);
        right.add(serverStatusLabel);

        bar.add(left,  BorderLayout.WEST);
        bar.add(right, BorderLayout.EAST);
        return bar;
    }

    /** 中央区域：2×5 缩略图 + 分组翻页导航 */
    private JPanel buildCenterPanel() {
        JPanel wrap = new JPanel(new BorderLayout(0, 0));
        wrap.setBackground(C_BG);
        wrap.setBorder(BorderFactory.createEmptyBorder(12, 12, 0, 8));

        // ── 缩略图网格 ──
        thumbnailsPanel = new JPanel(new GridLayout(2, 5, 8, 8));
        thumbnailsPanel.setBackground(C_BG);

        thumbnailLabels = new JLabel[GROUP_SIZE];
        for (int i = 0; i < GROUP_SIZE; i++) {
            thumbnailLabels[i] = new JLabel("", JLabel.CENTER);
            thumbnailLabels[i].setOpaque(true);
            thumbnailLabels[i].setBackground(C_THUMB_BG);
            thumbnailLabels[i].setForeground(C_TEXT_MUTED);
            thumbnailLabels[i].setFont(new Font("微软雅黑", Font.PLAIN, 12));
            thumbnailLabels[i].setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(C_BORDER, 1),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)));
            thumbnailLabels[i].setPreferredSize(new Dimension(160, 160));
            thumbnailsPanel.add(thumbnailLabels[i]);

            final int slotIndex = i;
            thumbnailLabels[i].addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int imgIndex = currentGroupIndex * GROUP_SIZE + slotIndex;
                    if (currentImages != null && imgIndex < currentImages.size()
                            && thumbnailLabels[slotIndex].getIcon() != null) {
                        openImageViewer(imgIndex);
                    }
                }
            });
        }

        // ── 翻页导航 ──
        JPanel navPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 10));
        navPanel.setBackground(C_BG);
        prevGroupButton = styledButton("◀  上一组", C_NEUTRAL);
        nextGroupButton = styledButton("下一组  ▶", C_NEUTRAL);
        prevGroupButton.setPreferredSize(new Dimension(130, 34));
        nextGroupButton.setPreferredSize(new Dimension(130, 34));
        navPanel.add(prevGroupButton);
        navPanel.add(nextGroupButton);

        wrap.add(thumbnailsPanel, BorderLayout.CENTER);
        wrap.add(navPanel,        BorderLayout.SOUTH);
        return wrap;
    }

    /** 右侧面板：分类说明（滚动列表）+ 操作区 */
    private JPanel buildRightPanel() {
        JPanel right = new JPanel(new BorderLayout(0, 8));
        right.setBackground(C_BG);
        right.setBorder(BorderFactory.createEmptyBorder(12, 8, 0, 12));
        right.setPreferredSize(new Dimension(280, 0));

        // ── 分类说明（可滚动） ──
        categoriesPanel = new JPanel();
        categoriesPanel.setLayout(new BoxLayout(categoriesPanel, BoxLayout.Y_AXIS));
        categoriesPanel.setBackground(C_PANEL);
        updateCategoriesPanel();

        JScrollPane catScroll = new JScrollPane(categoriesPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        catScroll.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(C_BORDER),
                        "分类说明",
                        javax.swing.border.TitledBorder.LEFT,
                        javax.swing.border.TitledBorder.TOP,
                        new Font("微软雅黑", Font.BOLD, 13)),
                BorderFactory.createEmptyBorder(0, 0, 0, 0)));

        // ── 操作区 ──
        JPanel actionPanel = new JPanel(new BorderLayout(0, 8));
        actionPanel.setBackground(C_BG);
        actionPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(
                        BorderFactory.createLineBorder(C_BORDER),
                        "文件夹分类",
                        javax.swing.border.TitledBorder.LEFT,
                        javax.swing.border.TitledBorder.TOP,
                        new Font("微软雅黑", Font.BOLD, 13)),
                BorderFactory.createEmptyBorder(4, 6, 8, 6)));

        // 编号输入行
        JPanel inputRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        inputRow.setOpaque(false);
        JLabel numLabel = new JLabel("编号:");
        numLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        numLabel.setForeground(C_TEXT_MUTED);
        targetFolderField = new JTextField(4);
        targetFolderField.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        remarkLabel = new JLabel("请输入 0–" + (targetFolders.size() - 1));
        remarkLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        remarkLabel.setForeground(C_TEXT_MUTED);
        inputRow.add(numLabel);
        inputRow.add(targetFolderField);
        inputRow.add(remarkLabel);

        // 四个操作按钮（2×2 网格）
        classifyButton  = styledButton("分类整个文件夹", C_PRIMARY);
        skipFolderButton = styledButton("跳过此文件夹",  C_DANGER);
        prevFolderButton = styledButton("← 上一文件夹", C_NEUTRAL);
        refreshButton    = styledButton("刷新缩略图",   C_NEUTRAL);
        skipFolderButton.setVisible(Boolean.parseBoolean(config.getProperty("show.skip.button", "true")));

        JPanel btnGrid = new JPanel(new GridLayout(2, 2, 6, 6));
        btnGrid.setOpaque(false);
        btnGrid.add(classifyButton);
        btnGrid.add(skipFolderButton);
        btnGrid.add(prevFolderButton);
        btnGrid.add(refreshButton);

        actionPanel.add(inputRow, BorderLayout.NORTH);
        actionPanel.add(btnGrid,  BorderLayout.CENTER);

        right.add(catScroll,    BorderLayout.CENTER);
        right.add(actionPanel,  BorderLayout.SOUTH);
        return right;
    }

    /** 底部状态栏 */
    private JPanel buildStatusPanel() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        bar.setBackground(C_PANEL);
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));
        statusLabel = new JLabel("请先选择包含数字文件夹的父目录");
        statusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        statusLabel.setForeground(C_TEXT_MUTED);
        bar.add(statusLabel);
        return bar;
    }

    /** 刷新分类说明列表 */
    private void updateCategoriesPanel() {
        categoriesPanel.removeAll();
        for (int i = 0; i < targetFolders.size(); i++) {
            boolean alt = (i % 2 == 1);
            JPanel row = new JPanel(new BorderLayout());
            row.setBackground(alt ? C_ROW_ALT : C_PANEL);
            row.setOpaque(true);
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, C_BORDER),
                    BorderFactory.createEmptyBorder(6, 10, 6, 8)));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 52));

            JLabel index = new JLabel(String.valueOf(i));
            index.setFont(new Font("微软雅黑", Font.BOLD, 14));
            index.setForeground(C_PRIMARY);
            index.setPreferredSize(new Dimension(22, 0));
            index.setHorizontalAlignment(SwingConstants.CENTER);

            JPanel textCol = new JPanel();
            textCol.setLayout(new BoxLayout(textCol, BoxLayout.Y_AXIS));
            textCol.setOpaque(false);
            JLabel name = new JLabel(folderRemarks.get(i));
            name.setFont(new Font("微软雅黑", Font.PLAIN, 13));
            name.setForeground(C_TEXT);
            JLabel path = new JLabel(targetFolders.get(i));
            path.setFont(new Font("微软雅黑", Font.PLAIN, 11));
            path.setForeground(C_TEXT_MUTED);
            textCol.add(name);
            textCol.add(path);

            row.add(index,   BorderLayout.WEST);
            row.add(textCol, BorderLayout.CENTER);

            final int folderIndex = i;
            MouseAdapter clickToFill = new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    targetFolderField.setText(String.valueOf(folderIndex));
                    targetFolderField.requestFocus();
                }
                @Override
                public void mouseEntered(MouseEvent e) {
                    row.setBackground(C_PRIMARY.brighter());
                }
                @Override
                public void mouseExited(MouseEvent e) {
                    row.setBackground(alt ? C_ROW_ALT : C_PANEL);
                }
            };
            row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            row.addMouseListener(clickToFill);
            index.addMouseListener(clickToFill);
            name.addMouseListener(clickToFill);
            path.addMouseListener(clickToFill);

            categoriesPanel.add(row);
        }
        categoriesPanel.revalidate();
        categoriesPanel.repaint();
    }

    /** 统一按钮样式：彩色背景、白色文字、固定高度 */
    private JButton styledButton(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("微软雅黑", Font.BOLD, 13));
        btn.setBackground(bg);
        btn.setForeground(Color.WHITE);
        btn.setFocusPainted(false);
        btn.setOpaque(true);
        btn.setBorderPainted(false);
        btn.setPreferredSize(new Dimension(btn.getPreferredSize().width, 34));
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return btn;
    }

    private void setupEventListeners() {
        openFolderButton.addActionListener(e -> openParentFolderFromField());
        browseFolderButton.addActionListener(e -> browseParentFolder());
        settingsButton.addActionListener(e -> showSettingsDialog());

        prevGroupButton.addActionListener(e -> showPreviousGroup());
        nextGroupButton.addActionListener(e -> showNextGroup());

        classifyButton.addActionListener(e -> classifyFolder());
        skipFolderButton.addActionListener(e -> skipCurrentFolder());
        prevFolderButton.addActionListener(e -> moveToPrevFolder());
        refreshButton.addActionListener(e -> refreshCurrentFolder());

        folderPathField.addActionListener(e -> openParentFolderFromField());

        statusLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        statusLabel.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (currentArtworkId == null) return;
                StringSelection selection = new StringSelection(String.valueOf(currentArtworkId));
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
                String prev = statusLabel.getText();
                statusLabel.setText("已复制 artworkId: " + currentArtworkId);
                Timer timer = new Timer(1500, ev -> statusLabel.setText(prev));
                timer.setRepeats(false);
                timer.start();
            }
        });

        targetFolderField.addActionListener(e -> updateRemarkLabel());
        targetFolderField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e)  { updateRemarkLabel(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e)  { updateRemarkLabel(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { updateRemarkLabel(); }
        });
    }

    // =========================================================================
    // 对话框
    // =========================================================================

    private void showSettingsDialog() {
        JDialog settingsDialog = new JDialog(this, "设置", true);
        settingsDialog.setSize(600, 500);
        settingsDialog.setLocationRelativeTo(this);
        settingsDialog.setLayout(new BorderLayout(10, 10));

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 用 JTabbedPane 的 clientProperty 在构建方法与保存 lambda 之间传递组件引用
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.addTab("基本设置",   buildBasicSettingsPanel(settingsDialog, tabbedPane));
        tabbedPane.addTab("目标文件夹", buildTargetFoldersPanel(settingsDialog, tabbedPane));
        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        JTextField defaultFolderField = (JTextField)  tabbedPane.getClientProperty("defaultFolderField");
        JCheckBox  showSkipCheckBox   = (JCheckBox)   tabbedPane.getClientProperty("showSkipCheckBox");
        JTextField serverUrlField     = (JTextField)  tabbedPane.getClientProperty("serverUrlField");
        javax.swing.table.DefaultTableModel tableModel =
                (javax.swing.table.DefaultTableModel) tabbedPane.getClientProperty("tableModel");

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveButton   = new JButton("保存");
        JButton cancelButton = new JButton("取消");
        buttonPanel.add(saveButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        saveButton.addActionListener(e -> {
            config.setProperty("default.folder", stripTrailingSlash(defaultFolderField.getText().trim()));
            config.setProperty("show.skip.button", String.valueOf(showSkipCheckBox.isSelected()));
            config.setProperty("server.url", serverUrlField.getText().trim());

            targetFolders.clear();
            folderRemarks.clear();
            for (int i = 0; i < tableModel.getRowCount(); i++) {
                targetFolders.add(stripTrailingSlash(tableModel.getValueAt(i, 1).toString()));
                folderRemarks.add(tableModel.getValueAt(i, 2).toString());
            }

            saveConfig();
            skipFolderButton.setVisible(showSkipCheckBox.isSelected());
            updateCategoriesPanel();
            remarkLabel.setText("请输入0-" + (targetFolders.size() - 1) + "之间的数字");
            settingsDialog.dispose();
            JOptionPane.showMessageDialog(this, "设置已保存", "提示", JOptionPane.INFORMATION_MESSAGE);
        });

        cancelButton.addActionListener(e -> settingsDialog.dispose());

        settingsDialog.add(mainPanel);
        settingsDialog.setVisible(true);
    }

    /**
     * 构建「基本设置」选项卡，将组件引用存入 tabbedPane clientProperty 供保存 lambda 读取。
     */
    private JPanel buildBasicSettingsPanel(JDialog parent, JTabbedPane tabbedPane) {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill   = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // 默认文件夹
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        panel.add(new JLabel("默认打开文件夹:"), gbc);

        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField defaultFolderField = new JTextField(config.getProperty("default.folder", ""));
        panel.add(defaultFolderField, gbc);

        gbc.gridx = 2; gbc.weightx = 0.0;
        JButton browseBtn = new JButton("浏览");
        panel.add(browseBtn, gbc);
        browseBtn.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("选择默认文件夹");
            if (fc.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
                defaultFolderField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        // 显示跳过按钮
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        panel.add(new JLabel("显示跳过按钮:"), gbc);

        gbc.gridx = 1;
        JCheckBox showSkipCheckBox = new JCheckBox();
        showSkipCheckBox.setSelected(Boolean.parseBoolean(config.getProperty("show.skip.button", "false")));
        panel.add(showSkipCheckBox, gbc);

        // 服务器网址
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0;
        panel.add(new JLabel("服务器网址:"), gbc);

        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        JTextField serverUrlField = new JTextField(config.getProperty("server.url", "http://localhost:6999"));
        panel.add(serverUrlField, gbc);

        // 存入 tabbedPane，供 showSettingsDialog 的保存 lambda 读取
        tabbedPane.putClientProperty("defaultFolderField", defaultFolderField);
        tabbedPane.putClientProperty("showSkipCheckBox",   showSkipCheckBox);
        tabbedPane.putClientProperty("serverUrlField",     serverUrlField);

        return panel;
    }

    /**
     * 构建「目标文件夹」选项卡，tableModel 存入 JTabbedPane clientProperty 供外部读取。
     */
    private JPanel buildTargetFoldersPanel(JDialog parent, JTabbedPane tabbedPane) {
        JPanel panel = new JPanel(new BorderLayout(5, 5));

        String[] columnNames = {"编号", "路径", "备注"};
        Object[][] data = new Object[targetFolders.size()][3];
        for (int i = 0; i < targetFolders.size(); i++) {
            data[i][0] = i;
            data[i][1] = targetFolders.get(i);
            data[i][2] = folderRemarks.get(i);
        }

        javax.swing.table.DefaultTableModel tableModel = new javax.swing.table.DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) { return column != 0; }
        };
        tabbedPane.putClientProperty("tableModel", tableModel);

        JTable table = new JTable(tableModel);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setMaxWidth(50);
        table.getColumnModel().getColumn(1).setPreferredWidth(200);
        table.getColumnModel().getColumn(2).setPreferredWidth(150);
        panel.add(new JScrollPane(table), BorderLayout.CENTER);

        // 操作按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addBtn      = new JButton("新增");
        JButton editBtn     = new JButton("编辑");
        JButton deleteBtn   = new JButton("删除");
        JButton moveUpBtn   = new JButton("上移");
        JButton moveDownBtn = new JButton("下移");
        btnPanel.add(addBtn);
        btnPanel.add(editBtn);
        btnPanel.add(deleteBtn);
        btnPanel.add(moveUpBtn);
        btnPanel.add(moveDownBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        addBtn.addActionListener(e -> showFolderEditDialog(parent, -1, tableModel));

        editBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row >= 0) showFolderEditDialog(parent, row, tableModel);
            else JOptionPane.showMessageDialog(parent, "请先选择一个文件夹进行编辑", "提示", JOptionPane.INFORMATION_MESSAGE);
        });

        deleteBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0) { JOptionPane.showMessageDialog(parent, "请先选择一个文件夹进行删除", "提示", JOptionPane.INFORMATION_MESSAGE); return; }
            if (JOptionPane.showConfirmDialog(parent, "确定要删除选中的文件夹吗？", "确认删除", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                tableModel.removeRow(row);
                for (int i = 0; i < tableModel.getRowCount(); i++) tableModel.setValueAt(i, i, 0);
            }
        });

        moveUpBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row <= 0) return;
            swapTableRows(tableModel, row, row - 1);
            table.setRowSelectionInterval(row - 1, row - 1);
        });

        moveDownBtn.addActionListener(e -> {
            int row = table.getSelectedRow();
            if (row < 0 || row >= tableModel.getRowCount() - 1) return;
            swapTableRows(tableModel, row, row + 1);
            table.setRowSelectionInterval(row + 1, row + 1);
        });

        return panel;
    }

    private void swapTableRows(javax.swing.table.DefaultTableModel model, int a, int b) {
        Object[] rowA = new Object[3];
        for (int i = 0; i < 3; i++) rowA[i] = model.getValueAt(a, i);
        for (int i = 0; i < 3; i++) model.setValueAt(model.getValueAt(b, i), a, i);
        for (int i = 0; i < 3; i++) model.setValueAt(rowA[i], b, i);
        model.setValueAt(a, a, 0);
        model.setValueAt(b, b, 0);
    }

    private void showFolderEditDialog(JDialog parent, int rowIndex, javax.swing.table.DefaultTableModel tableModel) {
        JDialog editDialog = new JDialog(parent, rowIndex < 0 ? "新增文件夹" : "编辑文件夹", true);
        editDialog.setSize(400, 200);
        editDialog.setLocationRelativeTo(parent);
        editDialog.setLayout(new BorderLayout(10, 10));

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        JPanel formPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill   = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        formPanel.add(new JLabel("文件夹路径:"), gbc);
        gbc.gridx = 1; gbc.weightx = 1.0;
        JTextField pathField = new JTextField(20);
        if (rowIndex >= 0) pathField.setText(tableModel.getValueAt(rowIndex, 1).toString());
        formPanel.add(pathField, gbc);
        gbc.gridx = 2; gbc.weightx = 0.0;
        JButton browsePathButton = new JButton("浏览");
        formPanel.add(browsePathButton, gbc);

        gbc.gridx = 0; gbc.gridy = 1;
        formPanel.add(new JLabel("文件夹备注:"), gbc);
        gbc.gridx = 1; gbc.gridwidth = 2; gbc.weightx = 1.0;
        JTextField remarkField = new JTextField(20);
        if (rowIndex >= 0) remarkField.setText(tableModel.getValueAt(rowIndex, 2).toString());
        formPanel.add(remarkField, gbc);

        mainPanel.add(formPanel, BorderLayout.CENTER);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton     = new JButton("确定");
        JButton cancelButton = new JButton("取消");
        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);
        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        editDialog.add(mainPanel);

        browsePathButton.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle("选择目标文件夹");
            if (fc.showOpenDialog(editDialog) == JFileChooser.APPROVE_OPTION) {
                pathField.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });

        okButton.addActionListener(e -> {
            if (pathField.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(editDialog, "请输入文件夹路径", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (remarkField.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(editDialog, "请输入文件夹备注", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (rowIndex < 0) {
                tableModel.addRow(new Object[]{tableModel.getRowCount(), pathField.getText().trim(), remarkField.getText().trim()});
            } else {
                tableModel.setValueAt(pathField.getText().trim(),   rowIndex, 1);
                tableModel.setValueAt(remarkField.getText().trim(), rowIndex, 2);
            }
            editDialog.dispose();
        });

        cancelButton.addActionListener(e -> editDialog.dispose());

        editDialog.setVisible(true);
    }

    // =========================================================================
    // 文件夹加载 & 导航
    // =========================================================================

    private void autoOpenDefaultFolder() {
        String defaultFolder = config.getProperty("default.folder", "").trim();
        if (!defaultFolder.isEmpty()) {
            File folder = new File(defaultFolder);
            if (folder.exists() && folder.isDirectory()) {
                folderPathField.setText(defaultFolder);
                SwingUtilities.invokeLater(this::openParentFolderFromField);
            } else {
                log.warn("默认文件夹不存在或不是有效目录: {}", defaultFolder);
            }
        }
    }

    private void openParentFolderFromField() {
        String folderPath = stripTrailingSlash(folderPathField.getText().trim());
        if (folderPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入文件夹路径", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        File folder = new File(folderPath);
        if (!folder.exists() || !folder.isDirectory()) {
            JOptionPane.showMessageDialog(this, "文件夹不存在或不是有效目录", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }
        parentFolder = folder;
        loadSubFolders();
        if (!subFolders.isEmpty()) {
            loadImagesFromCurrentFolder();
            updateThumbnails();
            updateStatus();
        } else {
            JOptionPane.showMessageDialog(this, "选择的文件夹中没有子文件夹", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void browseParentFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("选择包含数字文件夹的父文件夹");
        if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            parentFolder = fc.getSelectedFile();
            folderPathField.setText(parentFolder.getAbsolutePath());
            loadSubFolders();
            if (!subFolders.isEmpty()) {
                loadImagesFromCurrentFolder();
                updateThumbnails();
                updateStatus();
            }
        }
    }

    private void loadSubFolders() {
        File[] folders = parentFolder.listFiles(File::isDirectory);
        if (folders != null) {
            Arrays.sort(folders, (f1, f2) -> {
                try {
                    return Integer.compare(Integer.parseInt(f1.getName()), Integer.parseInt(f2.getName()));
                } catch (NumberFormatException e) {
                    return f1.getName().compareTo(f2.getName());
                }
            });
            subFolders = Arrays.asList(folders);
            currentFolderIndex = 0;
            currentGroupIndex  = 0;
        } else {
            subFolders = List.of();
            JOptionPane.showMessageDialog(this, "选择的文件夹中没有子文件夹", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadImagesFromCurrentFolder() {
        if (currentFolderIndex >= subFolders.size()) return;
        File currentFolder = subFolders.get(currentFolderIndex);
        File[] imageFiles  = currentFolder.listFiles((dir, name) -> {
            for (String ext : IMAGE_EXTENSIONS) {
                if (name.toLowerCase().endsWith(ext)) return true;
            }
            return false;
        });
        if (imageFiles != null && imageFiles.length > 0) {
            Arrays.sort(imageFiles, Comparator.comparing(File::getName));
            currentImages     = Arrays.asList(imageFiles);
            currentGroupIndex = 0;
        } else {
            currentImages = List.of();
        }
    }

    private void skipCurrentFolder() {
        moveToNextFolder();
    }

    private void moveToPrevFolder() {
        if (subFolders == null || currentFolderIndex <= 0) return;
        int temp = currentFolderIndex;
        do {
            temp--;
        }while (temp >=0 && !subFolders.get(temp).exists());
        if (temp == -1) {
            JOptionPane.showMessageDialog(this,"没有上一个文件夹了","错误",JOptionPane.ERROR_MESSAGE);
            return;
        }
        currentFolderIndex = temp;
        loadImagesFromCurrentFolder();
        updateThumbnails();
        updateStatus();
    }

    private void moveToNextFolder() {
        File currentFolder  = subFolders.get(currentFolderIndex);
        File[] remaining    = currentFolder.listFiles();
        if (remaining == null || remaining.length == 0) currentFolder.delete();

        int temp = currentFolderIndex;
        do {
            temp++;
        }while (temp < subFolders.size() && !subFolders.get(temp).exists());

        currentFolderIndex = temp;
        if (currentFolderIndex < subFolders.size()) {
            loadImagesFromCurrentFolder();
            updateThumbnails();
            updateStatus();
        } else {
            JOptionPane.showMessageDialog(this, "所有文件夹已处理完毕", "完成", JOptionPane.INFORMATION_MESSAGE);
            for (JLabel label : thumbnailLabels) { label.setIcon(null); label.setText("已全部完成"); }
            statusLabel.setText("所有文件夹已处理完毕");
            statusLabel.setForeground(new Color(34, 139, 87));
        }
    }

    private void refreshCurrentFolder() {
        if (subFolders == null || currentFolderIndex >= subFolders.size()) return;
        thumbnailManager.clearCache();
        loadImagesFromCurrentFolder();
        updateThumbnails();
        updateStatus();
    }

    // =========================================================================
    // 缩略图 & 导航按钮
    // =========================================================================

    private void updateThumbnails() {
        if (currentImages == null || currentImages.isEmpty()) {
            for (JLabel label : thumbnailLabels) {
                label.setIcon(null);
                label.setText("无图片");
                label.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
            }
            updateNavigationButtons();
            return;
        }

        int startIndex = currentGroupIndex * GROUP_SIZE;
        int endIndex   = Math.min(startIndex + GROUP_SIZE, currentImages.size());
        int thumbW = 150, thumbH = 150;

        for (int i = 0; i < GROUP_SIZE; i++) {
            int    imgIndex = startIndex + i;
            JLabel label    = thumbnailLabels[i];
            if (imgIndex < endIndex) {
                File   imageFile = currentImages.get(imgIndex);
                String fname     = imageFile.getName().toLowerCase();
                String badge     = fname.endsWith(".webp") ? "动图" : null;
                label.setText("加载中…");
                label.setIcon(null);
                label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                thumbnailManager.loadThumbnail(imageFile, label, thumbW, thumbH, badge);
            } else {
                label.setIcon(null);
                label.setText("无图片");
                label.setCursor(Cursor.getDefaultCursor());
            }
            label.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
        }

        int nextStart = (currentGroupIndex + 1) * GROUP_SIZE;
        if (nextStart < currentImages.size()) {
            thumbnailManager.prefetch(currentImages.subList(nextStart, Math.min(nextStart + GROUP_SIZE, currentImages.size())), thumbW, thumbH);
        }

        updateNavigationButtons();
    }

    private void updateNavigationButtons() {
        if (currentImages == null || currentImages.isEmpty()) {
            prevGroupButton.setEnabled(false);
            nextGroupButton.setEnabled(false);
            return;
        }
        int totalGroups = (int) Math.ceil((double) currentImages.size() / GROUP_SIZE);
        prevGroupButton.setEnabled(currentGroupIndex > 0);
        nextGroupButton.setEnabled(currentGroupIndex < totalGroups - 1);
    }

    private void showPreviousGroup() {
        if (currentGroupIndex > 0) {
            currentGroupIndex--;
            updateThumbnails();
            updateStatus();
        }
    }

    private void showNextGroup() {
        int totalGroups = currentImages.isEmpty() ? 0 : (int) Math.ceil((double) currentImages.size() / GROUP_SIZE);
        if (currentGroupIndex < totalGroups - 1) {
            currentGroupIndex++;
            updateThumbnails();
            updateStatus();
        }
    }

    // =========================================================================
    // 状态栏更新
    // =========================================================================

    private void updateStatus() {
        if (currentFolderIndex >= subFolders.size()) return;
        File currentFolder = subFolders.get(currentFolderIndex);
        int  totalGroups   = currentImages.isEmpty() ? 0 : (int) Math.ceil((double) currentImages.size() / GROUP_SIZE);

        statusLabel.setText(String.format("当前文件夹: %s   %d 张图片   第 %d / %d 组",
                currentFolder.getName(), currentImages.size(), currentGroupIndex + 1, totalGroups));
        statusLabel.setForeground(C_TEXT);

        setTitle(String.format("图片分类工具 - 共%s个文件夹 - %d 张图片",
                subFolders.size() - currentFolderIndex, currentImages.size()));

        Long resolvedId = serverRunning ? resolveArtworkId(currentFolder) : null;
        currentArtworkId = resolvedId;
        if (resolvedId != null) {
            final long artworkId = resolvedId;
            final int remainingFolders = subFolders.size() - currentFolderIndex;
            final int imageCount = currentImages.size();
            new Thread(() -> {
                try {
                    String serverUrl = config.getProperty("server.url", "http://localhost:6999");
                    ResponseEntity<Map> resp = restTemplate.getForEntity(serverUrl + "/api/downloaded/" + artworkId, Map.class);
                    if (resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null) {
                        Object  r18Val   = resp.getBody().get("R18");
                        Boolean isR18    = r18Val instanceof Boolean ? (Boolean) r18Val : null;
                        Object  titleVal = resp.getBody().get("title");
                        String  title    = titleVal instanceof String s ? s : null;
                        SwingUtilities.invokeLater(() -> {
                            if (title != null && !title.isEmpty()) {
                                setTitle(String.format("图片分类工具 - 共%d个文件夹 - %d 张图片  |  %s",
                                        remainingFolders, imageCount, title));
                            }
                            if (Boolean.TRUE.equals(isR18)) {
                                statusLabel.setText(statusLabel.getText() + "   [R18]");
                                statusLabel.setForeground(C_DANGER);
                            } else if (Boolean.FALSE.equals(isR18)) {
                                statusLabel.setText(statusLabel.getText() + "   [SFW]");
                                statusLabel.setForeground(new Color(34, 139, 87));
                            } else {
                                statusLabel.setText(statusLabel.getText() + "   [未知]");
                                statusLabel.setForeground(C_TEXT_MUTED);
                            }
                        });
                    }
                } catch (Exception ignored) {}
            }).start();
        }
    }

    private void updateRemarkLabel() {
        String input = targetFolderField.getText().trim();
        if (input.isEmpty()) {
            remarkLabel.setText("请输入0-" + (targetFolders.size() - 1) + "之间的数字");
            return;
        }
        try {
            int index = Integer.parseInt(input);
            remarkLabel.setText(index >= 0 && index < targetFolders.size()
                    ? folderRemarks.get(index)
                    : "无效编号，请输入0-" + (targetFolders.size() - 1));
        } catch (NumberFormatException ex) {
            remarkLabel.setText("请输入有效数字");
        }
    }

    // =========================================================================
    // 分类 & 移动
    // =========================================================================

    private void classifyFolder() {
        if (currentImages == null || currentImages.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前文件夹没有图片可分类", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        String targetFolderNum = targetFolderField.getText().trim();
        if (targetFolderNum.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请输入目标文件夹编号", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int index;
        try {
            index = Integer.parseInt(targetFolderNum);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "请输入有效的文件夹编号", "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        if (index < 0 || index >= targetFolders.size()) {
            JOptionPane.showMessageDialog(this, "请输入0-" + (targetFolders.size() - 1) + "之间的数字",
                    "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File currentSubFolder = subFolders.get(currentFolderIndex);
        Long artworkId        = resolveArtworkId(currentSubFolder);
        if (artworkId == null) {
            JOptionPane.showMessageDialog(this,
                    "无法从文件夹名「" + currentSubFolder.getName() + "」或数据库记录中找到对应的作品 ID。\n请使用「跳过此文件夹」处理。",
                    "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File targetFolder = new File(targetFolders.get(index));
        if (!targetFolder.exists() && !targetFolder.mkdirs()) {
            JOptionPane.showMessageDialog(this, "无法创建目标文件夹: " + targetFolder.getAbsolutePath(),
                    "错误", JOptionPane.ERROR_MESSAGE);
            return;
        }

        File destDir;
        File numberedFolder = null;
        try {
            if (currentImages.size() == 1) {
                destDir = targetFolder;
            } else {
                numberedFolder = new File(targetFolder, String.valueOf(findNextFolderNumber(targetFolder)));
                if (!numberedFolder.mkdirs()) throw new IOException("无法创建子文件夹: " + numberedFolder.getAbsolutePath());
                destDir = numberedFolder;
            }

            final String moveReportPath    = destDir.toPath().toString();
            final File   finalNumberedFolder = numberedFolder;
            List<File[]> copyPairs         = new ArrayList<>();

            // ==========================================
            // Phase 1：复制所有文件
            // ==========================================
            try {
                for (File image : currentImages) {
                    File dest = new File(destDir, image.getName());
                    Files.copy(image.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    copyPairs.add(new File[]{image, dest});
                }
            } catch (IOException copyErr) {
                // 如果是多图创建了独立文件夹，直接删除整个新建的文件夹
                if (finalNumberedFolder != null && finalNumberedFolder.exists()) {
                    deleteDir(finalNumberedFolder);
                } else {
                    // 如果是单图没创建文件夹，则只删除已拷贝过去的文件
                    for (File[] pair : copyPairs) {
                        try { Files.deleteIfExists(pair[1].toPath()); } catch (IOException re) { log.error("回滚删除失败: {}", re.getMessage()); }
                    }
                }
                throw copyErr; // 抛出异常交给外层 catch 弹窗并终止当前操作
            }

            // ==========================================
            // Phase 2：删除源文件 (发生错误不回滚目标文件)
            // ==========================================
            while (currentSubFolder.exists()) {
                try {
                    // 尝试删除所有源图片
                    for (File image : currentImages) {
                        if (image.exists()) {
                            Files.delete(image.toPath());
                        }
                    }

                    // 尝试删除源文件夹
                    File[] remaining = currentSubFolder.listFiles();
                    if (remaining == null || remaining.length == 0) {
                        Files.delete(currentSubFolder.toPath());
                    } else {
                        throw new IOException("源文件夹中仍存在其他非图片文件 (" + remaining.length + "个)");
                    }
                } catch (Exception delErr) {
                    // 如果在弹窗前，用户已经光速手动删除了文件夹，则直接跳出循环
                    if (!currentSubFolder.exists()) {
                        break;
                    }

                    int option = JOptionPane.showConfirmDialog(this,
                            "删除源文件或源文件夹时出错: " + delErr.getMessage() + "\n\n" +
                                    "目标文件已成功复制。\n" +
                                    "您可以前往路径手动删除源文件夹后，点击「确定」继续；\n" +
                                    "或者点击「取消」跳过清理直接进行下一个。\n\n" +
                                    "源路径: " + currentSubFolder.getAbsolutePath(),
                            "删除源文件失败",
                            JOptionPane.OK_CANCEL_OPTION,
                            JOptionPane.WARNING_MESSAGE);

                    if (option != JOptionPane.OK_OPTION) {
                        // 用户放弃挣扎，打断循环，保留源文件垃圾
                        break;
                    }
                }
            }

            // ==========================================
            // Phase 3：记录及跳转
            // ==========================================
            if (serverRunning) {
                try { sendMoveArtWorkInfo(artworkId, moveReportPath); }
                catch (Exception e) { log.error("记录失败", e); }
            }

            moveToNextFolder();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "移动文件时出错（已回滚目标文件）: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            loadImagesFromCurrentFolder();
            updateThumbnails();
        }
    }

    // =========================================================================
    // 服务器通信
    // =========================================================================

    private void checkServerStatus() {
        new Thread(() -> {
            try {
                String serverUrl = config.getProperty("server.url", "http://localhost:6999");
                ResponseEntity<String> response = restTemplate.getForEntity(serverUrl + "/api/download/status", String.class);
                boolean ok = response.getStatusCode() == HttpStatus.OK;
                serverRunning = ok;
                SwingUtilities.invokeLater(() -> {
                    if (ok) {
                        serverStatusLabel.setText("● 服务器正常");
                        serverStatusLabel.setForeground(new Color(34, 139, 87));
                    } else {
                        serverStatusLabel.setText("● 服务器异常 (" + response.getStatusCode() + ")");
                        serverStatusLabel.setForeground(C_DANGER);
                    }
                });
            } catch (Exception e) {
                serverRunning = false;
                SwingUtilities.invokeLater(() -> {
                    serverStatusLabel.setText("● 连接失败");
                    serverStatusLabel.setForeground(C_DANGER);
                });
                log.error("检查服务器状态失败: {}", e.getMessage());
            }
        }).start();
    }

    private void sendMoveArtWorkInfo(Long artWork, String movePath) {
        String serverUrl = config.getProperty("server.url", "http://localhost:6999");
        String url       = serverUrl + "/api/downloaded/move/" + artWork;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> body = new HashMap<>();
        body.put("movePath", movePath);
        body.put("moveTime", System.currentTimeMillis() / 1000);

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
            log.info("Response: {}", response.getBody());
        } catch (Exception e) {
            log.error("发送请求失败: {}", e.getMessage());
        }
    }

    // =========================================================================
    // 图片查看器
    // =========================================================================

    private void openImageViewer(int initialIndex) {
        if (currentImages == null || currentImages.isEmpty()) return;

        JDialog viewer = new JDialog(this, "图片查看", false);
        viewer.setSize(1100, 860);
        viewer.setLocationRelativeTo(this);
        viewer.setLayout(new BorderLayout());

        final int[] idx = {initialIndex};

        // ── 图片显示区 ──
        JLabel imageLabel = new JLabel("加载中...", JLabel.CENTER);
        imageLabel.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        imageLabel.setForeground(new Color(180, 180, 180));
        imageLabel.setBackground(new Color(18, 18, 18));
        imageLabel.setOpaque(true);

        JScrollPane scrollPane = new JScrollPane(imageLabel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        scrollPane.getViewport().setBackground(new Color(18, 18, 18));
        scrollPane.setBorder(null);

        // ── 底部导航栏 ──
        JPanel navPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 14, 8));
        navPanel.setBackground(C_PANEL);
        navPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER));

        JButton prevBtn = styledButton("◀ 上一张", C_NEUTRAL);
        JButton nextBtn = styledButton("下一张 ▶", C_NEUTRAL);
        prevBtn.setPreferredSize(new Dimension(110, 34));
        nextBtn.setPreferredSize(new Dimension(110, 34));

        JLabel pageLabel = new JLabel();
        pageLabel.setFont(new Font("微软雅黑", Font.BOLD, 14));
        pageLabel.setForeground(C_TEXT);
        pageLabel.setPreferredSize(new Dimension(130, 34));
        pageLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JLabel fileNameLabel = new JLabel();
        fileNameLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        fileNameLabel.setForeground(C_TEXT_MUTED);

        navPanel.add(prevBtn);
        navPanel.add(pageLabel);
        navPanel.add(nextBtn);
        navPanel.add(Box.createHorizontalStrut(16));
        navPanel.add(fileNameLabel);

        viewer.add(scrollPane, BorderLayout.CENTER);
        viewer.add(navPanel,   BorderLayout.SOUTH);

        // ── 加载图片（必须在 EDT 调用）──
        Runnable loadImage = () -> {
            if (currentImages == null || idx[0] >= currentImages.size()) return;
            File imgFile = currentImages.get(idx[0]);

            viewer.setTitle("图片查看  [" + (idx[0] + 1) + " / " + currentImages.size() + "]  " + imgFile.getName());
            pageLabel.setText("第 " + (idx[0] + 1) + " / " + currentImages.size() + " 张");
            fileNameLabel.setText(imgFile.getName());
            prevBtn.setEnabled(idx[0] > 0);
            nextBtn.setEnabled(idx[0] < currentImages.size() - 1);
            imageLabel.setText("加载中...");
            imageLabel.setIcon(null);

            // WebP 动图：Java ImageIO 不支持 webp，改用伴随的 _thumb.jpg（第一帧原始分辨率）
            File resolvedFile = imgFile;
            if (imgFile.getName().toLowerCase().endsWith(".webp")) {
                String base = imgFile.getName().substring(0, imgFile.getName().lastIndexOf('.'));
                File thumbJpg = new File(imgFile.getParent(), base + "_thumb.jpg");
                if (thumbJpg.exists()) resolvedFile = thumbJpg;
            }
            final File loadFile = resolvedFile;
            final Dimension vpSize = scrollPane.getViewport().getSize();
            final int vpW = vpSize.width  > 100 ? vpSize.width  : 1060;
            final int vpH = vpSize.height > 100 ? vpSize.height : 760;

            new Thread(() -> {
                try {
                    java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(loadFile);
                    if (src == null) {
                        // webp 等 ImageIO 不支持的格式，回退到 ImageIcon
                        ImageIcon raw = new ImageIcon(loadFile.getAbsolutePath());
                        src = toBufferedImage(raw.getImage());
                    }
                    if (src == null) throw new IOException("无法解码图片");

                    int imgW = src.getWidth();
                    int imgH = src.getHeight();
                    double scale = Math.min((double) vpW / imgW, (double) vpH / imgH);

                    java.awt.image.BufferedImage display;
                    if (scale < 1.0) {
                        int newW = (int) (imgW * scale);
                        int newH = (int) (imgH * scale);
                        display = ThumbnailManager.getThumbnail(loadFile, newW, newH);
                    } else {
                        display = src; // 原图不放大
                    }

                    final ImageIcon icon = new ImageIcon(display);
                    SwingUtilities.invokeLater(() -> {
                        imageLabel.setIcon(icon);
                        imageLabel.setText("");
                        // 滚动回顶部
                        scrollPane.getViewport().setViewPosition(new Point(0, 0));
                    });
                } catch (Exception ex) {
                    log.error("查看器加载图片失败: {}", ex.getMessage());
                    SwingUtilities.invokeLater(() -> {
                        imageLabel.setIcon(null);
                        imageLabel.setText("图片加载失败: " + ex.getMessage());
                    });
                }
            }, "ImageViewer-Loader").start();
        };

        prevBtn.addActionListener(e -> { if (idx[0] > 0) { idx[0]--; loadImage.run(); } });
        nextBtn.addActionListener(e -> { if (idx[0] < currentImages.size() - 1) { idx[0]++; loadImage.run(); } });

        // 键盘左右键翻页
        KeyStroke left  = KeyStroke.getKeyStroke(KeyEvent.VK_LEFT,  0);
        KeyStroke right = KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0);
        viewer.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(left,  "prev");
        viewer.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(right, "next");
        viewer.getRootPane().getActionMap().put("prev", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (prevBtn.isEnabled()) prevBtn.doClick(); }
        });
        viewer.getRootPane().getActionMap().put("next", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { if (nextBtn.isEnabled()) nextBtn.doClick(); }
        });

        viewer.setVisible(true);
        // invokeLater 确保对话框完成布局后再加载，viewport 尺寸才准确
        SwingUtilities.invokeLater(loadImage);
    }

    /**
     * 递归删除文件夹及其所有内容
     */
    private void deleteDir(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    deleteDir(f);
                }
            }
        }
        try {
            file.delete();
        } catch (Exception e) {
            log.error("删除残留文件失败: {}", file.getAbsolutePath());
        }
    }

    /** 将任意 Image 转为 BufferedImage（用于 webp 等 ImageIO 不直接支持的格式回退路径）*/
    private static java.awt.image.BufferedImage toBufferedImage(Image img) {
        if (img instanceof java.awt.image.BufferedImage bi) return bi;
        // 等待图片完全加载
        new ImageIcon(img); // 触发同步加载
        java.awt.image.BufferedImage bimage = new java.awt.image.BufferedImage(
                img.getWidth(null), img.getHeight(null), java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = bimage.createGraphics();
        g2d.drawImage(img, 0, 0, null);
        g2d.dispose();
        return bimage;
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    /**
     * 从文件夹名解析作品 ID。
     * 优先通过 move_folder 反查（覆盖序号目录及多次移动场景），
     * 回退到文件夹名本身即作品 ID（原始下载目录，如 137315774）。
     */
    private Long resolveArtworkId(File folder) {
        if (serverRunning) {
            try {
                String serverUrl = config.getProperty("server.url", "http://localhost:6999");
                ResponseEntity<Map> resp = restTemplate.getForEntity(
                        serverUrl + "/api/downloaded/by-move-folder?path={path}",
                        Map.class, folder.getAbsolutePath());
                if (resp.getStatusCode() == HttpStatus.OK && resp.getBody() != null) {
                    Object idVal = resp.getBody().get("artworkId");
                    if (idVal instanceof Number) return ((Number) idVal).longValue();
                }
            } catch (org.springframework.web.client.HttpClientErrorException ignored) {
            } catch (Exception e) {
                log.debug("通过 move_folder 查询作品 ID 失败: {}", e.getMessage());
            }
        }
        try {
            long id = Long.parseLong(folder.getName());
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {}
        return null;
    }

    private int findNextFolderNumber(File parentFolder) {
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            if (!Files.exists(parentFolder.toPath().resolve(String.valueOf(i)))) return i;
        }
        return Integer.MAX_VALUE;
    }

    private static String stripTrailingSlash(String path) {
        return path == null ? null : path.replaceAll("[/\\\\]+$", "");
    }
}
