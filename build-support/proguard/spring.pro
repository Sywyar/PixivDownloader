-keep,includedescriptorclasses,allowoptimization @org.springframework.boot.autoconfigure.SpringBootApplication class * { *; }
-keep,includedescriptorclasses,allowoptimization @org.springframework.context.annotation.Configuration class * { *; }
-keep,includedescriptorclasses,allowoptimization @org.springframework.stereotype.Component class * { *; }
-keep,includedescriptorclasses,allowoptimization @org.springframework.stereotype.Service class * { *; }
-keep,includedescriptorclasses,allowoptimization @org.springframework.stereotype.Repository class * { *; }
-keep,includedescriptorclasses,allowoptimization @org.springframework.stereotype.Controller class * { *; }
-keep,includedescriptorclasses,allowoptimization @org.springframework.web.bind.annotation.RestController class * { *; }
-keep,includedescriptorclasses,allowoptimization @org.springframework.web.bind.annotation.RestControllerAdvice class * { *; }
-keep,includedescriptorclasses,allowoptimization @org.springframework.boot.context.properties.ConfigurationProperties class * { *; }
-keep,includedescriptorclasses,allowoptimization @org.apache.ibatis.annotations.Mapper interface * { *; }

-keepclassmembers,includedescriptorclasses,allowoptimization class * {
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

-keepclassmembers,includedescriptorclasses,allowoptimization class * {
    public <init>(...);
    public <fields>;
    public <methods>;
}
