package acceptance.participant;
import acceptance.contract.Probe;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import org.pf4j.*;
import org.springframework.context.annotation.*;
import top.sywyar.pixivdownload.plugin.api.plugin.*;
import top.sywyar.pixivdownload.plugin.api.service.*;

public final class Participant extends Plugin implements PixivPluginProvider {
    private final String owner;
    public Participant(PluginWrapper wrapper) { super(wrapper); owner=wrapper.getPluginId(); }
    public PixivFeaturePlugin featurePlugin() {
        boolean provider=owner.startsWith("probe-provider");
        return new PixivFeaturePlugin() {
            public String id(){return owner;} public String displayName(){return "plugin.name";}
            public String description(){return "plugin.summary";} public PluginKind kind(){return PluginKind.FEATURE;}
            public List<ServiceKey<?>> serviceImports(){return List.of(provider?BACKWARD:FORWARD);}
            public List<ServiceExport<?>> serviceExports(){return provider
                ? List.of(new ServiceExport<>(FORWARD, owner.endsWith("broken")?"missing":"forward"))
                : List.of(new ServiceExport<>(BACKWARD,"backward"));}
        };
    }
    static final ServiceKey<Probe.Forward> FORWARD=new ServiceKey<>("probe-contract",Probe.Forward.class);
    static final ServiceKey<Probe.Backward> BACKWARD=new ServiceKey<>("probe-contract",Probe.Backward.class);
    public static volatile CountDownLatch entered=new CountDownLatch(1), release=new CountDownLatch(1);
    public static volatile Runnable hook;
    public static volatile byte[] output;
    public static volatile String initialReason;
    public static void reset(){entered=new CountDownLatch(1);release=new CountDownLatch(1);}
    public static void release(){release.countDown();}
    public static void hook(Runnable value){hook=value;}
    public static void corrupt(){output[0]=99;}
    @Configuration(proxyBeanMethods=false)
    public static class Provider {
        @Bean Probe.Forward forward(PluginServices services){
            try { services.find(BACKWARD); initialReason="accepted"; }
            catch(ServiceAccessException failure){initialReason=failure.reason().name();}
            return request -> {
                switch(request.mode()){
                    case "nested": return services.require(BACKWARD).call(new Probe.Request("nested",request.depth(),request.data()));
                    case "ordinary": throw new IllegalStateException("secret",new IllegalArgumentException("private graph"));
                    case "fatal": throw new InternalError("test fatal");
                    case "hook": hook.run(); break;
                    case "block":
                        entered.countDown();
                        boolean done=false;
                        while(!done){try{release.await();done=true;}catch(InterruptedException ignored){}}
                        break;
                    default: break;
                }
                request.data()[0]=7;
                output=request.data();
                return new Probe.Reply("done",output);
            };
        }
    }
    @Configuration(proxyBeanMethods=false)
    public static class Consumer {
        @Bean Probe.Backward backward(PluginServices services){
            return request -> request.depth()==0?new Probe.Reply("nested-done",request.data())
                :services.require(FORWARD).call(new Probe.Request("nested",request.depth()-1,request.data()));
        }
    }
}
