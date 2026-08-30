package top.sywyar.pixivdownload.plugin.runtime.isolation;

import top.sywyar.pixivdownload.plugin.api.plugin.PixivFeaturePlugin;
import top.sywyar.pixivdownload.plugin.api.web.AccessPolicy;
import top.sywyar.pixivdownload.plugin.api.web.HttpMethod;
import top.sywyar.pixivdownload.plugin.api.web.I18nContribution;
import top.sywyar.pixivdownload.plugin.api.web.NavigationContribution;
import top.sywyar.pixivdownload.plugin.api.web.StaticResourceContribution;
import top.sywyar.pixivdownload.plugin.api.web.WebRouteContribution;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 子进程 worker 的有界二进制 stdio 协议。 */
final class IsolatedPluginProtocol {

    static final byte INITIALIZE = 1;
    static final byte START_PACKAGE = 2;
    static final byte START_FEATURE = 3;
    static final byte STOP_FEATURE = 4;
    static final byte STOP_PACKAGE = 5;
    static final byte SHUTDOWN = 6;

    static final byte SUCCESS = 0;
    static final byte FAILURE = 1;

    static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final int MAX_STRING_BYTES = 32 * 1024;
    private static final int MAX_ITEMS = 256;
    private static final int MAX_SET_ITEMS = 64;

    private IsolatedPluginProtocol() {
    }

