package top.sywyar.pixivdownload.core.db;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class FileNameTemplateRow {
    private Long id;
    private String template;
}
