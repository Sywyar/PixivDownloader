package top.sywyar.pixivdownload.core.db;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ArtworkTagRow {
    private Long artworkId;
    private Long tagId;
    private String name;
    private String translatedName;
}
