package top.sywyar.pixivdownload.download.testsupport;

import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.ResourceBundleMessageResolver;

public final class WorkbenchTestMessages {

    private WorkbenchTestMessages() {
    }

    public static MessageResolver messages() {
        return ResourceBundleMessageResolver.of(
                null,
                WorkbenchTestMessages.class.getClassLoader(),
                "i18n.workbench.messages");
    }
}
