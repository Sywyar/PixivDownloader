package top.sywyar.pixivdownload.config.credential;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** 通过 JDK 原生接口调用当前 Windows 用户的 DPAPI，不依赖 GUI 插件。 */
final class WindowsCredentialProtection {
    private static final MemoryLayout BLOB = ADDRESS.byteSize() == 8
            ? MemoryLayout.structLayout(JAVA_INT, MemoryLayout.paddingLayout(4), ADDRESS)
            : MemoryLayout.structLayout(JAVA_INT, ADDRESS);
    private static final long POINTER_OFFSET = ADDRESS.byteSize();

    private static final class Native {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup CRYPT = library("Crypt32.dll");
        private static final MethodHandle PROTECT = operation("CryptProtectData");
        private static final MethodHandle UNPROTECT = operation("CryptUnprotectData");
        private static final MethodHandle FREE = LINKER.downcallHandle(
                library("Kernel32.dll").findOrThrow("LocalFree"), FunctionDescriptor.of(ADDRESS, ADDRESS));

        private static SymbolLookup library(String name) {
            return SymbolLookup.libraryLookup(Path.of(System.getenv("SystemRoot"), "System32", name), Arena.global());
        }

        private static MethodHandle operation(String name) {
            return LINKER.downcallHandle(CRYPT.findOrThrow(name), FunctionDescriptor.of(
                    JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
        }
    }

    private WindowsCredentialProtection() {}

    static byte[] apply(byte[] input, boolean protect) throws IOException {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment bytes = arena.allocateFrom(JAVA_BYTE, input);
            MemorySegment source = arena.allocate(BLOB);
            source.set(JAVA_INT, 0, input.length);
            source.set(ADDRESS, POINTER_OFFSET, bytes);
            MemorySegment target = arena.allocate(BLOB);
            MemorySegment output = MemorySegment.NULL;
            try {
                MethodHandle operation = protect ? Native.PROTECT : Native.UNPROTECT;
                // 禁止系统 UI；不使用 CRYPTPROTECT_LOCAL_MACHINE，保留用户隔离。
                int success = (int) operation.invokeWithArguments(source, MemorySegment.NULL, MemorySegment.NULL,
                        MemorySegment.NULL, MemorySegment.NULL, 1, target);
                output = target.get(ADDRESS, POINTER_OFFSET);
                if (success == 0) throw new IOException("Credential key protection could not be unlocked by this Windows user");
                int size = target.get(JAVA_INT, 0);
                if (output.equals(MemorySegment.NULL) || size <= 0 || size > CredentialKeyProtection.MAX_WRAPPED_BYTES)
                    throw new IOException("Invalid protected credential key size");
                output = output.reinterpret(size);
                return output.toArray(JAVA_BYTE);
            } finally {
                bytes.fill((byte) 0);
                if (!output.equals(MemorySegment.NULL)) {
                    if (output.byteSize() > 0) output.fill((byte) 0);
                    Native.FREE.invokeWithArguments(output);
                }
            }
        } catch (IOException failure) {
            throw failure;
        } catch (Throwable failure) {
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            throw new IOException("Windows credential key protection is unavailable", failure);
        }
    }
}
