package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import java.lang.reflect.Array;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 新服务通道的类型图与双向复制；旧领域适配器继续使用各自的合同。 */
final class ServiceValueBoundary {
    static final int MAX_BYTES = 1024 * 1024;
    static final int MAX_NODES = 16_384;
    static final int MAX_DEPTH = 32;
    private static final int MAX_TYPES = 512;
    private final ClassLoader contractLoader;
    private final java.security.CodeSource contractSource;
    private final IdentityHashMap<Object, Boolean> visiting = new IdentityHashMap<>();
    private int nodes;
    private long bytes;

    ServiceValueBoundary(Class<?> contract) {
        contractLoader = contract.getClassLoader();
        contractSource = contract.getProtectionDomain().getCodeSource();
    }

    static void validate(Class<?> contract) {
        if (!contract.isInterface() || contract.isSealed() || !Modifier.isPublic(contract.getModifiers())
                || contract.getTypeParameters().length != 0) throw invalid();
        ServiceValueBoundary boundary = new ServiceValueBoundary(contract);
        Set<String> visited = new HashSet<>();
        Method[] methods = contract.getMethods();
        if (methods.length > 128) throw invalid();
        for (Method method : methods) {
            if (Modifier.isStatic(method.getModifiers())) continue;
            boundary.requireContractType(method.getDeclaringClass());
            if (method.getTypeParameters().length != 0 || method.getExceptionTypes().length != 0) throw invalid();
            if (method.getReturnType() != void.class) boundary.validateType(method.getGenericReturnType(), Map.of(), visited, 0);
            for (Type parameter : method.getGenericParameterTypes()) boundary.validateType(parameter, Map.of(), visited, 0);
        }
    }

    private void validateType(Type type, Map<TypeVariable<?>, Type> bindings, Set<String> visited, int depth) {
        if (depth > MAX_DEPTH) throw invalid();
        type = resolve(type, bindings);
        Class<?> raw = raw(type);
        if (scalar(raw)) {
            if (raw.isEnum() && visited.add(raw.getName())) {
                if (visited.size() > MAX_TYPES) throw invalid();
                requireContractType(raw);
                Set<Class<?>> implementations = new HashSet<>();
                implementations.add(raw);
                for (Object constant : raw.getEnumConstants()) implementations.add(constant.getClass());
                for (Class<?> implementation : implementations) {
                    for (var field : implementation.getDeclaredFields()) {
                        if (!Modifier.isStatic(field.getModifiers())) throw invalid();
                    }
                }
            }
            return;
        }
        if (raw.isArray()) {
            validateType(raw.getComponentType(), bindings, visited, depth + 1);
            return;
        }
        Map<TypeVariable<?>, Type> nested = bindings(type, bindings);
        if (container(raw)) {
            Type[] args = arguments(type);
            if (args.length != (raw == Map.class ? 2 : 1)) throw invalid();
            if ((raw == Map.class || raw == Set.class) && !scalar(raw(resolve(args[0], nested)))) throw invalid();
            for (Type arg : args) validateType(arg, nested, visited, depth + 1);
            return;
        }
        requireRecord(raw);
        // 遍历每个具体泛型绑定；递归 DTO 在运行值复制时另外检查循环与深度。
        String identity = type.getTypeName() + nested;
        if (!visited.add(identity)) return;
        if (visited.size() > MAX_TYPES) throw invalid();
        ServiceRecordConstructor.validate(raw);
        for (RecordComponent component : raw.getRecordComponents()) {
            validateType(component.getGenericType(), nested, visited, depth + 1);
        }
    }

    Object copy(Object value, Type type) throws Throwable {
        return copy(value, type, Map.of(), 0);
    }

