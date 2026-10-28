package top.sywyar.pixivdownload.testsupport;

import top.sywyar.pixivdownload.i18n.AppMessages;
import top.sywyar.pixivdownload.i18n.CatalogMessageSource;
import top.sywyar.pixivdownload.i18n.LocaleCatalog;
import top.sywyar.pixivdownload.i18n.MessageResolver;

public final class HostTestMessages {
    private HostTestMessages() {}
    public static MessageResolver messages() {
        return new AppMessages(new CatalogMessageSource(LocaleCatalog.defaultCatalog(),
                "i18n/messages", "i18n/notification-templates"));
    }
}
