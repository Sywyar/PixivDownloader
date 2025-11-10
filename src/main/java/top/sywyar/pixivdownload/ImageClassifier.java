package top.sywyar.pixivdownload;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.List;

@Slf4j
public class ImageClassifier extends JFrame {
    private File parentFolder;
    private List<File> subFolders;
    private int currentFolderIndex = 0;
    private List<File> currentImages;
    private int currentGroupIndex = 0; // 当前组索引
    private static final int GROUP_SIZE = 10; // 每组显示10张图片

    // 目标文件夹配置
    private final String[] targetFolders = {
            "S:\\z\\Ovo\\小特",
            "S:\\z\\Ovo\\气象学家",
            "S:\\z\\Ovo\\小女孩",
            "S:\\z\\Ovo\\0",
            "S:\\z\\Ovo\\0\\idv",
            "S:\\Temp"
    };
    private final String[] folderRemarks = {
            "类别0 - 小特",
            "类别1 - 气象学家",
            "类别2 - 小女孩",
            "类别3 - 默认",
            "类别4 - idv",
            "类别5 - 删除",
    };

    // UI组件
    private JLabel folderLabel;
    private JPanel thumbnailsPanel;
    private JTextField targetFolderField;
    private JLabel remarkLabel;
    private JButton prevGroupButton;
    private JButton nextGroupButton;
    private JButton classifyButton;
    private JButton selectFolderButton;
    private JButton skipFolderButton; // 新增跳过按钮
    private JPanel categoriesPanel;
    private JLabel statusLabel;
    private JLabel[] thumbnailLabels;

    // 支持的图片格式
    private static final String[] IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".gif", ".bmp"};

    public ImageClassifier() {
        initUI();
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

        // 顶部面板 - 文件夹选择
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.setBorder(BorderFactory.createTitledBorder("文件夹选择"));
        selectFolderButton = new JButton("选择父文件夹");
        folderLabel = new JLabel("未选择文件夹");
        topPanel.add(selectFolderButton);
        topPanel.add(folderLabel);

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

        remarkLabel = new JLabel("请输入0-4之间的数字");
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

    private void updateCategoriesPanel() {
        categoriesPanel.removeAll();

        for (int i = 0; i < targetFolders.length; i++) {
            JLabel categoryLabel = new JLabel("<html><b>" + i + "</b> - " + folderRemarks[i] + "<br/><font size='2' color='gray'>" +
                    targetFolders[i] + "</font></html>");
            categoryLabel.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
            categoriesPanel.add(categoryLabel);
        }

        categoriesPanel.revalidate();
        categoriesPanel.repaint();
    }

    private void setupEventListeners() {
        selectFolderButton.addActionListener(e -> selectParentFolder());

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
            remarkLabel.setText("请输入0-4之间的数字");
            return;
        }

        try {
            int index = Integer.parseInt(input);
            if (index >= 0 && index < targetFolders.length) {
                remarkLabel.setText(folderRemarks[index]);
            } else {
                remarkLabel.setText("无效编号，请输入0-" + (targetFolders.length - 1));
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
            return;
        }

        // 计算当前组显示的图片范围
        int startIndex = currentGroupIndex * GROUP_SIZE;
        int endIndex = Math.min(startIndex + GROUP_SIZE, currentImages.size());

        // 显示当前组的图片
        for (int i = 0; i < GROUP_SIZE; i++) {
            int imageIndex = startIndex + i;
            if (imageIndex < endIndex) {
                File imageFile = currentImages.get(imageIndex);

                // 创建图片缩略图
                ImageIcon originalIcon = new ImageIcon(imageFile.getAbsolutePath());
                Image originalImage = originalIcon.getImage();

                // 缩略图尺寸
                int thumbWidth = 150;
                int thumbHeight = 150;

                // 计算缩放比例，保持图片比例
                double widthRatio = (double) thumbWidth / originalIcon.getIconWidth();
                double heightRatio = (double) thumbHeight / originalIcon.getIconHeight();
                double ratio = Math.min(widthRatio, heightRatio);

                int scaledWidth = (int) (originalIcon.getIconWidth() * ratio);
                int scaledHeight = (int) (originalIcon.getIconHeight() * ratio);

                Image scaledImage = originalImage.getScaledInstance(scaledWidth, scaledHeight, Image.SCALE_SMOOTH);
                ImageIcon scaledIcon = new ImageIcon(scaledImage);

                thumbnailLabels[i].setIcon(scaledIcon);
                thumbnailLabels[i].setText(null);
                thumbnailLabels[i].setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
            } else {
                thumbnailLabels[i].setIcon(null);
                thumbnailLabels[i].setText("无图片");
                thumbnailLabels[i].setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
            }
        }

        // 更新导航按钮状态
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

    private void selectParentFolder() {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fileChooser.setDialogTitle("选择包含数字文件夹的父文件夹");

        int result = fileChooser.showOpenDialog(this);
        if (result == JFileChooser.APPROVE_OPTION) {
            parentFolder = fileChooser.getSelectedFile();
            folderLabel.setText("已选择: " + parentFolder.getAbsolutePath());
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
            setTitle(String.format("图片分类工具 - %s/%s - %d 张图片",
                    currentFolder.getName(),
                    subFolders.get(subFolders.size() - 1).getName(),
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
            if (index < 0 || index >= targetFolders.length) {
                JOptionPane.showMessageDialog(this,
                        "请输入0-" + (targetFolders.length - 1) + "之间的数字",
                        "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // 获取目标文件夹路径
            String targetFolderPath = targetFolders[index];
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

                Files.move(currentImage.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                try {
                    sendMoveArtWorkInfo(Long.valueOf(currentImage.toPath().getParent().getFileName().toString()), targetFolder.toPath().toString());
                } catch (Exception e) {
                    log.error("记录失败", e);
                }
            } else {
                // 多张图片，创建数字递增文件夹
                int nextFolderNumber = findNextFolderNumber(targetFolder);
                File numberedFolder = new File(targetFolder, String.valueOf(nextFolderNumber));

                if (!numberedFolder.exists()) {
                    numberedFolder.mkdirs();
                }

                // 移动所有图片到数字文件夹
                for (File image : currentImages) {
                    File targetFile = new File(numberedFolder, image.getName());
                    Files.move(image.toPath(), targetFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                try {
                    sendMoveArtWorkInfo(Long.valueOf(currentImages.get(0).toPath().getParent().getFileName().toString()), targetFolder.toPath().toString());
                } catch (Exception e) {
                    log.error("记录失败", e);
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
        String url = "http://localhost:6999/api/download/downloaded/move/" + artWork;

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