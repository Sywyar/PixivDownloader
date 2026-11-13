package top.sywyar.pixivdownload.plugin.api.gui.importing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 本地作品解析与原地登记语义；路径始终属于运行宿主的机器。 */
public interface LocalArtworkImports {
    /** {@return 已保存的路径模板预设} */
    List<Preset> presets();
    /**
     * 创建异步扫描任务，不登记作品。
     * @param request 本次扫描范围
     * @return 新扫描任务的状态
     */
    Snapshot scan(Request request);
    /**
     * 查询指定任务的当前状态。
     * @param id 扫描或导入任务标识
     * @return 当前状态快照
     */
    Snapshot status(UUID id);
    /**
     * 分页读取指定预览任务的作品。
     * @param id 预览任务标识
     * @param offset 从零开始的偏移量
     * @param limit 本次最多返回的作品数
     * @return 该预览任务中的作品
     */
    List<Item> items(UUID id, int offset, int limit);
    /**
     * 确认并开始登记指定预览中的作品。
     * @param previewId 已完成扫描的预览任务标识
     * @return 导入任务的状态
     */
    Snapshot start(UUID previewId);
    /**
     * 请求取消指定任务。
     * @param id 扫描或导入任务标识
     * @return 取消请求后的状态；进入取消中不代表任务已结束
     */
    Snapshot cancel(UUID id);

    /** 变量校验失败；异常消息只携带稳定错误码，变量名单独供界面和 API 展示。 */
    final class VariableException extends IllegalArgumentException {
        /** 校验失败的原始变量名。 */
        private final String variable;

        /**
         * 创建包含稳定原因码的变量校验异常。
         * @param variable 用户输入的变量名，不含花括号
         * @param known 是否已定义但当前模式不可用
         */
        public VariableException(String variable, boolean known) {
            super(known ? "IMPORT_VARIABLE_UNAVAILABLE" : "UNKNOWN_IMPORT_VARIABLE");
            this.variable = java.util.Objects.requireNonNull(variable);
        }

        /** {@return 用户输入的变量名} */
        public String variable() { return variable; }
    }

    /**
     * 可重复使用的路径模板。
     * @param template 包含作品变量的路径模板
     */
    record Preset(String template) {
        /** 创建并校验路径模板预设。 */
        public Preset {
            if (template == null || template.isBlank() || template.length() > 4096)
                throw new IllegalArgumentException("INVALID_IMPORT_PRESET");
        }
    }
    /**
     * 本次扫描参数；显式模板仅作用于本次导入。
     * @param templates 路径模板；留空时使用全部已保存预设
     * @param artworkIds 待扫描作品 ID；留空时不按作品筛选
     * @param refreshIndex 是否重新扫描目录索引
     * @param selectedFile 选定的宿主本地文件；空字符串表示未选定
     */
    record Request(List<String> templates, List<Long> artworkIds,
                   boolean refreshIndex, String selectedFile) {
        /** 规范化空值并校验扫描参数。 */
        public Request {
            templates = templates == null ? List.of() : List.copyOf(templates);
            artworkIds = artworkIds == null ? List.of() : List.copyOf(artworkIds);
            selectedFile = selectedFile == null ? "" : selectedFile;
            if (templates.size() > 32 || artworkIds.size() > 10000 || selectedFile.length() > 4096
                    || templates.stream().anyMatch(t -> t.isBlank() || t.length() > 4096)
                    || artworkIds.stream().anyMatch(id -> id <= 0))
                throw new IllegalArgumentException("INVALID_IMPORT_REQUEST");
        }
    }
    /** 扫描和导入任务的执行阶段。 */
    enum Phase {
        /** 正在扫描本地文件。 */
        SCANNING,
        /** 扫描完成，等待确认导入。 */
        READY,
        /** 正在登记作品。 */
        IMPORTING,
        /** 已请求取消，等待执行结束。 */
        CANCELLING,
        /** 导入执行结束；单项结果见计数与问题列表。 */
        COMPLETED,
        /** 任务已取消。 */
        CANCELLED,
        /** 任务失败。 */
        FAILED
    }
    /** 作品与现有登记之间的关系。 */
    enum Disposition {
        /** 尚未登记的新作品。 */
        NEW,
        /** 为已登记作品补充文件。 */
        SUPPLEMENT,
        /** 文件已全部登记。 */
        DUPLICATE,
        /** 不能合入现有登记。 */
        CONFLICT,
        /** 未找到匹配文件。 */
        MISSING
    }
    /**
     * 从本地路径解析出的文件信息。
     * @param path 宿主本地文件路径
     * @param sourcePage 路径中解析出的原始页码；缺失时为空
     * @param hints 从路径模板提取的元数据
     */
    record File(String path, Integer sourcePage, Map<String, String> hints) {
        /** 固定元数据快照，避免调用方修改预览内容。 */
        public File { hints = Map.copyOf(hints); }
    }
    /**
     * 单个作品的扫描结果。
     * @param artworkId 作品 ID
     * @param files 匹配到的本地文件
     * @param disposition 与现有登记的关系
     * @param reason 无法导入时的稳定原因码；没有原因时为空字符串
     */
    record Item(long artworkId, List<File> files, Disposition disposition, String reason) {
        /** 固定作品的文件列表。 */
        public Item { files = List.copyOf(files); }
    }
    /**
     * 扫描或导入中的受控问题。
     * @param artworkId 相关作品 ID；任务级问题可为空
     * @param code 稳定问题码
     */
    record Problem(Long artworkId, String code) {}
    /**
     * 指定任务的状态与累计结果。
     * @param id 任务标识
     * @param phase 当前阶段
     * @param artworks 预览作品数
     * @param files 预览文件数
     * @param processed 已处理作品数
     * @param imported 成功登记作品数
     * @param skipped 跳过作品数
     * @param failed 失败作品数
     * @param cachedIndex 是否复用了目录索引
     * @param problems 有界问题样本，不代替失败总数
     */
    record Snapshot(UUID id, Phase phase, int artworks, int files, int processed,
                    int imported, int skipped, int failed, boolean cachedIndex, List<Problem> problems) {
        /** 固定当前问题列表。 */
        public Snapshot { problems = List.copyOf(problems); }
    }
}