    private Object copy(Object value, Type type, Map<TypeVariable<?>, Type> bindings, int depth) throws Throwable {
        if (++nodes > MAX_NODES || depth > MAX_DEPTH) throw invalid();
        type = resolve(type, bindings);
        Class<?> raw = raw(type);
        if (value == null) {
            if (raw.isPrimitive()) throw invalid();
            return null;
        }
        if (scalar(raw)) {
            if (value.getClass() != boxed(raw) && !(raw.isEnum() && raw.isInstance(value))) throw invalid();
            charge(value instanceof String text ? 24L + text.length() * 2L : 24L);
            return value;
        }
        if (!raw.isInstance(value) || visiting.put(value, Boolean.TRUE) != null) throw invalid();
        try {
            if (raw.isArray()) {
                if (value.getClass() != raw) throw invalid();
                int length = Array.getLength(value);
                Class<?> component = raw.getComponentType();
                charge(24L + length * (component.isPrimitive() ? primitiveSize(component) : 8L));
                if (!component.isPrimitive() && length > MAX_NODES - nodes) throw invalid();
                Object result = Array.newInstance(component, length);
                if (component.isPrimitive()) System.arraycopy(value, 0, result, 0, length);
                else for (int i = 0; i < length; i++) Array.set(result, i, copy(Array.get(value, i), component, bindings, depth + 1));
                return result;
            }
            Map<TypeVariable<?>, Type> nested = bindings(type, bindings);
            if (container(raw)) {
                Type[] args = arguments(type);
                if (raw == Optional.class) {
                    Optional<?> optional = (Optional<?>) value;
                    charge(24);
                    return optional.isEmpty() ? Optional.empty()
                            : Optional.ofNullable(copy(optional.orElseThrow(), args[0], nested, depth + 1));
                }
                if (raw == Map.class) {
                    Map<?, ?> source = (Map<?, ?>) value;
                    chargeCollection(source.size(), 48);
                    Map<Object, Object> result = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> entry : source.entrySet()) {
                        Object key = copy(entry.getKey(), args[0], nested, depth + 1);
                        Object item = copy(entry.getValue(), args[1], nested, depth + 1);
                        if (result.containsKey(key)) throw invalid();
                        result.put(key, item);
                    }
                    return Collections.unmodifiableMap(result);
                }
                java.util.Collection<?> source = (java.util.Collection<?>) value;
                chargeCollection(source.size(), raw == Set.class ? 40 : 8);
                if (raw == List.class) {
                    List<Object> result = new ArrayList<>();
                    for (Object item : source) result.add(copy(item, args[0], nested, depth + 1));
                    return Collections.unmodifiableList(result);
                }
                Set<Object> result = new LinkedHashSet<>();
                for (Object item : source) if (!result.add(copy(item, args[0], nested, depth + 1))) throw invalid();
                return Collections.unmodifiableSet(result);
            }
            requireRecord(raw);
            if (value.getClass() != raw) throw invalid();
            RecordComponent[] components = raw.getRecordComponents();
            charge(32L + components.length * 16L);
            Class<?>[] parameterTypes = new Class<?>[components.length];
            Object[] values = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent component = components[i];
                parameterTypes[i] = component.getType();
                var field = raw.getDeclaredField(component.getName());
                if (!field.trySetAccessible()) throw invalid();
                values[i] = copy(field.get(value), component.getGenericType(), nested, depth + 1);
            }
            try {
                return raw.getConstructor(parameterTypes).newInstance(values);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        } finally {
            visiting.remove(value);
        }
    }

    private void chargeCollection(int size, int cost) {
        if (size < 0 || size > MAX_NODES - nodes) throw invalid();
        charge(32L + (long) size * cost);
    }

    private void charge(long amount) {
        bytes += amount;
        if (amount < 0 || bytes > MAX_BYTES) throw invalid();
    }

    private static boolean scalar(Class<?> type) {
        return type.isEnum() || type != void.class && (type.isPrimitive() || type == String.class || type == Boolean.class
                || type == Byte.class || type == Short.class || type == Character.class || type == Integer.class
                || type == Long.class || type == Float.class || type == Double.class
                || type == UUID.class || type == Instant.class || type == Duration.class);
    }

    private void requireRecord(Class<?> type) {
        requireContractType(type);
        if (!type.isRecord()) throw invalid();
        Map<String, Class<?>> fields = new HashMap<>();
        for (var component : type.getRecordComponents()) fields.put(component.getName(), component.getType());
        for (var field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())
                    && (!Modifier.isFinal(field.getModifiers()) || fields.remove(field.getName()) != field.getType())) throw invalid();
        }
        if (!fields.isEmpty()) throw invalid();
    }

    private void requireContractType(Class<?> type) {
        if (!Modifier.isPublic(type.getModifiers()) || type.getClassLoader() != contractLoader
                || contractSource == null || !contractSource.equals(type.getProtectionDomain().getCodeSource())) throw invalid();
    }

    private static boolean container(Class<?> type) {
        return type == List.class || type == Set.class || type == Map.class || type == Optional.class;
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bindings) {
        for (int i = 0; type instanceof TypeVariable<?> variable; i++) {
            if (i > MAX_DEPTH || !bindings.containsKey(variable)) throw invalid();
            type = bindings.get(variable);
        }
        return type;
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> value) return value;
        if (type instanceof ParameterizedType value && value.getRawType() instanceof Class<?> raw) return raw;
        throw invalid();
    }

    private static Type[] arguments(Type type) {
        if (type instanceof ParameterizedType parameterized) return parameterized.getActualTypeArguments();
        throw invalid();
    }

    private static Map<TypeVariable<?>, Type> bindings(Type type, Map<TypeVariable<?>, Type> parent) {
        if (!(type instanceof ParameterizedType parameterized)) {
            if (raw(type).getTypeParameters().length != 0) throw invalid();
            return parent;
        }
        Map<TypeVariable<?>, Type> result = new HashMap<>(parent);
        TypeVariable<?>[] variables = raw(type).getTypeParameters();
        Type[] values = parameterized.getActualTypeArguments();
        if (variables.length != values.length) throw invalid();
        for (int i = 0; i < variables.length; i++) result.put(variables[i], resolve(values[i], parent));
        return result;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == float.class) return Float.class;
        if (type == double.class) return Double.class;
        throw invalid();
    }

    private static int primitiveSize(Class<?> type) {
        if (type == byte.class || type == boolean.class) return 1;
        if (type == short.class || type == char.class) return 2;
        return type == int.class || type == float.class ? 4 : 8;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("SERVICE_VALUE_INVALID");
    }
}
