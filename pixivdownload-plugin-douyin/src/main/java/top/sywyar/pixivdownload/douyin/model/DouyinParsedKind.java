package top.sywyar.pixivdownload.douyin.model;

public enum DouyinParsedKind {
    VIDEO,
    NOTE,
    GALLERY,
    SHORT_LINK,
    USER_PROFILE,
    COLLECTION,
    MUSIC;

    public boolean singleWork() {
        return this == VIDEO || this == NOTE || this == GALLERY;
    }

    public boolean downloadableCollection() {
        return this == COLLECTION;
    }
}
