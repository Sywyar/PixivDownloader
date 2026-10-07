package top.sywyar.pixivdownload.sdk.development;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.FieldVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.AnnotationVisitor;
import org.springframework.asm.Type;
import org.springframework.asm.TypePath;

import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import java.util.jar.Manifest;
import java.io.ByteArrayInputStream;

/** 检查编译产物引用，不加载或执行插件类。 */
public final class PluginSdkUsage {
    private static final int MAX_CLASS_BYTES = 8 * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 512L * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;

    private PluginSdkUsage() { }

    public static void verify(Path plugin, List<Path> baseline, List<Path> candidate) throws IOException {
        Map<String, Shape> minimum = read(baseline);
        Map<String, Shape> current = read(candidate);
        Set<String> sdkTypes = new HashSet<>(current.keySet());
        sdkTypes.addAll(minimum.keySet());
        Map<String, Shape> implementation = read(List.of(plugin));
        for (String name : implementation.keySet()) {
            if (sdkTypes.contains(name)) throw new IOException("PLUGIN_BUNDLES_SDK: " + name);
        }
        Map<String, Shape> currentHierarchy = new HashMap<>(current);
        currentHierarchy.putAll(implementation);
        Map<String, Shape> minimumHierarchy = new HashMap<>(minimum);
        minimumHierarchy.putAll(implementation);
        Set<String> missing = new LinkedHashSet<>();
        for (Shape shape : implementation.values()) {
            for (String member : shape.members) {
                for (String parent : shape.parents) {
                    String owner = resolve(currentHierarchy, parent, member, new HashSet<>());
                    if (owner != null && sdkTypes.contains(owner)
                            && resolve(minimumHierarchy, parent, member, new HashSet<>()) == null) {
                        missing.add(parent + "." + member);
                    }
                }
            }
            for (String type : shape.types) {
                if (sdkTypes.contains(type) && !minimum.containsKey(type)) missing.add(type);
            }
            for (Reference reference : shape.references) {
                String owner = resolve(currentHierarchy, reference.owner, reference.member, new HashSet<>());
                if (owner != null && sdkTypes.contains(owner)) {
                    String previousOwner = resolve(minimumHierarchy, reference.owner, reference.member, new HashSet<>());
                    if (previousOwner == null || currentHierarchy.get(owner).publicMembers.contains(reference.member)
                            && !minimumHierarchy.get(previousOwner).publicMembers.contains(reference.member)) {
                        missing.add(reference.owner + "." + reference.member);
                    }
                }
            }
        }
        if (!missing.isEmpty()) throw new IOException("PLUGIN_REQUIRES_NEWER_SDK: " + plugin.getFileName() + ": " + missing);
    }

    private static String resolve(Map<String, Shape> classes, String owner, String member, Set<String> visited) {
        if (!visited.add(owner)) return null;
        Shape shape = classes.get(owner);
        if (shape == null) return null;
        if (shape.members.contains(member) || member.endsWith("()")
                && shape.members.stream().anyMatch(value -> value.startsWith(member))) return owner;
        if (member.startsWith("<init>")) return null;
        for (String parent : shape.parents) {
            String found = resolve(classes, parent, member, visited);
            if (found != null) return found;
        }
        return null;
    }

    private static Map<String, Shape> read(List<Path> jars) throws IOException {
        Map<String, Shape> result = new HashMap<>();
        Budget budget = new Budget();
        for (Path jar : jars) {
            try (InputStream input = Files.newInputStream(jar)) { readArchive(input, result, budget, false); }
        }
        return result;
    }

