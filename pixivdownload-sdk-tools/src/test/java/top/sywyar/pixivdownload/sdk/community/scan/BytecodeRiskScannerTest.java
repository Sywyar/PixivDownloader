package top.sywyar.pixivdownload.sdk.community.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassWriter;
import org.springframework.asm.Opcodes;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.*;

class BytecodeRiskScannerTest {
    @Test
    @DisplayName("每条启用规则只命中完整调用符号，并保留可重算的真实指令偏移")
    void exactCalls() {
        for (var rule : BytecodeRiskScanner.rules()) {
            var actual = BytecodeRiskScanner.scan(example(rule, rule.owner(), rule.name(), rule.descriptor(), rule.opcode()));
            assertThat(actual).singleElement().satisfies(call -> {
                assertThat(call.ruleId()).isEqualTo(rule.id());
                assertThat(call.signal()).isEqualTo(rule.signal());
                assertThat(call.className()).isEqualTo("sample/Plugin");
                assertThat(call.methodName()).isEqualTo("operation");
                assertThat(call.methodDescriptor()).isEqualTo("()V");
                assertThat(call.bytecodeOffset()).isEqualTo(3);
            });
            assertThat(BytecodeRiskScanner.scan(example(rule, "sample/Imitation", rule.name(), rule.descriptor(), rule.opcode()))).isEmpty();
            assertThat(BytecodeRiskScanner.scan(example(rule, rule.owner(), "unrelated", rule.descriptor(), rule.opcode()))).isEmpty();
            assertThat(BytecodeRiskScanner.scan(example(rule, rule.owner(), rule.name(), "()V", rule.opcode()))).isEmpty();
            assertThat(BytecodeRiskScanner.scan(example(rule, rule.owner(), rule.name(), rule.descriptor(), Opcodes.INVOKESPECIAL))).isEmpty();
        }
    }

    @Test
    @DisplayName("方法名字符串不算调用，截断及未知字节码不能被当成空结果")
    void rejectsUnparseableClasses() {
        var rule = BytecodeRiskScanner.rules().get(0);
        byte[] bytes = example(rule, "sample/UnusedDependency", rule.name(), rule.descriptor(), rule.opcode());
        assertThat(BytecodeRiskScanner.scan(bytes)).isEmpty();
        assertThatThrownBy(() -> BytecodeRiskScanner.scan(Arrays.copyOf(bytes, 12))).isInstanceOf(RuntimeException.class);
        bytes[6] = 0x7f;
        bytes[7] = 0x7f;
        assertThatThrownBy(() -> BytecodeRiskScanner.scan(bytes)).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] example(BytecodeRiskScanner.Rule rule, String owner, String name, String descriptor, int opcode) {
        var writer = new ClassWriter(0);
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "sample/Plugin", null, "java/lang/Object", null);
        var method = writer.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "operation", "()V", null, null);
        method.visitCode();
        method.visitLdcInsn(rule.owner() + "." + rule.name());
        method.visitInsn(Opcodes.POP);
        method.visitMethodInsn(opcode, owner, name, descriptor, false);
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(8, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
