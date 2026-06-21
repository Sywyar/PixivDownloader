package top.sywyar.pixivdownload.collection.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class CollectionCreateRequest {
    @NotBlank(message = "{validation.collection.name.required}")
    private String name;
    private String downloadRoot;
}
