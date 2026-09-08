-keep,includedescriptorclasses,allowoptimization class * extends org.pf4j.Plugin {
    public <init>(org.pf4j.PluginWrapper);
}

# child context 的受管 Bean 同样需要保留 Spring 代理结构。
-keep,includedescriptorclasses @top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean class * { *; }
-keep,includedescriptorclasses,allowoptimization class * implements top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider { *; }

-adaptresourcefilecontents plugin.properties,META-INF/spring/**
