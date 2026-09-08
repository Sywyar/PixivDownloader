# Spring 反射与 CGLIB 代理依赖入口的可见性和可覆写性；不能允许优化器改写这些结构。
-keep,includedescriptorclasses @org.springframework.boot.autoconfigure.SpringBootApplication class * { *; }
-keep,includedescriptorclasses @org.springframework.context.annotation.Configuration class * { *; }
-keep,includedescriptorclasses @org.springframework.stereotype.Component class * { *; }
-keep,includedescriptorclasses @org.springframework.stereotype.Service class * { *; }
-keep,includedescriptorclasses @org.springframework.stereotype.Repository class * { *; }
-keep,includedescriptorclasses @org.springframework.stereotype.Controller class * { *; }
-keep,includedescriptorclasses @org.springframework.web.bind.annotation.RestController class * { *; }
-keep,includedescriptorclasses @org.springframework.web.bind.annotation.RestControllerAdvice class * { *; }
-keep,includedescriptorclasses @org.springframework.boot.context.properties.ConfigurationProperties class * { *; }
-keep,includedescriptorclasses @org.springframework.scheduling.annotation.Async class * { *; }
-keep,includedescriptorclasses @org.springframework.transaction.annotation.Transactional class * { *; }
-keep,includedescriptorclasses @org.apache.ibatis.annotations.Mapper interface * { *; }

# @Conditional 由 Spring 反射实例化，条件类的无参构造器不要求 public。
-keep,includedescriptorclasses class * implements org.springframework.context.annotation.Condition {
    <init>();
}

# 显式 @Bean 装配的普通类也可能需要代理，包括没有组件注解的非 public 事务方法。
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.springframework.scheduling.annotation.Async <methods>;
}
-keepclasseswithmembers,includedescriptorclasses class * {
    @org.springframework.transaction.annotation.Transactional <methods>;
}

-keepclassmembers,includedescriptorclasses class * {
    @org.springframework.context.annotation.Bean <methods>;
    @org.springframework.context.event.EventListener <methods>;
    @org.springframework.scheduling.annotation.Scheduled <methods>;
    @org.springframework.web.bind.annotation.RequestMapping <methods>;
    @org.springframework.web.bind.annotation.GetMapping <methods>;
    @org.springframework.web.bind.annotation.PostMapping <methods>;
    @org.springframework.web.bind.annotation.PutMapping <methods>;
    @org.springframework.web.bind.annotation.PatchMapping <methods>;
    @org.springframework.web.bind.annotation.DeleteMapping <methods>;
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
