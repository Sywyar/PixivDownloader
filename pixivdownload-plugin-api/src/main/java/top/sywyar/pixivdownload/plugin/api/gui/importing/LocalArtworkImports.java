package top.sywyar.pixivdownload.plugin.api.gui.importing;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 本地作品解析与原地登记语义；路径始终属于运行宿主的机器。 */
public interface LocalArtworkImports {
    List<Preset> presets();
    Snapshot scan(Request request);
    Snapshot status(UUID id);
    List<Item> items(UUID id, int offset, int limit);
    Snapshot start(UUID previewId);
    Snapshot cancel(UUID id);

    /** 变量校验失败；异常消息只携带稳定错误码，变量名单独供界面和 API 展示。 */
    final class VariableException extends IllegalArgumentException {
        private final String variable;

        /**
         * @param variable 用户输入的变量名，不含花括号
         * @param known 是否已定义但当前模式不可用
         */
        public VariableException(String variable, boolean known) {
            super(known ? "IMPORT_VARIABLE_UNAVAILABLE" : "UNKNOWN_IMPORT_VARIABLE");
            this.variable = java.util.Objects.requireNonNull(variable);
        }

        /** @return 用户输入的变量名 */
        public String variable() { return variable; }
    }

    record Preset(String name, String template, boolean enabled) {
        public Preset {
            if (name == null || name.isBlank() || name.length() > 100 || template == null || template.isBlank() || template.length() > 4096)
                throw new IllegalArgumentException("INVALID_IMPORT_PRESET");
        }
    }
    record Request(List<String> templates, List<Long> artworkIds, List<String> presetNames,
                   boolean refreshIndex, String selectedFile) {
        public Request {
            templates = templates == null ? List.of() : List.copyOf(templates);
            artworkIds = artworkIds == null ? List.of() : List.copyOf(artworkIds);
            presetNames = presetNames == null ? List.of() : List.copyOf(presetNames);
            selectedFile = selectedFile == null ? "" : selectedFile;
            if (templates.size() > 32 || artworkIds.size() > 10000 || presetNames.size() > 32 || selectedFile.length() > 4096
                    || templates.stream().anyMatch(t -> t.isBlank() || t.length() > 4096)
                    || artworkIds.stream().anyMatch(id -> id <= 0)
                    || presetNames.stream().anyMatch(n -> n.isBlank() || n.length() > 100))
                throw new IllegalArgumentException("INVALID_IMPORT_REQUEST");
        }
    }
    enum Phase { SCANNING, READY, IMPORTING, CANCELLING, COMPLETED, CANCELLED, FAILED }
    enum Disposition { NEW, SUPPLEMENT, DUPLICATE, CONFLICT, MISSING }
    record File(String path, Integer sourcePage, Map<String, String> hints) {
        public File { hints = Map.copyOf(hints); }
    }
    record Item(long artworkId, List<File> files, Disposition disposition, String reason) {
        public Item { files = List.copyOf(files); }
    }
    record Problem(Long artworkId, String code) {}
    record Snapshot(UUID id, Phase phase, int artworks, int files, int processed,
                    int imported, int skipped, int failed, boolean cachedIndex, List<Problem> problems) {
        public Snapshot { problems = List.copyOf(problems); }
    }
}
