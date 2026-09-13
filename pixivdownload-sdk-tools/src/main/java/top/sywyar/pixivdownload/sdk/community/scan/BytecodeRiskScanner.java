package top.sywyar.pixivdownload.sdk.community.scan;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;

import java.util.ArrayList;
import java.util.List;

/** 只识别完整方法符号对应的调用指令；不执行类、不推断运行结果或跨方法数据流。 */
public final class BytecodeRiskScanner {
    public static final String VERSION = "jvm-direct-calls-v1";

    public record Rule(String id, String signal, int opcode, String owner, String name, String descriptor) { }
    public record Call(String ruleId, String signal, String className, String methodName,
                       String methodDescriptor, long bytecodeOffset, String owner, String name,
                       String descriptor, int opcode) { }

    private static final List<Rule> RULES = List.of(
            new Rule("files-read-bytes", "FILE_READ", Opcodes.INVOKESTATIC, "java/nio/file/Files", "readAllBytes", "(Ljava/nio/file/Path;)[B"),
            new Rule("files-read-string", "FILE_READ", Opcodes.INVOKESTATIC, "java/nio/file/Files", "readString", "(Ljava/nio/file/Path;)Ljava/lang/String;"),
            new Rule("files-write-bytes", "FILE_WRITE", Opcodes.INVOKESTATIC, "java/nio/file/Files", "write", "(Ljava/nio/file/Path;[B[Ljava/nio/file/OpenOption;)Ljava/nio/file/Path;"),
            new Rule("files-delete", "FILE_DELETE", Opcodes.INVOKESTATIC, "java/nio/file/Files", "delete", "(Ljava/nio/file/Path;)V"),
            new Rule("files-delete-if-exists", "FILE_DELETE", Opcodes.INVOKESTATIC, "java/nio/file/Files", "deleteIfExists", "(Ljava/nio/file/Path;)Z"),
            new Rule("process-builder-start", "PROCESS_EXECUTION", Opcodes.INVOKEVIRTUAL, "java/lang/ProcessBuilder", "start", "()Ljava/lang/Process;"),
            new Rule("runtime-exec-string", "PROCESS_EXECUTION", Opcodes.INVOKEVIRTUAL, "java/lang/Runtime", "exec", "(Ljava/lang/String;)Ljava/lang/Process;"),
            new Rule("system-load", "NATIVE_CODE", Opcodes.INVOKESTATIC, "java/lang/System", "load", "(Ljava/lang/String;)V"),
            new Rule("system-load-library", "NATIVE_CODE", Opcodes.INVOKESTATIC, "java/lang/System", "loadLibrary", "(Ljava/lang/String;)V"),
            new Rule("method-invoke", "REFLECTION", Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;"),
            new Rule("lookup-define-class", "DYNAMIC_CODE_LOADING", Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup", "defineClass", "([B)Ljava/lang/Class;"),
            new Rule("url-open-stream", "NETWORK", Opcodes.INVOKEVIRTUAL, "java/net/URL", "openStream", "()Ljava/io/InputStream;"),
            new Rule("http-client-send", "NETWORK", Opcodes.INVOKEVIRTUAL, "java/net/http/HttpClient", "send", "(Ljava/net/http/HttpRequest;Ljava/net/http/HttpResponse$BodyHandler;)Ljava/net/http/HttpResponse;")
    );

    private BytecodeRiskScanner() { }

    public static List<Rule> rules() { return RULES; }
    public static String rulesSha256() { return CommunityJson.sha256(CommunityJson.encode(RULES)); }

    /** 返回字节码偏移供复核；格式错误及不支持的版本直接交由调用方报告扫描未完成。 */
    public static List<Call> scan(byte[] classFile) {
        var calls = new ArrayList<Call>();
        var reader = new OffsetReader(classFile);
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name, String targetDescriptor, boolean isInterface) {
                        for (var rule : RULES) {
                            if (opcode == rule.opcode && owner.equals(rule.owner) && name.equals(rule.name)
                                    && targetDescriptor.equals(rule.descriptor) && !isInterface) {
                                calls.add(new Call(rule.id, rule.signal, reader.getClassName(), method, descriptor,
                                        reader.offset, owner, name, targetDescriptor, opcode));
                            }
                        }
                    }
                };
            }
        }, 0);
        return List.copyOf(calls);
    }

    private static final class OffsetReader extends ClassReader {
        private int offset;
        private OffsetReader(byte[] bytes) { super(bytes); }
        @Override protected void readBytecodeInstructionOffset(int offset) { this.offset = offset; }
    }
}
