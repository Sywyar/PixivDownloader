-keep,includedescriptorclasses,allowoptimization class * extends org.pf4j.Plugin {
    public <init>(org.pf4j.PluginWrapper);
}

-keep,includedescriptorclasses,allowoptimization @top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean class * { *; }
-keep,includedescriptorclasses,allowoptimization class * implements top.sywyar.pixivdownload.plugin.api.plugin.PixivPluginProvider { *; }

-adaptresourcefilecontents plugin.properties,META-INF/spring/**
