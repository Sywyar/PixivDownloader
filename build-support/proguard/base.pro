-dontobfuscate
-optimizationpasses 3
-keepattributes Exceptions,InnerClasses,Signature,Deprecated,SourceFile,LineNumberTable,*Annotation*,EnclosingMethod,MethodParameters,Record,PermittedSubclasses
-keepdirectories
-dontnote module-info
-dontnote jdk.internal.jimage.**
-dontnote jdk.internal.jrtfs.**

-keep,allowoptimization public class * {
    public static void main(java.lang.String[]);
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers,includedescriptorclasses,allowoptimization enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

-keepclassmembers,includedescriptorclasses,allowoptimization class * extends java.lang.Record {
    <fields>;
    <methods>;
}
