package top.sywyar.pixivdownload.plugin.lifecycle.capability;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.core.download.LocalWorkFileImporter;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImportHandler;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime.*;
import java.util.Map;
import java.util.EnumMap;

/** 精确 publication 撤回与在途调用排空复用 capability runtime。 */
@Component
public final class WorkFileImportCapabilityAdapter implements ExternalRuntimeCapabilityAdapter {
    private record Prepared(ExternalCapabilityOwner owner, Map<WorkType, WorkFileImportHandler> handlers) implements PreparedContribution {}
    private final LocalWorkFileImporter importer;
    private final ExternalCapabilityInvocationRegistry invocations;
    public WorkFileImportCapabilityAdapter(LocalWorkFileImporter importer, ExternalCapabilityInvocationRegistry invocations) {
        this.importer = importer; this.invocations = invocations;
    }
    @Override public String capabilityName() { return WorkFileImportHandler.class.getName(); }
    @Override public PreparedContribution prepare(ExternalCapabilityPreparation preparation, ConfigurableApplicationContext context) {
        Map<WorkType, WorkFileImportHandler> handlers = new EnumMap<>(WorkType.class);
        for (WorkFileImportHandler bean : context.getBeansOfType(WorkFileImportHandler.class).values()) {
            if (handlers.putIfAbsent(bean.workType(), invocations.prepareProxy(preparation, WorkFileImportHandler.class, bean)) != null)
                throw new IllegalArgumentException("Duplicate work import type");
        }
        return new Prepared(preparation.owner(), Map.copyOf(handlers));
    }
    @Override public void publish(PreparedContribution contribution) {
        Prepared prepared = (Prepared) contribution;
        importer.register(prepared.owner(), prepared.handlers());
    }
    @Override public void withdraw(ExternalCapabilityOwner owner) { importer.withdraw(owner); }
}
