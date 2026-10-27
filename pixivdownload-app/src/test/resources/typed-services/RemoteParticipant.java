package acceptance.remote;
import acceptance.remotecontract.RemoteLedger;
import pixivdownload.generated.remote.RemoteLedgerRemoteAdapter;
import org.pf4j.*;
import org.springframework.context.annotation.*;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.service.*;
import top.sywyar.pixivdownload.plugin.api.web.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** 提供方与消费方在独立加载器中运行；所有业务分支仅属于此夹具。 */
public final class RemoteParticipant extends Plugin implements PixivPluginProvider {
    private final String owner;
    public RemoteParticipant(PluginWrapper wrapper) { super(wrapper); owner=wrapper.getPluginId(); }
    static final ServiceKey<RemoteLedger> KEY = new ServiceKey<>("remote-ledger-contract", RemoteLedger.class);
    public static final AtomicInteger active = new AtomicInteger(), closed = new AtomicInteger(), calls = new AtomicInteger();
    public static volatile CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    public static void release() { release.countDown(); }
    public PixivFeaturePlugin featurePlugin() {
        boolean provider = owner.startsWith("remote-ledger-provider");
        return new PixivFeaturePlugin() {
            public String id(){return owner;} public String displayName(){return "plugin.name";}
            public String description(){return "plugin.summary";} public PluginKind kind(){return PluginKind.FEATURE;}
            public List<ServiceKey<?>> serviceImports(){return provider?List.of():List.of(KEY);}
            public List<ServiceExport<?>> serviceExports(){return provider?List.of(new ServiceExport<>(KEY,"ledger")):List.of();}
            public List<WebRouteContribution> routes(){return provider?List.of():RemoteLedgerRemoteAdapter.routes();}
        };
    }
    @Configuration(proxyBeanMethods=false)
    public static class Provider {
        @Bean RemoteLedger ledger() { return new RemoteLedger() {
          public Receipt record(Command command) {
            calls.incrementAndGet();
            if(command.mode()==RemoteLedger.Mode.FAIL) throw new IllegalStateException("private sample",new RuntimeException("private cause"));
            if(command.mode()==RemoteLedger.Mode.BLOCK) {
                entered.countDown();
                boolean done=false;
                while(!done) try {release.await();done=true;} catch(InterruptedException ignored){}
            }
            return new RemoteLedger.Receipt(command.sequence(),command.text(),command.note(),command.numbers(),command.labels(),command.mode());
          }
          public Receipt record(String localOnly) { throw new AssertionError("内部重载不得经远程分派"); }
          public NamedValue __proto__(NamedValue value) { calls.incrementAndGet(); return value; }
          public NamedValue routes(NamedValue value) { calls.incrementAndGet(); return value; }
        }; }
    }
    @Configuration(proxyBeanMethods=false)
    public static class Consumer {
        @Bean RemoteLedgerRemoteAdapter remote(RemoteOperations operations) { return new RemoteLedgerRemoteAdapter(operations); }
        @Bean ServiceBinding binding(PluginServices services) {
            return services.bind(List.of(KEY), scope -> {
                scope.require(KEY);
                active.incrementAndGet();
                scope.acquire(() -> () -> {active.decrementAndGet();closed.incrementAndGet();});
            });
        }
    }
}
