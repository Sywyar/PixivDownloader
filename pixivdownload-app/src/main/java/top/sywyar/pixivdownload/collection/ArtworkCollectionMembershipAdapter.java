package top.sywyar.pixivdownload.collection;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.collection.ArtworkCollectionMembership;

/**
 * 将核心收藏关系端口适配到收藏夹 owner 的既有业务服务。
 */
@Component
public class ArtworkCollectionMembershipAdapter implements ArtworkCollectionMembership {

    private final CollectionService collectionService;

    public ArtworkCollectionMembershipAdapter(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Override
    public boolean addArtwork(long collectionId, long artworkId) {
        return collectionService.addArtwork(collectionId, artworkId);
    }
}