    static byte[] message(byte type, Encoder encoder) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeByte(type);
            if (encoder != null) {
                encoder.write(output);
            }
        }
        byte[] message = bytes.toByteArray();
        if (message.length > MAX_FRAME_BYTES) {
            throw new IOException("isolated plugin IPC frame exceeds the byte limit");
        }
        return message;
    }

    static void writeFrame(DataOutputStream output, byte[] frame) throws IOException {
        if (frame == null || frame.length == 0 || frame.length > MAX_FRAME_BYTES) {
            throw new IOException("isolated plugin IPC frame has an invalid size");
        }
        output.writeInt(frame.length);
        output.write(frame);
        output.flush();
    }

    static byte[] readFrame(DataInputStream input) throws IOException {
        int length;
        try {
            length = input.readInt();
        } catch (EOFException failure) {
            throw new EOFException("isolated plugin worker closed its IPC stream");
        }
        if (length <= 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("isolated plugin IPC frame has an invalid size: " + length);
        }
        byte[] frame = input.readNBytes(length);
        if (frame.length != length) {
            throw new EOFException("isolated plugin IPC frame was truncated");
        }
        return frame;
    }

    static DataInputStream requireSuccess(byte[] response) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(response));
        int status = input.readUnsignedByte();
        if (status == SUCCESS) {
            return input;
        }
        if (status == FAILURE) {
            throw new IOException("isolated plugin worker rejected the command: " + readString(input));
        }
        throw new IOException("isolated plugin worker returned an unknown response status: " + status);
    }

    static byte[] failure(Throwable failure) throws IOException {
        String message = failure == null ? "unknown worker failure"
                : failure.getClass().getSimpleName() + ": "
                + (failure.getMessage() == null ? "no detail" : failure.getMessage());
        return message(FAILURE, output -> writeString(output, message));
    }

    static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            throw new IOException("isolated plugin IPC string must not be null");
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IOException("isolated plugin IPC string exceeds the byte limit");
        }
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) {
            throw new IOException("isolated plugin IPC string has an invalid size: " + length);
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("isolated plugin IPC string was truncated");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            writeString(output, value);
        }
    }

    private static String readNullableString(DataInputStream input) throws IOException {
        return input.readBoolean() ? readString(input) : null;
    }

    record Snapshot(List<WebRouteContribution> routes,
                    List<StaticResourceContribution> staticResources,
                    List<I18nContribution> i18n,
                    List<NavigationContribution> navigation) {

        Snapshot {
            routes = List.copyOf(routes);
            staticResources = List.copyOf(staticResources);
            i18n = List.copyOf(i18n);
            navigation = List.copyOf(navigation);
        }

        static Snapshot capture(PixivFeaturePlugin plugin) {
            List<WebRouteContribution> routes = requireList(plugin.routes(), "routes");
            List<StaticResourceContribution> resources = requireList(
                    plugin.staticResources(), "staticResources");
            List<I18nContribution> i18n = requireList(plugin.i18n(), "i18n");
            List<NavigationContribution> navigation = requireList(plugin.navigation(), "navigation");

            requireEmpty(plugin.schema(), "schema");
            requireEmpty(plugin.startupRoutes(), "startupRoutes");
            requireEmpty(plugin.landings(), "landings");
            requireEmpty(plugin.pageSections(), "pageSections");
            requireEmpty(plugin.uiSlots(), "uiSlots");
            requireEmpty(plugin.guiThemes(), "guiThemes");
            requireEmpty(plugin.guiConfigContributions(), "guiConfigContributions");
            requireEmpty(plugin.guiOnboardingSteps(), "guiOnboardingSteps");
            requireEmpty(plugin.drilldowns(), "drilldowns");
            requireEmpty(plugin.userscripts(), "userscripts");
            requireEmpty(plugin.scheduledSourceDescriptors(), "scheduledSourceDescriptors");
            requireEmpty(plugin.downloadTypes(), "downloadTypes");

            for (WebRouteContribution route : routes) {
                if (resources.stream().noneMatch(resource -> covers(resource, route.pathPattern()))) {
                    throw new IllegalArgumentException(
                            "isolated static plugin route is not backed by its own resource mapping: "
                                    + route.pathPattern());
                }
            }
            for (NavigationContribution item : navigation) {
                String href = item.href();
                int suffix = href == null ? -1 : firstSuffix(href);
                String path = suffix < 0 ? href : href.substring(0, suffix);
                if (resources.stream().noneMatch(resource -> covers(resource, path))) {
                    throw new IllegalArgumentException(
                            "isolated static plugin navigation target is not backed by its own resource mapping: "
                                    + href);
                }
            }
            return new Snapshot(routes, resources, i18n, navigation);
        }

        void writeTo(DataOutputStream output) throws IOException {
            writeListSize(output, routes.size());
            for (WebRouteContribution route : routes) {
                writeString(output, route.pathPattern());
                writeString(output, route.accessPolicy().name());
                writeSetSize(output, route.methods().size());
                for (HttpMethod method : route.methods()) {
                    writeString(output, method.name());
                }
                output.writeBoolean(route.visibleDuringMaintenance());
            }

            writeListSize(output, staticResources.size());
            for (StaticResourceContribution resource : staticResources) {
                writeString(output, resource.classpathLocation());
                writeString(output, resource.publicPathPrefix());
                output.writeBoolean(resource.exactFile());
            }

            writeListSize(output, i18n.size());
            for (I18nContribution contribution : i18n) {
                writeString(output, contribution.namespace());
                writeString(output, contribution.baseName());
                output.writeInt(contribution.order());
            }

            writeListSize(output, navigation.size());
            for (NavigationContribution item : navigation) {
                writeString(output, item.id());
                writeStringSet(output, item.placements());
                writeNullableString(output, item.labelNamespace());
                writeString(output, item.labelI18nKey());
                writeString(output, item.href());
                writeString(output, item.icon());
                writeString(output, item.visibleTo().name());
                output.writeInt(item.priority());
                writeStringSet(output, item.markers());
            }
        }

        static Snapshot readFrom(DataInputStream input) throws IOException {
            List<WebRouteContribution> routes = new ArrayList<>();
            for (int i = 0, count = readListSize(input); i < count; i++) {
                String path = readString(input);
                AccessPolicy policy = readEnum(input, AccessPolicy.class);
                Set<HttpMethod> methods = new LinkedHashSet<>();
                for (int j = 0, methodCount = readSetSize(input); j < methodCount; j++) {
                    methods.add(readEnum(input, HttpMethod.class));
                }
                routes.add(new WebRouteContribution(path, policy, methods, input.readBoolean()));
            }

            List<StaticResourceContribution> resources = new ArrayList<>();
            for (int i = 0, count = readListSize(input); i < count; i++) {
                resources.add(new StaticResourceContribution(
                        readString(input), readString(input), input.readBoolean()));
            }

            List<I18nContribution> i18n = new ArrayList<>();
            for (int i = 0, count = readListSize(input); i < count; i++) {
                i18n.add(new I18nContribution(readString(input), readString(input), input.readInt()));
            }

            List<NavigationContribution> navigation = new ArrayList<>();
            for (int i = 0, count = readListSize(input); i < count; i++) {
                navigation.add(new NavigationContribution(
                        readString(input), readStringSet(input), readNullableString(input),
                        readString(input), readString(input), readString(input),
                        readEnum(input, AccessPolicy.class), input.readInt(), readStringSet(input)));
            }
            if (input.available() != 0) {
                throw new IOException("isolated plugin IPC snapshot contains trailing bytes");
            }
            return new Snapshot(routes, resources, i18n, navigation);
        }

        private static boolean covers(StaticResourceContribution resource, String publicPath) {
            if (publicPath == null || resource.publicPathPrefix() == null) {
                return false;
            }
            if (resource.exactFile()) {
                return resource.publicPathPrefix().equals(publicPath);
            }
            return publicPath.startsWith(resource.publicPathPrefix());
        }

        private static int firstSuffix(String href) {
            int query = href.indexOf('?');
            int fragment = href.indexOf('#');
            if (query < 0) {
                return fragment;
            }
            return fragment < 0 ? query : Math.min(query, fragment);
        }

        private static <T> List<T> requireList(List<T> value, String name) {
            if (value == null) {
                throw new IllegalArgumentException(name + " returned null");
            }
            if (value.size() > MAX_ITEMS) {
                throw new IllegalArgumentException(name + " exceeds the isolated contribution count limit");
            }
            return List.copyOf(value);
        }

        private static void requireEmpty(List<?> value, String name) {
            if (value == null || !value.isEmpty()) {
                throw new IllegalArgumentException(
                        "isolated static plugin does not support " + name + " contributions");
            }
        }
    }

    private static void writeStringSet(DataOutputStream output, Set<String> values) throws IOException {
        writeSetSize(output, values.size());
        for (String value : values) {
            writeString(output, value);
        }
    }

    private static Set<String> readStringSet(DataInputStream input) throws IOException {
        Set<String> values = new LinkedHashSet<>();
        for (int i = 0, count = readSetSize(input); i < count; i++) {
            values.add(readString(input));
        }
        return Set.copyOf(values);
    }

    private static void writeListSize(DataOutputStream output, int size) throws IOException {
        if (size < 0 || size > MAX_ITEMS) {
            throw new IOException("isolated plugin IPC list has an invalid size: " + size);
        }
        output.writeInt(size);
    }

    private static int readListSize(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_ITEMS) {
            throw new IOException("isolated plugin IPC list has an invalid size: " + size);
        }
        return size;
    }

    private static void writeSetSize(DataOutputStream output, int size) throws IOException {
        if (size < 0 || size > MAX_SET_ITEMS) {
            throw new IOException("isolated plugin IPC set has an invalid size: " + size);
        }
        output.writeInt(size);
    }

    private static int readSetSize(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > MAX_SET_ITEMS) {
            throw new IOException("isolated plugin IPC set has an invalid size: " + size);
        }
        return size;
    }

    private static <E extends Enum<E>> E readEnum(DataInputStream input, Class<E> type) throws IOException {
        String name = readString(input);
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException failure) {
            throw new IOException("isolated plugin IPC enum is invalid: " + type.getSimpleName() + "." + name,
                    failure);
        }
    }

    @FunctionalInterface
    interface Encoder {
        void write(DataOutputStream output) throws IOException;
    }
}
