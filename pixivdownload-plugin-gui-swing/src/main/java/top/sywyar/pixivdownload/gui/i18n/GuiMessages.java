package top.sywyar.pixivdownload.gui.i18n;

import top.sywyar.pixivdownload.guiswing.SwingHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;

/** Resolves Swing GUI messages from this plugin's exact locale resources. */
public final class GuiMessages {
    private static final Map<String,Map<String,String>> EXACT_BUNDLES=new ConcurrentHashMap<>();
    private static volatile Locale localeOverride;
    private GuiMessages(){}
    public static Locale currentLocale(){
        Locale requested=localeOverride!=null?localeOverride:Locale.getDefault();
        return SwingHost.host().resolveLocale(requested).target().toLocale();
    }
    public static void setLocale(Locale locale){localeOverride=locale;}
    public static void clearLocaleOverride(){localeOverride=null;}
    public static String get(String key,Object... args){
        DesktopUiHost.UiLocaleResolution resolution=SwingHost.host().resolveLocale(currentLocale());
        String pattern=key;
        for(DesktopUiHost.UiLocale locale:resolution.fallbackChain()){
            String value=exact(locale).get(key);
            if(value!=null){pattern=value;break;}
        }
        return args==null||args.length==0?pattern:new MessageFormat(pattern,resolution.target().toLocale()).format(args);
    }
    private static Map<String,String> exact(DesktopUiHost.UiLocale locale){
        return EXACT_BUNDLES.computeIfAbsent(locale.resourceSuffix(),GuiMessages::loadExact);
    }
    private static Map<String,String> loadExact(String suffix){
        String resource="i18n/gui"+(suffix.isEmpty()?"":"_"+suffix)+".properties";
        try(InputStream stream=GuiMessages.class.getClassLoader().getResourceAsStream(resource)){
            if(stream==null){return Map.of();}
            Properties properties=new Properties();
            properties.load(new InputStreamReader(stream,StandardCharsets.UTF_8));
            return properties.stringPropertyNames().stream()
                    .collect(java.util.stream.Collectors.toUnmodifiableMap(key->key,properties::getProperty));
        }catch(IOException e){throw new IllegalStateException("Failed to load GUI messages: "+resource,e);}
    }
}