    private static void readArchive(InputStream input, Map<String, Shape> result, Budget budget, boolean nested)
            throws IOException {
        Map<String, Map<Integer, Shape>> variants = new HashMap<>();
        boolean multiRelease = false;
        // 外层负责输入生命周期；释放嵌套解压器时不关闭所属归档。
        try (ZipInputStream zip = new ZipInputStream(new FilterInputStream(input) {
            @Override public void close() { }
        })) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++budget.entries > MAX_ENTRIES) throw new IOException("SDK_SCAN_ENTRY_LIMIT");
                String name = entry.getName();
                if (name.equalsIgnoreCase("META-INF/MANIFEST.MF")) {
                    byte[] bytes = zip.readNBytes(MAX_CLASS_BYTES + 1);
                    budget.consume(bytes.length);
                    if (bytes.length > MAX_CLASS_BYTES) throw new IOException("SDK_SCAN_CLASS_LIMIT");
                    multiRelease = "true".equalsIgnoreCase(new Manifest(new ByteArrayInputStream(bytes))
                            .getMainAttributes().getValue("Multi-Release"));
                } else if (name.endsWith(".jar") && !nested) {
                    readArchive(zip, result, budget, true);
                } else if (name.endsWith(".class") && !name.endsWith("module-info.class")
                        && !name.endsWith("/package-info.class")) {
                    byte[] bytes = zip.readNBytes(MAX_CLASS_BYTES + 1);
                    budget.consume(bytes.length);
                    if (bytes.length > MAX_CLASS_BYTES) throw new IOException("SDK_SCAN_CLASS_LIMIT");
                    int version = 0;
                    if (name.startsWith("META-INF/versions/")) {
                        String[] parts = name.split("/", 4);
                        try { version = Integer.parseInt(parts[2]); }
                        catch (NumberFormatException invalid) { throw new IOException("SDK_SCAN_INVALID_VERSION", invalid); }
                        if (version < 9 || version > 17) continue;
                    }
                    ClassReader reader = new ClassReader(bytes);
                    if (variants.computeIfAbsent(reader.getClassName(), ignored -> new HashMap<>())
                            .putIfAbsent(version, shape(reader)) != null) {
                        throw new IOException("SDK_SCAN_DUPLICATE_CLASS: " + reader.getClassName());
                    }
                } else {
                    byte[] buffer = new byte[8192];
                    for (int count; (count = zip.read(buffer)) != -1;) budget.consume(count);
                }
            }
        }
        for (var entry : variants.entrySet()) {
            int version = multiRelease ? entry.getValue().keySet().stream().mapToInt(Integer::intValue).max().orElse(0) : 0;
            Shape selected = entry.getValue().get(version);
            if (selected != null && result.putIfAbsent(entry.getKey(), selected) != null) {
                throw new IOException("SDK_SCAN_DUPLICATE_CLASS: " + entry.getKey());
            }
        }
    }

    private static Shape shape(ClassReader reader) {
        Shape shape = new Shape();
        if (reader.getSuperName() != null) shape.parents.add(reader.getSuperName());
        shape.parents.addAll(List.of(reader.getInterfaces()));
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                return annotation(descriptor, shape);
            }
            @Override public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String descriptor, boolean visible) {
                return annotation(descriptor, shape);
            }
            @Override public FieldVisitor visitField(int access, String name, String descriptor, String signature, Object value) {
                member(shape, access, name + descriptor);
                types(descriptor, shape.types);
                return new FieldVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String d, boolean visible) { return annotation(d, shape); }
                    @Override public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String d, boolean visible) {
                        return annotation(d, shape);
                    }
                };
            }
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                member(shape, access, name + descriptor);
                types(descriptor, shape.types);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public AnnotationVisitor visitAnnotation(String d, boolean visible) { return annotation(d, shape); }
                    @Override public AnnotationVisitor visitParameterAnnotation(int index, String d, boolean visible) { return annotation(d, shape); }
                    @Override public AnnotationVisitor visitAnnotationDefault() { return annotation("", shape); }
                    @Override public AnnotationVisitor visitTypeAnnotation(int ref, TypePath path, String d, boolean visible) {
                        return annotation(d, shape);
                    }
                };
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        char[] chars = new char[reader.getMaxStringLength()];
        for (int index = 1; index < reader.getItemCount(); index++) {
            int offset = reader.getItem(index);
            if (offset == 0) continue;
            int tag = reader.readByte(offset - 1);
            if (tag == 7) {
                String type = reader.readUTF8(offset, chars);
                if (type.startsWith("[")) types(type, shape.types);
                else shape.types.add(type);
            } else if (tag == 9 || tag == 10 || tag == 11) {
                String owner = reader.readClass(offset, chars);
                int nameType = reader.getItem(reader.readUnsignedShort(offset + 2));
                shape.references.add(new Reference(owner,
                        reader.readUTF8(nameType, chars) + reader.readUTF8(nameType + 2, chars)));
            } else if (tag == 12) {
                types(reader.readUTF8(offset + 2, chars), shape.types);
            } else if (tag == 16) {
                types(reader.readUTF8(offset, chars), shape.types);
            }
        }
        return shape;
    }

    private static AnnotationVisitor annotation(String descriptor, Shape shape) {
        types(descriptor, shape.types);
        return new AnnotationVisitor(Opcodes.ASM9) {
            private void member(String name) {
                if (!descriptor.isEmpty() && name != null) {
                    shape.references.add(new Reference(Type.getType(descriptor).getInternalName(), name + "()"));
                }
            }
            @Override public void visit(String name, Object value) {
                member(name);
                if (value instanceof Type type) types(type.getDescriptor(), shape.types);
            }
            @Override public void visitEnum(String name, String d, String value) {
                member(name);
                types(d, shape.types);
                shape.references.add(new Reference(Type.getType(d).getInternalName(), value + d));
            }
            @Override public AnnotationVisitor visitAnnotation(String name, String d) { member(name); return annotation(d, shape); }
            @Override public AnnotationVisitor visitArray(String name) { member(name); return this; }
        };
    }

    private static void member(Shape shape, int access, String member) {
        if ((access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED)) != 0) shape.members.add(member);
        if ((access & Opcodes.ACC_PUBLIC) != 0) shape.publicMembers.add(member);
    }

    private static void types(String descriptor, Set<String> result) {
        for (int start = descriptor.indexOf('L'); start >= 0; start = descriptor.indexOf('L', start)) {
            int end = descriptor.indexOf(';', start);
            if (end < 0) break;
            result.add(descriptor.substring(start + 1, end));
            start = end + 1;
        }
    }

    private static final class Shape {
        final List<String> parents = new ArrayList<>();
        final Set<String> members = new HashSet<>();
        final Set<String> publicMembers = new HashSet<>();
        final Set<String> types = new HashSet<>();
        final Set<Reference> references = new HashSet<>();
    }

    private record Reference(String owner, String member) { }

    private static final class Budget {
        int entries;
        long bytes;
        void consume(int count) throws IOException {
            bytes += count;
            if (bytes > MAX_ARCHIVE_BYTES) throw new IOException("SDK_SCAN_BYTE_LIMIT");
        }
    }
}
