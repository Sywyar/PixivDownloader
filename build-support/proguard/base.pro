-dontobfuscate
-optimizationpasses 3
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod,MethodParameters,Record,PermittedSubclasses
-keepdirectories
-dontnote module-info
-dontnote jdk.internal.jimage.**
-dontnote jdk.internal.jrtfs.**

# 只把实际声明 main 的类作为入口，不能保留所有 public 类。
-keepclasseswithmembers,allowoptimization public class * {
    public static void main(java.lang.String[]);
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# Class.getEnumConstants 通过反射查找这些成员，禁止优化改写反射入口。
-keepclassmembers,includedescriptorclasses enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers,includedescriptorclasses,allowoptimization class * extends java.lang.Record {
    <fields>;
    <methods>;
}

# Jackson 依据 Record 属性查找规范构造器，不能删去恒定参数。
-keepclassmembers class * extends java.lang.Record {
    <init>(...);
}
