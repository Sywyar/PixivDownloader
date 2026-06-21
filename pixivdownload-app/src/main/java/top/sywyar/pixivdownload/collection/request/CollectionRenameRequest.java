package top.sywyar.pixivdownload.collection.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CollectionRenameRequest {
    @NotBlank(message = "{validation.collection.name.required}")
    private String name;
}
