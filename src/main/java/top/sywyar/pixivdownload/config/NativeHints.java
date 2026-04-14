package top.sywyar.pixivdownload.config;

import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportRuntimeHints;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivMapper;
import top.sywyar.pixivdownload.download.db.StatisticsData;
import top.sywyar.pixivdownload.logback.MdcColorConverter;

@Configuration
@ImportRuntimeHints(NativeHints.class)
public class NativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        // MyBatis: PixivMapper 接口的 JDK 动态代理
        hints.proxies().registerJdkProxy(PixivMapper.class);

        // MyBatis: record 结果类型 — 需要通过反射调用 canonical constructor 进行结果映射
        hints.reflection().registerType(ArtworkRecord.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.DECLARED_FIELDS,
                MemberCategory.INTROSPECT_PUBLIC_METHODS,
                MemberCategory.INVOKE_PUBLIC_METHODS);
        hints.reflection().registerType(StatisticsData.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                MemberCategory.DECLARED_FIELDS,
                MemberCategory.INTROSPECT_PUBLIC_METHODS,
                MemberCategory.INVOKE_PUBLIC_METHODS);

        // Logback: logback.xml 通过类名字符串引用自定义 converter，需要反射实例化
        hints.reflection().registerType(MdcColorConverter.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);

        // 静态资源：classpath:/static/ 下的 HTML 和 favicon
        hints.resources().registerPattern("static/*");

        // SSL keystore（开发用自签名证书）
        hints.resources().registerPattern("localhost.jks");
    }
}
