package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.Charset;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;

/** Deep, fail-closed copy from a child call into parent-owned values. */
final class CapabilityValueBoundary {

    private CapabilityValueBoundary() {
    }

    static Object copy(Object value, ClassLoader boundaryLoader) throws Throwable {
        return copy(value, boundaryLoader, new IdentityHashMap<>());
    }

    private static Object copy(
            Object value,
            ClassLoader boundaryLoader,
            IdentityHashMap<Object, Boolean> visiting) throws Throwable {
        if (value == null) {
            return null;
        }
        Class<?> type = value.getClass();
        if (isScalar(type)) {
            requireParentOwned(type, boundaryLoader);
            return value;
        }
        if (value instanceof Optional<?> optional) {
            return optional.isEmpty()
                    ? Optional.empty()
                    : Optional.ofNullable(copy(optional.orElse(null), boundaryLoader, visiting));
        }
        if (value instanceof OptionalInt || value instanceof OptionalLong || value instanceof OptionalDouble) {
            return value;
        }
        if (type.isArray()) {
            return copyArray(value, type, boundaryLoader, visiting);
        }

        enter(value, visiting);
        try {
            if (value instanceof List<?> list) {
                List<Object> copied = new ArrayList<>(list.size());
                for (Object item : list) {
                    copied.add(copy(item, boundaryLoader, visiting));
                }
                return Collections.unmodifiableList(copied);
            }
            if (value instanceof Set<?> set) {
                Set<Object> copied = new LinkedHashSet<>();
                for (Object item : set) {
                    copied.add(copy(item, boundaryLoader, visiting));
                }
                return Collections.unmodifiableSet(copied);
            }
            if (value instanceof Map<?, ?> map) {
                Map<Object, Object> copied = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    Object key = copy(entry.getKey(), boundaryLoader, visiting);
                    Object item = copy(entry.getValue(), boundaryLoader, visiting);
                    if (copied.containsKey(key)) {
                        throw unsupported("duplicate map key after defensive copy", type);
                    }
                    copied.put(key, item);
                }
                return Collections.unmodifiableMap(copied);
            }
            if (type.isRecord()) {
                return copyRecord(value, type, boundaryLoader, visiting);
            }
            throw unsupported("unsupported outbound value", type);
        } finally {
            visiting.remove(value);
        }
    }

    private static Object copyArray(
            Object value,
            Class<?> type,
            ClassLoader boundaryLoader,
            IdentityHashMap<Object, Boolean> visiting) throws Throwable {
        Class<?> componentType = type.getComponentType();
        requireParentOwned(componentType, boundaryLoader);
        int length = Array.getLength(value);
        Object copied = Array.newInstance(componentType, length);
        if (componentType.isPrimitive()) {
            System.arraycopy(value, 0, copied, 0, length);
            return copied;
        }
        enter(value, visiting);
        try {
            for (int index = 0; index < length; index++) {
                Array.set(copied, index, copy(Array.get(value, index), boundaryLoader, visiting));
            }
            return copied;
        } finally {
            visiting.remove(value);
        }
    }

    private static Object copyRecord(
            Object value,
            Class<?> type,
            ClassLoader boundaryLoader,
            IdentityHashMap<Object, Boolean> visiting) throws Throwable {
        requireParentOwned(type, boundaryLoader);
        RecordComponent[] components = type.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int index = 0; index < components.length; index++) {
            RecordComponent component = components[index];
            parameterTypes[index] = component.getType();
            Object raw;
            try {
                raw = component.getAccessor().invoke(value);
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
            arguments[index] = copy(raw, boundaryLoader, visiting);
        }
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            if (!constructor.canAccess(null) && !constructor.trySetAccessible()) {
                throw unsupported("record constructor is not accessible", type);
            }
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } catch (ReflectiveOperationException failure) {
            throw unsupported("cannot reconstruct outbound record", type);
        }
    }

    private static void enter(Object value, IdentityHashMap<Object, Boolean> visiting) {
        if (visiting.put(value, Boolean.TRUE) != null) {
            throw unsupported("cyclic outbound value graph", value.getClass());
        }
    }

    private static boolean isScalar(Class<?> type) {
        return type == String.class
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Character.class
                || type == BigInteger.class
                || type == BigDecimal.class
                || type == UUID.class
                || type == URI.class
                || type == Locale.class
                || Charset.class.isAssignableFrom(type)
                || type == Instant.class
                || type == Duration.class
                || type == Period.class
                || type == LocalDate.class
                || type == LocalTime.class
                || type == LocalDateTime.class
                || type == OffsetTime.class
                || type == OffsetDateTime.class
                || type == ZonedDateTime.class
                || type == ZoneOffset.class
                || ZoneId.class.isAssignableFrom(type)
                || type == Year.class
                || type == YearMonth.class
                || type == MonthDay.class
                || type.isEnum();
    }

    private static void requireParentOwned(Class<?> type, ClassLoader boundaryLoader) {
        if (type.isPrimitive()) {
            return;
        }
        ClassLoader valueLoader = type.getClassLoader();
        if (valueLoader == null) {
            return;
        }
        for (ClassLoader current = boundaryLoader; current != null; current = current.getParent()) {
            if (current == valueLoader) {
                return;
            }
        }
        throw unsupported("child-loader value cannot cross capability boundary", type);
    }

    private static ExternalCapabilityInvocationException unsupported(String reason, Class<?> type) {
        return new ExternalCapabilityInvocationException(reason + " (valueType=" + type.getName() + ")");
    }
}
