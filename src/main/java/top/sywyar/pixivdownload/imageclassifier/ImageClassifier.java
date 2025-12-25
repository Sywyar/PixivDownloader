package top.sywyar.pixivdownload.imageclassifier;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import javax.swing.*;
import java.awt.*;
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
    private File parentFolder;
    private List<File> subFolders;
    private int currentFolderIndex = 0;
    private List<File> currentImages;
    private int currentGroupIndex = 0; // 当前组索引
    private static final int GROUP_SIZE = 10; // 每组显示10张图片

    private boolean serverRunning = false;

    private final ThumbnailManager thumbnailManager = new ThumbnailManager();
    // 配置管理
    private Properties config;
    private static final String CONFIG_FILE = "image_classifier.properties";

    // 目标文件夹配置 - 从配置加载
    private List<String> targetFolders;
    private List<String> folderRemarks;

    // UI组件
    private JTextField folderPathField; // 改为输入框
    private JPanel thumbnailsPanel;
    private JTextField targetFolderField;
    private JLabel remarkLabel;
    private JButton prevGroupButton;
    private JButton nextGroupButton;
    private JButton classifyButton;
    private JButton openFolderButton; // 改为打开按钮
    private JButton browseFolderButton; // 保留浏览按钮
    private JButton skipFolderButton; // 新增跳过按钮
    private JPanel categoriesPanel;
    private JLabel statusLabel;
    private JLabel[] thumbnailLabels;
    private JLabel serverStatusLabel; // 新增服务器状态标签
    private JButton settingsButton; // 新增设置按钮

    // 支持的图片格式
    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp"};

    public ImageClassifier() {
        loadConfig(); // 加载配置
        initUI();
        checkServerStatus(); // 初始化时检查服务器状态

        // 自动打开默认文件夹
        autoOpenDefaultFolder();
    }

    private void autoOpenDefaultFolder() {
        String defaultFolder = config.getProperty("default.folder", "").trim();
        if (!defaultFolder.isEmpty()) {
            File folder = new File(defaultFolder);
            if (folder.exists() && folder.isDirectory()) {
                // 设置文件夹路径到输入框
                folderPathField.setText(defaultFolder);

                // 延迟执行打开操作，确保UI已经完全初始化
                SwingUtilities.invokeLater(this::openParentFolderFromField);
            } else {
                log.warn("默认文件夹不存在或不是有效目录: {}", defaultFolder);
            }
        }
    }

    private void loadConfig() {
        config = new Properties();

        // 默认配置
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

        // 尝试加载配置文件
        try {
            File configFile = new File(CONFIG_FILE);
            if (configFile.exists()) {
                config.load(new FileInputStream(configFile));
            }
        } catch (IOException e) {
            log.warn("无法加载配置文件，使用默认配置: {}", e.getMessage());
        }

        // 初始化目标文件夹配置 - 改为动态列表
        targetFolders = new ArrayList<>();
        folderRemarks = new ArrayList<>();

        // 从配置加载目标文件夹，最多加载20个
        for (int i = 0; i < 20; i++) {
            String folder = config.getProperty("target.folder." + i);
            String remark = config.getProperty("folder.remark." + i);
            if (folder != null && remark != null) {
                targetFolders.add(folder);
                folderRemarks.add(remark);
            }
        }

        // 如果没有配置，使用默认值
        if (targetFolders.isEmpty()) {
            for (int i = 0; i < defaultTargetFolders.length; i++) {
                targetFolders.add(defaultTargetFolders[i]);
                folderRemarks.add(defaultFolderRemarks[i]);
            }
        }
    }

    private void saveConfig() {
        try {
            // 清除旧的配置
            for (int i = 0; i < 20; i++) {
                config.remove("target.folder." + i);
                config.remove("folder.remark." + i);
            }

            // 保存新的配置
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

    private void initUI() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }

        setTitle("图片分类工具 - 批量移动模式");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1200, 700);
        setLocationRelativeTo(null);

        // 创建主面板
        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 顶部面板 - 文件夹选择和服务器状态
        JPanel topPanel = new JPanel(new BorderLayout());
        topPanel.setBorder(BorderFactory.createTitledBorder("文件夹选择"));

        // 文件夹选择部分 - 修改为输入框和按钮
        JPanel folderSelectionPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        folderSelectionPanel.add(new JLabel("父文件夹路径:"));

        folderPathField = new JTextField(30);
        folderPathField.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        // 设置默认文件夹路径
        String defaultFolder = config.getProperty("default.folder", "");
        folderPathField.setText(defaultFolder);
        folderSelectionPanel.add(folderPathField);

        openFolderButton = new JButton("打开");
        openFolderButton.setFont(new Font("微软雅黑", Font.BOLD, 12));
        folderSelectionPanel.add(openFolderButton);

        browseFolderButton = new JButton("浏览");
        browseFolderButton.setFont(new Font("微软雅黑", Font.BOLD, 12));
        folderSelectionPanel.add(browseFolderButton);

        // 设置按钮
        settingsButton = new JButton("设置");
        settingsButton.setFont(new Font("微软雅黑", Font.BOLD, 12));
        folderSelectionPanel.add(settingsButton);

        // 服务器状态部分
        JPanel serverStatusPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        serverStatusLabel = new JLabel("服务器状态: 检测中...");
        serverStatusLabel.setFont(new Font("微软雅黑", Font.BOLD, 12));
        serverStatusPanel.add(serverStatusLabel);

        topPanel.add(folderSelectionPanel, BorderLayout.WEST);
        topPanel.add(serverStatusPanel, BorderLayout.EAST);

        // 中间面板 - 缩略图显示
        JPanel centerPanel = new JPanel(new BorderLayout(10, 10));
        centerPanel.setBorder(BorderFactory.createTitledBorder("图片预览"));

        // 缩略图面板 - 2行5列
        thumbnailsPanel = new JPanel(new GridLayout(2, 5, 10, 10));
        thumbnailsPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 初始化10个缩略图标签
        thumbnailLabels = new JLabel[GROUP_SIZE];
        for (int i = 0; i < GROUP_SIZE; i++) {
            thumbnailLabels[i] = new JLabel("图片 " + (i + 1), JLabel.CENTER);
            thumbnailLabels[i].setBorder(BorderFactory.createLineBorder(Color.GRAY));
            thumbnailLabels[i].setPreferredSize(new Dimension(150, 150));
            thumbnailLabels[i].setOpaque(true);
            thumbnailLabels[i].setBackground(Color.LIGHT_GRAY);
            thumbnailsPanel.add(thumbnailLabels[i]);
        }

        // 导航面板
        JPanel navPanel = new JPanel(new FlowLayout());
        prevGroupButton = new JButton("← 上一组");
        prevGroupButton.setFont(new Font("微软雅黑", Font.BOLD, 14));
        prevGroupButton.setPreferredSize(new Dimension(120, 35));
        nextGroupButton = new JButton("下一组 →");
        nextGroupButton.setFont(new Font("微软雅黑", Font.BOLD, 14));
        nextGroupButton.setPreferredSize(new Dimension(120, 35));
        navPanel.add(prevGroupButton);
        navPanel.add(Box.createHorizontalStrut(20));
        navPanel.add(nextGroupButton);

        centerPanel.add(thumbnailsPanel, BorderLayout.CENTER);
        centerPanel.add(navPanel, BorderLayout.SOUTH);

        // 分类说明面板
        categoriesPanel = new JPanel();
        categoriesPanel.setBorder(BorderFactory.createTitledBorder("分类说明"));
        categoriesPanel.setLayout(new BoxLayout(categoriesPanel, BoxLayout.Y_AXIS));
        updateCategoriesPanel();

        // 分类操作面板
        JPanel classifyPanel = new JPanel(new FlowLayout());
        classifyPanel.setBorder(BorderFactory.createTitledBorder("文件夹分类"));
        classifyPanel.add(new JLabel("目标文件夹编号:"));
        targetFolderField = new JTextField(5);
        targetFolderField.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        classifyPanel.add(targetFolderField);

        remarkLabel = new JLabel("请输入0-" + (targetFolders.size() - 1) + "之间的数字");
        remarkLabel.setForeground(Color.BLACK);
        classifyPanel.add(remarkLabel);

        classifyButton = new JButton("分类整个文件夹");
        classifyButton.setFont(new Font("微软雅黑", Font.BOLD, 14));
        classifyButton.setBackground(new Color(70, 130, 180));
        classifyButton.setForeground(Color.BLACK);
        classifyButton.setPreferredSize(new Dimension(150, 35));
        classifyPanel.add(classifyButton);

        // 添加跳过按钮
        skipFolderButton = new JButton("跳过此文件夹");
        skipFolderButton.setFont(new Font("微软雅黑", Font.BOLD, 14));
        skipFolderButton.setBackground(new Color(220, 120, 60));
        skipFolderButton.setForeground(Color.BLACK);
        skipFolderButton.setPreferredSize(new Dimension(150, 35));
        classifyPanel.add(skipFolderButton);

        // 根据配置设置跳过按钮的可见性
        boolean showSkipButton = Boolean.parseBoolean(config.getProperty("show.skip.button", "true"));
        skipFolderButton.setVisible(showSkipButton);

        // 状态面板
        JPanel statusPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        statusPanel.setBorder(BorderFactory.createTitledBorder("状态信息"));
        statusLabel = new JLabel("请先选择包含数字文件夹的父目录");
        statusPanel.add(statusLabel);

        // 创建右侧面板，包含分类说明和分类操作
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(categoriesPanel, BorderLayout.CENTER);
        rightPanel.add(classifyPanel, BorderLayout.SOUTH);

        // 添加面板到主面板
        mainPanel.add(topPanel, BorderLayout.NORTH);
        mainPanel.add(centerPanel, BorderLayout.CENTER);
        mainPanel.add(rightPanel, BorderLayout.EAST);
        mainPanel.add(statusPanel, BorderLayout.SOUTH);

        add(mainPanel);

        // 添加事件监听器
        setupEventListeners();
    }

    private void checkServerStatus() {
        new Thread(() -> {
            try {
                String serverUrl = config.getProperty("server.url", "http://localhost:6999");
                String url = serverUrl + "/api/download/status";
                ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);

                if (response.getStatusCode() == HttpStatus.OK) {
                    serverRunning = true;
                    SwingUtilities.invokeLater(() -> {
                        serverStatusLabel.setText("服务器状态: 正常运行");
                        serverStatusLabel.setForeground(new Color(0, 128, 0)); // 绿色
                    });
                } else {
                    serverRunning = false;
                    SwingUtilities.invokeLater(() -> {
                        serverStatusLabel.setText("服务器状态: 异常 (" + response.getStatusCode() + ")");
                        serverStatusLabel.setForeground(Color.RED);
                    });
                }
            } catch (Exception e) {
                serverRunning = false;
                SwingUtilities.invokeLater(() -> {
                    serverStatusLabel.setText("服务器状态: 连接失败");
                    serverStatusLabel.setForeground(Color.RED);
                });
                log.error("检查服务器状态失败: {}", e.getMessage());
            }
        }).start();
    }

    private void updateCategoriesPanel() {
        categoriesPanel.removeAll();

        for (int i = 0; i < targetFolders.size(); i++) {
            JLabel categoryLabel = new JLabel("<html><b>" + i + "</b> - " + folderRemarks.get(i) + "<br/><font size='2' color='gray'>" +
                    targetFolders.get(i) + "</font></html>");
            categoryLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            categoriesPanel.add(categoryLabel);
        }

        categoriesPanel.revalidate();
        categoriesPanel.repaint();
    }

    private void setupEventListeners() {
        // 打开按钮事件 - 从输入框加载文件夹
        openFolderButton.addActionListener(e -> openParentFolderFromField());

        // 浏览按钮事件 - 选择文件夹并填入输入框
        browseFolderButton.addActionListener(e -> browseParentFolder());

        prevGroupButton.addActionListener(e -> showPreviousGroup());

        nextGroupButton.addActionListener(e -> showNextGroup());

        classifyButton.addActionListener(e -> classifyFolder());

        // 添加跳过按钮的事件监听器
        skipFolderButton.addActionListener(e -> skipCurrentFolder());

        // 添加输入框监听器，实时更新备注显示
        targetFolderField.addActionListener(e -> updateRemarkLabel());

        targetFolderField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                updateRemarkLabel();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                updateRemarkLabel();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                updateRemarkLabel();
            }
        });

        // 为文件夹路径输入框添加回车键监听
        folderPathField.addActionListener(e -> openParentFolderFromField());

        // 设置按钮事件
        settingsButton.addActionListener(e -> showSettingsDialog());
    }

    private void showSettingsDialog() {
        JDialog settingsDialog = new JDialog(this, "设置", true);
        settingsDialog.setSize(600, 500);
        settingsDialog.setLocationRelativeTo(this);
        settingsDialog.setLayout(new BorderLayout(10, 10));

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 创建选项卡面板
        JTabbedPane tabbedPane = new JTabbedPane();

        // 基本设置选项卡 - 使用紧凑布局
        JPanel basicPanel = new JPanel(new GridBagLayout());
        basicPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // 默认文件夹设置
        gbc.gridx = 0;
        gbc.gridy = 0;
        basicPanel.add(new JLabel("默认打开文件夹:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        JTextField defaultFolderField = new JTextField(config.getProperty("default.folder", ""));
        basicPanel.add(defaultFolderField, gbc);

        gbc.gridx = 2;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        JButton browseDefaultFolderButton = new JButton("浏览");
        basicPanel.add(browseDefaultFolderButton, gbc);

        // 显示跳过按钮设置
        gbc.gridx = 0;
        gbc.gridy = 1;
        basicPanel.add(new JLabel("显示跳过按钮:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        JCheckBox showSkipButtonCheckBox = new JCheckBox();
        showSkipButtonCheckBox.setSelected(Boolean.parseBoolean(config.getProperty("show.skip.button", "false")));
        basicPanel.add(showSkipButtonCheckBox, gbc);

        // 服务器网址设置
        gbc.gridx = 0;
        gbc.gridy = 2;
        basicPanel.add(new JLabel("服务器网址:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 2;
        gbc.gridwidth = 2;
        JTextField serverUrlField = new JTextField(config.getProperty("server.url", "http://localhost:6999"));
        basicPanel.add(serverUrlField, gbc);

        tabbedPane.addTab("基本设置", basicPanel);

        // 目标文件夹设置选项卡 - 使用表格布局
        JPanel targetFoldersPanel = new JPanel(new BorderLayout(5, 5));

        // 创建表格模型 - 修复：明确使用 DefaultTableModel
        String[] columnNames = {"编号", "路径", "备注"};
        Object[][] data = new Object[targetFolders.size()][3];
        for (int i = 0; i < targetFolders.size(); i++) {
            data[i][0] = i;
            data[i][1] = targetFolders.get(i);
            data[i][2] = folderRemarks.get(i);
        }

        // 明确创建 DefaultTableModel
        javax.swing.table.DefaultTableModel tableModel = new javax.swing.table.DefaultTableModel(data, columnNames) {
            @Override
            public boolean isCellEditable(int row, int column) {
                // 只有编号列不可编辑
                return column != 0;
            }
        };

        JTable targetFoldersTable = new JTable(tableModel);
        targetFoldersTable.setFillsViewportHeight(true);
        targetFoldersTable.getColumnModel().getColumn(0).setMaxWidth(50);
        targetFoldersTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        targetFoldersTable.getColumnModel().getColumn(2).setPreferredWidth(150);

        JScrollPane tableScrollPane = new JScrollPane(targetFoldersTable);
        targetFoldersPanel.add(tableScrollPane, BorderLayout.CENTER);

        // 按钮面板
        JPanel targetFoldersButtonPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        JButton addFolderButton = new JButton("新增");
        JButton editFolderButton = new JButton("编辑");
        JButton deleteFolderButton = new JButton("删除");
        JButton moveUpButton = new JButton("上移");
        JButton moveDownButton = new JButton("下移");

        targetFoldersButtonPanel.add(addFolderButton);
        targetFoldersButtonPanel.add(editFolderButton);
        targetFoldersButtonPanel.add(deleteFolderButton);
        targetFoldersButtonPanel.add(moveUpButton);
        targetFoldersButtonPanel.add(moveDownButton);

        targetFoldersPanel.add(targetFoldersButtonPanel, BorderLayout.SOUTH);

        tabbedPane.addTab("目标文件夹", targetFoldersPanel);

        mainPanel.add(tabbedPane, BorderLayout.CENTER);

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveButton = new JButton("保存");
        JButton cancelButton = new JButton("取消");

        buttonPanel.add(saveButton);
        buttonPanel.add(cancelButton);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        settingsDialog.add(mainPanel);

        // 浏览默认文件夹按钮事件
        browseDefaultFolderButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fileChooser.setDialogTitle("选择默认文件夹");

            int result = fileChooser.showOpenDialog(settingsDialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                defaultFolderField.setText(fileChooser.getSelectedFile().getAbsolutePath());
            }
        });

        // 新增文件夹按钮事件
        addFolderButton.addActionListener(e -> showFolderEditDialog(settingsDialog, -1, tableModel));

        // 编辑文件夹按钮事件
        editFolderButton.addActionListener(e -> {
            int selectedRow = targetFoldersTable.getSelectedRow();
            if (selectedRow >= 0) {
                showFolderEditDialog(settingsDialog, selectedRow, tableModel);
            } else {
                JOptionPane.showMessageDialog(settingsDialog, "请先选择一个文件夹进行编辑",
                        "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        // 删除文件夹按钮事件
        deleteFolderButton.addActionListener(e -> {
            int selectedRow = targetFoldersTable.getSelectedRow();
            if (selectedRow >= 0) {
                int result = JOptionPane.showConfirmDialog(settingsDialog,
                        "确定要删除选中的文件夹吗？", "确认删除",
                        JOptionPane.YES_NO_OPTION);
                if (result == JOptionPane.YES_OPTION) {
                    tableModel.removeRow(selectedRow);
                    // 更新编号
                    for (int i = 0; i < tableModel.getRowCount(); i++) {
                        tableModel.setValueAt(i, i, 0);
                    }
                }
            } else {
                JOptionPane.showMessageDialog(settingsDialog, "请先选择一个文件夹进行删除",
                        "提示", JOptionPane.INFORMATION_MESSAGE);
            }
        });

        // 上移按钮事件
        moveUpButton.addActionListener(e -> {
            int selectedRow = targetFoldersTable.getSelectedRow();
            if (selectedRow > 0) {
                // 交换行数据
                Object[] rowData = new Object[3];
                for (int i = 0; i < 3; i++) {
                    rowData[i] = tableModel.getValueAt(selectedRow, i);
                }

                for (int i = 0; i < 3; i++) {
                    tableModel.setValueAt(tableModel.getValueAt(selectedRow - 1, i), selectedRow, i);
                }

                for (int i = 0; i < 3; i++) {
                    tableModel.setValueAt(rowData[i], selectedRow - 1, i);
                }

                // 更新编号
                tableModel.setValueAt(selectedRow - 1, selectedRow - 1, 0);
                tableModel.setValueAt(selectedRow, selectedRow, 0);

                targetFoldersTable.setRowSelectionInterval(selectedRow - 1, selectedRow - 1);
            }
        });

        // 下移按钮事件
        moveDownButton.addActionListener(e -> {
            int selectedRow = targetFoldersTable.getSelectedRow();
            if (selectedRow >= 0 && selectedRow < tableModel.getRowCount() - 1) {
                // 交换行数据
                Object[] rowData = new Object[3];
                for (int i = 0; i < 3; i++) {
                    rowData[i] = tableModel.getValueAt(selectedRow, i);
                }

                for (int i = 0; i < 3; i++) {
                    tableModel.setValueAt(tableModel.getValueAt(selectedRow + 1, i), selectedRow, i);
                }

                for (int i = 0; i < 3; i++) {
                    tableModel.setValueAt(rowData[i], selectedRow + 1, i);
                }

                // 更新编号
                tableModel.setValueAt(selectedRow, selectedRow, 0);
                tableModel.setValueAt(selectedRow + 1, selectedRow + 1, 0);

                targetFoldersTable.setRowSelectionInterval(selectedRow + 1, selectedRow + 1);
            }
        });

        // 保存按钮事件
        saveButton.addActionListener(e -> {
            // 保存基本设置
            config.setProperty("default.folder", defaultFolderField.getText().trim());
            config.setProperty("show.skip.button", String.valueOf(showSkipButtonCheckBox.isSelected()));
            config.setProperty("server.url", serverUrlField.getText().trim());

            // 保存目标文件夹设置
            targetFolders.clear();
            folderRemarks.clear();

            for (int i = 0; i < tableModel.getRowCount(); i++) {
                targetFolders.add(tableModel.getValueAt(i, 1).toString());
                folderRemarks.add(tableModel.getValueAt(i, 2).toString());
            }

            saveConfig();
            loadConfig(); // 重新加载配置

            // 更新UI
            skipFolderButton.setVisible(showSkipButtonCheckBox.isSelected());
            updateCategoriesPanel();
            remarkLabel.setText("请输入0-" + (targetFolders.size() - 1) + "之间的数字");

            settingsDialog.dispose();

            // 重新打开默认文件夹
            //autoOpenDefaultFolder();

            JOptionPane.showMessageDialog(this, "设置已保存",
                    "提示", JOptionPane.INFORMATION_MESSAGE);
        });

        // 取消按钮事件
        cancelButton.addActionListener(e -> settingsDialog.dispose());

        settingsDialog.setVisible(true);
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
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.insets = new Insets(5, 5, 5, 5);

        // 路径输入
        gbc.gridx = 0;
        gbc.gridy = 0;
        formPanel.add(new JLabel("文件夹路径:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 0;
        gbc.weightx = 1.0;
        JTextField pathField = new JTextField(20);
        if (rowIndex >= 0) {
            pathField.setText(tableModel.getValueAt(rowIndex, 1).toString());
        }
        formPanel.add(pathField, gbc);

        gbc.gridx = 2;
        gbc.gridy = 0;
        gbc.weightx = 0.0;
        JButton browsePathButton = new JButton("浏览");
        formPanel.add(browsePathButton, gbc);

        // 备注输入
        gbc.gridx = 0;
        gbc.gridy = 1;
        formPanel.add(new JLabel("文件夹备注:"), gbc);

        gbc.gridx = 1;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        JTextField remarkField = new JTextField(20);
        if (rowIndex >= 0) {
            remarkField.setText(tableModel.getValueAt(rowIndex, 2).toString());
        }
        formPanel.add(remarkField, gbc);

        mainPanel.add(formPanel, BorderLayout.CENTER);

        // 按钮面板
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okButton = new JButton("确定");
        JButton cancelButton = new JButton("取消");

        buttonPanel.add(okButton);
        buttonPanel.add(cancelButton);

        mainPanel.add(buttonPanel, BorderLayout.SOUTH);

        editDialog.add(mainPanel);

        // 浏览路径按钮事件
        browsePathButton.addActionListener(e -> {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fileChooser.setDialogTitle("选择目标文件夹");

            int result = fileChooser.showOpenDialog(editDialog);
            if (result == JFileChooser.APPROVE_OPTION) {
                pathField.setText(fileChooser.getSelectedFile().getAbsolutePath());
            }
        });

        // 确定按钮事件
        okButton.addActionListener(e -> {
            if (pathField.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(editDialog, "请输入文件夹路径",
                        "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (remarkField.getText().trim().isEmpty()) {
                JOptionPane.showMessageDialog(editDialog, "请输入文件夹备注",
                        "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            if (rowIndex < 0) {
                // 新增
                int newRowIndex = tableModel.getRowCount();
                tableModel.addRow(new Object[]{
                        newRowIndex,
                        pathField.getText().trim(),
                        remarkField.getText().trim()
                });
            } else {
                // 编辑
                tableModel.setValueAt(pathField.getText().trim(), rowIndex, 1);
                tableModel.setValueAt(remarkField.getText().trim(), rowIndex, 2);
            }

            editDialog.dispose();
        });

        // 取消按钮事件
        cancelButton.addActionListener(e -> editDialog.dispose());

        editDialog.setVisible(true);
    }

    // 从输入框打开文件夹
    private void openParentFolderFromField() {
        String folderPath = folderPathField.getText().trim();
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

    // 浏览文件夹并填入输入框
    private void browseParentFolder() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("选择包含数字文件夹的父文件夹");

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            parentFolder = fileChooser.getSelectedFile();
            folderPathField.setText(parentFolder.getAbsolutePath());
            loadSubFolders();
            if (!subFolders.isEmpty()) {
                loadImagesFromCurrentFolder();
                updateThumbnails();
                updateStatus();
            }
        }
    }

    // 新增跳过文件夹方法
    private void skipCurrentFolder() {
        if (currentImages == null || currentImages.isEmpty()) {
            JOptionPane.showMessageDialog(this, "当前文件夹没有图片可跳过", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        moveToNextFolder();
    }

    private void updateRemarkLabel() {
        String input = targetFolderField.getText().trim();
        if (input.isEmpty()) {
            remarkLabel.setText("请输入0-" + (targetFolders.size() - 1) + "之间的数字");
            return;
        }

        try {
            int index = Integer.parseInt(input);
            if (index >= 0 && index < targetFolders.size()) {
                remarkLabel.setText(folderRemarks.get(index));
            } else {
                remarkLabel.setText("无效编号，请输入0-" + (targetFolders.size() - 1));
            }
        } catch (NumberFormatException ex) {
            remarkLabel.setText("请输入有效数字");
        }
    }

    private void updateThumbnails() {
        if (currentImages == null || currentImages.isEmpty()) {
            for (int i = 0; i < GROUP_SIZE; i++) {
                thumbnailLabels[i].setIcon(null);
                thumbnailLabels[i].setText("无图片");
                thumbnailLabels[i].setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
            }
            updateNavigationButtons();
            return;
        }

        // 计算当前组显示范围
        int startIndex = currentGroupIndex * GROUP_SIZE;
        int endIndex = Math.min(startIndex + GROUP_SIZE, currentImages.size());

        int thumbW = 150;
        int thumbH = 150;

        // 显示当前组图片（异步 + 缓存）
        for (int i = 0; i < GROUP_SIZE; i++) {
            int imgIndex = startIndex + i;
            JLabel label = thumbnailLabels[i];

            if (imgIndex < endIndex) {
                File imageFile = currentImages.get(imgIndex);

                // 立即显示占位，不阻塞 UI
                label.setText("加载中…");
                label.setIcon(null);

                // 使用异步缩略图管理器加载
                thumbnailManager.loadThumbnail(imageFile, label, thumbW, thumbH);
            } else {
                label.setIcon(null);
                label.setText("无图片");
            }

            label.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
        }

        int nextStart = (currentGroupIndex + 1) * GROUP_SIZE;
        if (nextStart < currentImages.size()) {
            int nextEnd = Math.min(nextStart + GROUP_SIZE, currentImages.size());
            List<File> nextGroup = currentImages.subList(nextStart, nextEnd);
            thumbnailManager.prefetch(nextGroup, thumbW, thumbH);
        }

        updateNavigationButtons();
    }


    private void updateNavigationButtons() {
        if (currentImages == null || currentImages.isEmpty()) {
            prevGroupButton.setEnabled(false);
            nextGroupButton.setEnabled(false);
            return;
        }

        // 检查是否有上一组
        prevGroupButton.setEnabled(currentGroupIndex > 0);

        // 检查是否有下一组
        int totalGroups = (int) Math.ceil((double) currentImages.size() / GROUP_SIZE);
        nextGroupButton.setEnabled(currentGroupIndex < totalGroups - 1);
    }

    private void loadSubFolders() {
        File[] folders = parentFolder.listFiles(File::isDirectory);
        if (folders != null) {
            // 按文件夹名称的数字顺序排序
            Arrays.sort(folders, (f1, f2) -> {
                try {
                    int num1 = Integer.parseInt(f1.getName());
                    int num2 = Integer.parseInt(f2.getName());
                    return Integer.compare(num1, num2);
                } catch (NumberFormatException e) {
                    return f1.getName().compareTo(f2.getName());
                }
            });
            subFolders = Arrays.asList(folders);
            currentFolderIndex = 0;
            currentGroupIndex = 0; // 重置组索引
        } else {
            subFolders = List.of();
            JOptionPane.showMessageDialog(this, "选择的文件夹中没有子文件夹", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadImagesFromCurrentFolder() {
        if (currentFolderIndex < subFolders.size()) {
            File currentFolder = subFolders.get(currentFolderIndex);
            File[] imageFiles = currentFolder.listFiles((dir, name) -> {
                for (String ext : IMAGE_EXTENSIONS) {
                    if (name.toLowerCase().endsWith(ext)) {
                        return true;
                    }
                }
                return false;
            });

            if (imageFiles != null && imageFiles.length > 0) {
                // 按文件名排序
                Arrays.sort(imageFiles, Comparator.comparing(File::getName));
                currentImages = Arrays.asList(imageFiles);
                currentGroupIndex = 0; // 切换到新文件夹时重置组索引
            } else {
                currentImages = List.of();
            }
        }
    }

    private void updateStatus() {
        if (currentFolderIndex < subFolders.size()) {
            File currentFolder = subFolders.get(currentFolderIndex);
            int totalGroups = currentImages.isEmpty() ? 0 : (int) Math.ceil((double) currentImages.size() / GROUP_SIZE);
            int currentGroup = currentGroupIndex + 1;

            statusLabel.setText(String.format("当前文件夹: %s (%d 张图片) - 第 %d/%d 组",
                    currentFolder.getName(),
                    currentImages.size(),
                    currentGroup,
                    totalGroups));

            // 更新窗口标题显示当前进度
            setTitle(String.format("图片分类工具 - 共%s个文件夹 - %d 张图片",
                    subFolders.size() - currentFolderIndex,
                    currentImages.size()));
        }
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

        try {
            int index = Integer.parseInt(targetFolderNum);
            if (index < 0 || index >= targetFolders.size()) {
                JOptionPane.showMessageDialog(this,
                        "请输入0-" + (targetFolders.size() - 1) + "之间的数字",
                        "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 获取目标文件夹路径
            String targetFolderPath = targetFolders.get(index);
            File targetFolder = new File(targetFolderPath);

            // 确保目标文件夹存在
            if (!targetFolder.exists()) {
                if (!targetFolder.mkdirs()) {
                    JOptionPane.showMessageDialog(this,
                            "无法创建目标文件夹: " + targetFolderPath,
                            "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }

            // 根据图片数量决定移动方式
            if (currentImages.size() == 1) {
                // 只有一张图片，直接移动到目标文件夹
                File currentImage = currentImages.get(0);
                File targetFile = new File(targetFolder, currentImage.getName());

                Long artworkId = Long.valueOf(currentImage.toPath().getParent().getFileName().toString());

                Files.move(currentImage.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                if (serverRunning) {
                    try {
                        sendMoveArtWorkInfo(artworkId, targetFolder.toPath().toString());
                    } catch (Exception e) {
                        log.error("记录失败", e);
                    }
                }
            } else {
                // 多张图片，创建数字递增文件夹
                int nextFolderNumber = findNextFolderNumber(targetFolder);
                File numberedFolder = new File(targetFolder, String.valueOf(nextFolderNumber));

                if (!numberedFolder.exists()) {
                    numberedFolder.mkdirs();
                }

                Long artworkId = Long.valueOf(currentImages.get(0).toPath().getParent().getFileName().toString());

                // 移动所有图片到数字文件夹
                for (File image : currentImages) {
                    File targetFile = new File(numberedFolder, image.getName());
                    Files.move(image.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                if (serverRunning) {
                    try {
                        sendMoveArtWorkInfo(artworkId, numberedFolder.toPath().toString());
                    } catch (Exception e) {
                        log.error("记录失败", e);
                    }
                }

            }

            // 移动到下一个文件夹
            moveToNextFolder();

        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "移动文件时出错: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "请输入有效的文件夹编号", "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private int findNextFolderNumber(File parentFolder) {
        for (int i = 0; i < Integer.MAX_VALUE; i++) {
            if (!Files.exists(parentFolder.toPath().resolve(String.valueOf(i)))) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private void moveToNextFolder() {
        // 删除已处理的文件夹
        File currentFolder = subFolders.get(currentFolderIndex);
        if (currentFolder.listFiles() == null || currentFolder.listFiles().length == 0) {
            // 文件夹为空，删除它
            currentFolder.delete();
        }

        // 移动到下一个文件夹
        currentFolderIndex++;

        if (currentFolderIndex < subFolders.size()) {
            // 加载下一个文件夹的图片
            loadImagesFromCurrentFolder();
            updateThumbnails();
            updateStatus();
        } else {
            // 所有文件夹已处理完毕
            JOptionPane.showMessageDialog(this, "所有文件夹已处理完毕", "完成", JOptionPane.INFORMATION_MESSAGE);
            for (int i = 0; i < GROUP_SIZE; i++) {
                thumbnailLabels[i].setIcon(null);
                thumbnailLabels[i].setText("所有文件夹已处理完毕");
            }
            statusLabel.setText("状态: 所有文件夹已处理完毕");
        }
    }

    private final RestTemplate restTemplate = new RestTemplate();

    private void sendMoveArtWorkInfo(Long artWork, String movePath) {
        String serverUrl = config.getProperty("server.url", "http://localhost:6999");
        String url = serverUrl + "/api/downloaded/move/" + artWork;

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON); // 保持JSON类型

        Map<String, Object> body = new HashMap<>();
        body.put("movePath", movePath);
        body.put("moveTime", System.currentTimeMillis() / 1000);

        HttpEntity<Map<String, Object>> requestEntity =
                new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.POST, requestEntity, String.class);
            log.info("Response: {}", response.getBody());
        } catch (Exception e) {
            log.error("发送请求失败: {}", e.getMessage());
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            ImageClassifier classifier = new ImageClassifier();
            classifier.setVisible(true);
        });
    }
}