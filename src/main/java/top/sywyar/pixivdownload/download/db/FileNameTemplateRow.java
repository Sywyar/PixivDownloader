package top.sywyar.pixivdownload.download.db;

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
