package top.sywyar.pixivdownload.sdk.development;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.Handle;
import org.springframework.asm.Opcodes;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PluginSdkUsageTest {
    @TempDir Path temporary;

    @Test
    @DisplayName("实际旧接口引用通过，新方法和方法句柄要求提升最低 SDK，继承引用同样检查")
    void checksCompiledReferences() throws Exception {
        Path minimum = jar("minimum.jar", Map.of("fixture/Api.class", api(false)));
        Path current = jar("current.jar", Map.of("fixture/Api.class", api(true)));
        for (boolean handle : List.of(false, true)) {
            Path oldPlugin = jar("old-" + handle + ".jar", Map.of("fixture/Plugin.class", plugin("oldMethod", handle)));
            assertThatCode(() -> PluginSdkUsage.verify(oldPlugin, List.of(minimum), List.of(current))).doesNotThrowAnyException();
            Path newPlugin = jar("new-" + handle + ".jar", Map.of("fixture/Plugin.class", plugin("newMethod", handle)));
            assertThatThrownBy(() -> PluginSdkUsage.verify(newPlugin, List.of(minimum), List.of(current)))
                    .hasMessageContaining("PLUGIN_REQUIRES_NEWER_SDK").hasMessageContaining("newMethod");
            assertThatCode(() -> PluginSdkUsage.verify(newPlugin, List.of(current), List.of(current))).doesNotThrowAnyException();
        }
        Path inaccessible = jar("inaccessible.jar", Map.of("fixture/Api.class", api(true, Opcodes.ACC_PRIVATE)));
        Path newPlugin = jar("uses-public-method.jar", Map.of("fixture/Plugin.class", plugin("newMethod", false)));
        assertThatThrownBy(() -> PluginSdkUsage.verify(newPlugin, List.of(inaccessible), List.of(current)))
                .hasMessageContaining("newMethod");
    }

    @Test
    @DisplayName("私有依赖的新 SDK 引用不能漏检，伪装 SDK 类和超限 classfile 直接拒绝")
    void checksNestedLibrariesAndBounds() throws Exception {
        Path minimum = jar("minimum.jar", Map.of("fixture/Api.class", api(false)));
        Path current = jar("current.jar", Map.of("fixture/Api.class", api(true)));
        Path nested = jar("nested.jar", Map.of("lib/library.jar", archive(Map.of("fixture/Plugin.class", plugin("newMethod", false)))));
        assertThatThrownBy(() -> PluginSdkUsage.verify(nested, List.of(minimum), List.of(current)))
                .hasMessageContaining("PLUGIN_REQUIRES_NEWER_SDK");
        assertThatThrownBy(() -> PluginSdkUsage.verify(current, List.of(minimum), List.of(current)))
                .hasMessageContaining("PLUGIN_BUNDLES_SDK");
        Path huge = jar("huge.jar", Map.of("fixture/Huge.class", new byte[8 * 1024 * 1024 + 1]));
        assertThatThrownBy(() -> PluginSdkUsage.verify(huge, List.of(minimum), List.of(current)))
                .hasMessageContaining("SDK_SCAN_CLASS_LIMIT");
    }

    @Test
    @DisplayName("多版本 JAR 按 Java 17 选择类，新增 SDK 回调覆盖与注解类型也要求提升下界")
    void checksRuntimeVariantsAndCallbacks() throws Exception {
        Path minimum = jar("minimum.jar", Map.of("fixture/Api.class", api(false)));
        ClassWriter annotation = new ClassWriter(0);
        annotation.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ANNOTATION | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
                "fixture/Marker", null, "java/lang/Object", new String[]{"java/lang/annotation/Annotation"});
        annotation.visitEnd();
        Path current = jar("current.jar", Map.of("fixture/Api.class", api(true), "fixture/Marker.class", annotation.toByteArray()));
        Path variants = jar("variants.jar", Map.of(
                "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nMulti-Release: true\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "fixture/Plugin.class", plugin("oldMethod", false),
                "META-INF/versions/17/fixture/Plugin.class", plugin("newMethod", false)));
        assertThatThrownBy(() -> PluginSdkUsage.verify(variants, List.of(minimum), List.of(current)))
                .hasMessageContaining("newMethod");
        ClassWriter callback = new ClassWriter(0);
        callback.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "fixture/Callback", null, "fixture/Api", null);
        callback.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "newMethod", "()V", null, null).visitEnd();
        callback.visitAnnotation("Lfixture/Marker;", true).visitEnd();
        callback.visitEnd();
        Path annotated = jar("annotated.jar", Map.of("fixture/Callback.class", callback.toByteArray()));
        assertThatThrownBy(() -> PluginSdkUsage.verify(annotated, List.of(minimum), List.of(current)))
                .hasMessageContaining("newMethod").hasMessageContaining("fixture/Marker");
    }

    private byte[] api(boolean added) {
        return api(added, Opcodes.ACC_PUBLIC);
    }

    private byte[] api(boolean added, int methodAccess) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "fixture/Api", null, "java/lang/Object", null);
        writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "oldMethod", "()V", null, null).visitEnd();
        if (added) {
            var method = writer.visitMethod(methodAccess, "newMethod", "()V", null, null);
            method.visitCode();
            method.visitInsn(Opcodes.RETURN);
            method.visitMaxs(0, 1);
            method.visitEnd();
        }
        writer.visitEnd();
        return writer.toByteArray();
    }

    private byte[] plugin(String name, boolean handle) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "fixture/Plugin", null, "fixture/Api", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC, "run", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn("fixture/Api.newMethod");
        method.visitInsn(Opcodes.POP);
        if (handle) {
            method.visitLdcInsn(new Handle(Opcodes.H_INVOKEVIRTUAL, "fixture/Plugin", name, "()V", false));
            method.visitInsn(Opcodes.POP);
        } else {
            method.visitVarInsn(Opcodes.ALOAD, 0);
            method.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "fixture/Plugin", name, "()V", false);
        }
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(1, 1);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private Path jar(String name, Map<String, byte[]> entries) throws Exception {
        return Files.write(temporary.resolve(name), archive(entries));
    }

    private byte[] archive(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue());
                jar.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
