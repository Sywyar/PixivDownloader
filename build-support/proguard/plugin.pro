-keep,includedescriptorclasses,allowoptimization class * extends org.pf4j.Plugin {
    public <init>(...);
}

# child context 的受管 Bean 同样需要保留 Spring 代理结构。
-keep,includedescriptorclasses @top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses,allowoptimization class * implements top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider { public <init>(...); }

# 外置插件直接调用这些宿主适配器，调用方不在宿主的收缩输入中。
-keep,includedescriptorclasses class top.sywyar.pixivdownload.plugin.runtime.http.PluginRestTemplateAdapter { public <methods>; }
-keep,includedescriptorclasses class top.sywyar.pixivdownload.plugin.runtime.download.queue.QueueStatusRetention { public <methods>; }

-adaptresourcefilecontents plugin.properties,META-INF/spring/**
