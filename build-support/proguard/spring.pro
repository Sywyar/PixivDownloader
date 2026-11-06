# Spring 反射与 CGLIB 代理依赖入口的可见性和可覆写性；不能允许优化器改写这些结构。
-keep,includedescriptorclasses @org.springframework.boot.autoconfigure.SpringBootApplication class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.springframework.context.annotation.Configuration class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.springframework.stereotype.Component class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.springframework.stereotype.Service class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.springframework.stereotype.Repository class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.springframework.stereotype.Controller class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.springframework.web.bind.annotation.RestController class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.springframework.web.bind.annotation.RestControllerAdvice class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.springframework.web.bind.annotation.ControllerAdvice class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.springframework.boot.context.properties.ConfigurationProperties class * { *; }
-keep,includedescriptorclasses @org.springframework.scheduling.annotation.Async class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.springframework.transaction.annotation.Transactional class * { <init>(...); !private <methods>; }
-keep,includedescriptorclasses @org.apache.ibatis.annotations.Mapper interface * { *; }

# spring.factories 按名称实例化环境处理器，静态调用图不包含该入口。
-keep,includedescriptorclasses class * implements org.springframework.boot.EnvironmentPostProcessor {
    public <init>(...);
}

# logback.xml 通过反射构造日志扩展，入口不能只依赖 Java 静态引用。
-keep class * implements ch.qos.logback.core.spi.PropertyDefiner { public <init>(); }
-keep class * implements ch.qos.logback.core.Appender { public <init>(); }
-keep class * implements ch.qos.logback.core.Layout { public <init>(); }
-keep class * extends ch.qos.logback.core.pattern.Converter { public <init>(); }

# @Conditional 由 Spring 反射实例化，条件类的无参构造器不要求 public。
-keep,includedescriptorclasses class * implements org.springframework.context.annotation.Condition {
    <init>();
}

# 子类代理的非注解方法也必须转发到真实 Bean，不能改为 final 后读取代理自身的空字段。
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.springframework.scheduling.annotation.Async <methods>;
    !private !static <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.springframework.transaction.annotation.Transactional <methods>;
    !private !static <methods>;
}

-keepclassmembers,includedescriptorclasses class * {
    @org.springframework.beans.factory.annotation.* <fields>;
    @org.springframework.beans.factory.annotation.* <methods>;
    @jakarta.annotation.* <fields>;
    @jakarta.annotation.* <methods>;
    @org.springframework.context.annotation.Bean <methods>;
    @org.springframework.context.event.EventListener <methods>;
    @org.springframework.scheduling.annotation.Scheduled <methods>;
    @org.springframework.web.bind.annotation.* <methods>;
    @com.fasterxml.jackson.annotation.JsonCreator <init>(...);
    @com.fasterxml.jackson.annotation.JsonCreator <methods>;
    @com.fasterxml.jackson.annotation.JsonProperty <fields>;
    @com.fasterxml.jackson.annotation.JsonProperty <methods>;
}

-dontnote org.springframework.**
-dontnote org.apache.ibatis.annotations.**
-dontnote com.fasterxml.jackson.annotation.**

-keepclassmembers,includedescriptorclasses class * {
    public <init>(...);
    public <fields>;
    public <methods>;
}
