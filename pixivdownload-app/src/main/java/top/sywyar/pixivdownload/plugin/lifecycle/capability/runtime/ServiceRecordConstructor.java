package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Label;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 契约 loader 的原始类字节已由安装器绑定；只允许不执行用户逻辑的 record 字段赋值构造器。 */
final class ServiceRecordConstructor {
    private ServiceRecordConstructor() { }

    static void validate(Class<?> record) {
        String owner = Type.getInternalName(record);
        List<String> expected = new ArrayList<>();
        expected.add("v:" + Opcodes.ALOAD + ":0");
        expected.add("m:" + Opcodes.INVOKESPECIAL + ":java/lang/Record:<init>:()V:false");
        StringBuilder descriptor = new StringBuilder("(");
        int slot = 1;
        for (var component : record.getRecordComponents()) {
            Type type = Type.getType(component.getType());
            descriptor.append(type.getDescriptor());
            expected.add("v:" + Opcodes.ALOAD + ":0");
            expected.add("v:" + type.getOpcode(Opcodes.ILOAD) + ":" + slot);
            expected.add("f:" + Opcodes.PUTFIELD + ":" + owner + ":" + component.getName() + ":" + type.getDescriptor());
            slot += type.getSize();
        }
        descriptor.append(")V");
        expected.add("i:" + Opcodes.RETURN);
        List<String> actual = new ArrayList<>();
        try (var input = record.getResourceAsStream("/" + owner + ".class")) {
            if (input == null) throw invalid();
            byte[] bytes = input.readNBytes(ServiceValueBoundary.MAX_BYTES + 1);
            if (bytes.length > ServiceValueBoundary.MAX_BYTES) throw invalid();
            new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                    if (!name.equals("<init>") || !desc.equals(descriptor.toString())) return null;
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override public void visitVarInsn(int opcode, int var) { actual.add("v:" + opcode + ":" + var); }
                        @Override public void visitInsn(int opcode) { actual.add("i:" + opcode); }
                        @Override public void visitFieldInsn(int opcode, String o, String n, String d) { actual.add("f:" + opcode + ":" + o + ":" + n + ":" + d); }
                        @Override public void visitMethodInsn(int opcode, String o, String n, String d, boolean itf) { actual.add("m:" + opcode + ":" + o + ":" + n + ":" + d + ":" + itf); }
                        @Override public void visitIntInsn(int opcode, int operand) { throw invalid(); }
                        @Override public void visitTypeInsn(int opcode, String type) { throw invalid(); }
                        @Override public void visitLdcInsn(Object value) { throw invalid(); }
                        @Override public void visitJumpInsn(int opcode, Label label) { throw invalid(); }
                        @Override public void visitIincInsn(int var, int increment) { throw invalid(); }
                        @Override public void visitInvokeDynamicInsn(String n, String d, org.springframework.asm.Handle h, Object... args) { throw invalid(); }
                        @Override public void visitMultiANewArrayInsn(String d, int dimensions) { throw invalid(); }
                        @Override public void visitTableSwitchInsn(int min, int max, Label dflt, Label... labels) { throw invalid(); }
                        @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels) { throw invalid(); }
                        @Override public void visitTryCatchBlock(Label start, Label end, Label handler, String type) { throw invalid(); }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (IOException failure) {
            throw invalid();
        }
        if (!expected.equals(actual)) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("service records require a field-assignment-only canonical constructor");
    }
}
