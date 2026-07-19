package top.sywyar.pixivdownload.collection;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.collection.CollectionDownloadRootResolver;
import top.sywyar.pixivdownload.core.collection.WorkCollectionMembership;
import top.sywyar.pixivdownload.core.work.model.WorkType;

import java.nio.file.Path;

/**
 * 将核心收藏夹端口适配到收藏夹 owner 的业务服务。
 */
@Component
public class CoreCollectionAdapter implements WorkCollectionMembership, CollectionDownloadRootResolver {

    private final CollectionService collectionService;

    public CoreCollectionAdapter(CollectionService collectionService) {
        this.collectionService = collectionService;
    }

    @Override
    public boolean addWork(WorkType workType, long collectionId, long workId) {
        return switch (workType) {
            case ARTWORK -> collectionService.addArtwork(collectionId, workId);
            case NOVEL -> collectionService.addNovel(collectionId, workId);
        };
    }

    @Override
    public Path resolveDownloadRoot(long collectionId, Path defaultRoot) {
        return collectionService.resolveDownloadRoot(collectionId, defaultRoot);
    }
}
