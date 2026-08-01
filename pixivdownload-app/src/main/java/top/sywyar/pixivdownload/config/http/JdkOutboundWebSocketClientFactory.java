package top.sywyar.pixivdownload.config.http;

import top.sywyar.pixivdownload.config.OutboundProxyEndpoint;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClient;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClientFactory;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketClientProfile;
import top.sywyar.pixivdownload.plugin.api.http.websocket.OutboundWebSocketRequest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * App-owned JDK transport implementation of the stable outbound WebSocket capability.
 */
final class JdkOutboundWebSocketClientFactory implements OutboundWebSocketClientFactory {

    private static final ProxySelector DIRECT_PROXY_SELECTOR = new DirectProxySelector();
    private static final HandshakeTransport JDK_TRANSPORT =
            JdkOutboundWebSocketClientFactory::startJdkHandshake;

    private final ProxyConfig proxyConfig;
    private final HandshakeTransport transport;

    JdkOutboundWebSocketClientFactory(ProxyConfig proxyConfig) {
        this(proxyConfig, JDK_TRANSPORT);
    }

    JdkOutboundWebSocketClientFactory(
            ProxyConfig proxyConfig,
            HandshakeTransport transport
    ) {
        this.proxyConfig = Objects.requireNonNull(proxyConfig, "proxyConfig");
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    @Override
    public OutboundWebSocketClient open(OutboundWebSocketClientProfile profile) {
        return new JdkOutboundWebSocketClient(
                Objects.requireNonNull(profile, "profile"),
                new OutboundHttpProxyResolver(proxyConfig),
                transport);
    }

    private static CompletableFuture<WebSocket> startJdkHandshake(
            Duration connectTimeout,
            ProxySelector proxySelector,
            OutboundWebSocketRequest request,
            WebSocket.Listener listener
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .proxy(proxySelector)
                .build();
        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .connectTimeout(connectTimeout);
        request.headers().forEach((name, values) ->
                values.forEach(value -> builder.header(name, value)));
        return builder.buildAsync(request.uri(), listener);
    }

    @FunctionalInterface
    interface HandshakeTransport {

        CompletableFuture<WebSocket> connect(
                Duration connectTimeout,
                ProxySelector proxySelector,
                OutboundWebSocketRequest request,
                WebSocket.Listener listener
        );
    }

    private static final class JdkOutboundWebSocketClient
            implements OutboundWebSocketClient {

        private final OutboundWebSocketClientProfile profile;
        private final OutboundHttpProxyResolver proxyResolver;
        private final HandshakeTransport transport;
        private final Object monitor = new Object();
        private final Set<ConnectionAttempt> pending =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<ConnectionAttempt> active =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private boolean closed;

        private JdkOutboundWebSocketClient(
                OutboundWebSocketClientProfile profile,
                OutboundHttpProxyResolver proxyResolver,
                HandshakeTransport transport
        ) {
            this.profile = profile;
            this.proxyResolver = proxyResolver;
            this.transport = transport;
        }

        @Override
        public CompletableFuture<WebSocket> connect(
                OutboundWebSocketRequest request,
                WebSocket.Listener listener
        ) {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(listener, "listener");
            ConnectionAttempt attempt = new ConnectionAttempt(this, listener);
            synchronized (monitor) {
                if (closed) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Outbound WebSocket client is closed"));
                }
                pending.add(attempt);
            }

            try {
                OutboundProxyEndpoint endpoint = proxyResolver.resolve(profile.route());
                if (attempt.result().isCancelled()) {
                    removePending(attempt);
                    return attempt.result();
                }
                ProxySelector proxySelector = endpoint == null
                        ? DIRECT_PROXY_SELECTOR
                        : ProxySelector.of(InetSocketAddress.createUnresolved(
                                endpoint.hostName(), endpoint.port()));
                CompletableFuture<WebSocket> handshake = Objects.requireNonNull(
                        transport.connect(
                                profile.connectTimeout(),
                                proxySelector,
                                request,
                                attempt.listener()),
                        "WebSocket handshake transport returned null");
                attempt.bind(handshake);
                handshake.whenComplete(attempt::handshakeCompleted);
            } catch (VirtualMachineError | ThreadDeath fatal) {
                attempt.failBeforeHandshake(fatal);
                throw fatal;
            } catch (Throwable failure) {
                attempt.failBeforeHandshake(failure);
            }
            return attempt.result();
        }

        private void handshakeCompleted(
                ConnectionAttempt attempt,
                WebSocket webSocket,
                Throwable failure
        ) {
            boolean publishSocket = false;
            boolean abortSocket = false;
            synchronized (monitor) {
                pending.remove(attempt);
                if (failure == null && webSocket != null) {
                    attempt.socket(webSocket);
                    if (closed || attempt.result().isCancelled()) {
                        abortSocket = true;
                        attempt.clearListener();
                    } else if (!attempt.listenerTerminated()) {
                        active.add(attempt);
                    }
                    publishSocket = true;
                } else {
                    attempt.clearListener();
                }
            }

            if (publishSocket) {
                if (abortSocket) {
                    abortQuietly(webSocket);
                }
                if (!attempt.complete(webSocket) && !abortSocket) {
                    retireAndAbort(attempt, webSocket);
                }
                return;
            }
            Throwable actualFailure = failure != null
                    ? failure
                    : new IllegalStateException(
                            "WebSocket handshake completed without a socket");
            attempt.completeExceptionally(actualFailure);
        }

        private void listenerTerminated(
                ConnectionAttempt attempt,
                WebSocket webSocket
        ) {
            synchronized (monitor) {
                attempt.listenerTerminated(true);
                active.remove(attempt);
            }
            attempt.clearListener();
        }

        private void removePending(ConnectionAttempt attempt) {
            synchronized (monitor) {
                pending.remove(attempt);
            }
            attempt.clearListener();
        }

        private void retireAndAbort(
                ConnectionAttempt attempt,
                WebSocket webSocket
        ) {
            synchronized (monitor) {
                active.remove(attempt);
            }
            attempt.clearListener();
            abortQuietly(webSocket);
        }

        @Override
        public void close() {
            List<ConnectionAttempt> pendingSnapshot;
            List<ConnectionAttempt> activeSnapshot;
            synchronized (monitor) {
                if (closed) {
                    return;
                }
                closed = true;
                pendingSnapshot = new ArrayList<>(pending);
                activeSnapshot = new ArrayList<>(active);
                pending.clear();
                active.clear();
                pendingSnapshot.forEach(ConnectionAttempt::clearListener);
                activeSnapshot.forEach(ConnectionAttempt::clearListener);
            }

            Throwable fatalFailure = null;
            for (ConnectionAttempt attempt : pendingSnapshot) {
                try {
                    attempt.cancel();
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    fatalFailure = firstFailure(fatalFailure, fatal);
                }
            }
            for (ConnectionAttempt attempt : activeSnapshot) {
                try {
                    abortQuietly(attempt.socket());
                } catch (VirtualMachineError | ThreadDeath fatal) {
                    fatalFailure = firstFailure(fatalFailure, fatal);
                }
            }
            rethrowFatal(fatalFailure);
        }
    }

    private static final class ConnectionAttempt {

        private final JdkOutboundWebSocketClient owner;
        private final PropagatingConnectFuture result =
                new PropagatingConnectFuture();
        private final ManagedListener listener;
        private volatile WebSocket socket;
        private boolean listenerTerminated;

        private ConnectionAttempt(
                JdkOutboundWebSocketClient owner,
                WebSocket.Listener listener
        ) {
            this.owner = owner;
            this.listener = new ManagedListener(
                    listener,
                    webSocket -> owner.listenerTerminated(this, webSocket));
        }

        private PropagatingConnectFuture result() {
            return result;
        }

        private ManagedListener listener() {
            return listener;
        }

        private void bind(CompletableFuture<WebSocket> handshake) {
            result.bind(handshake);
        }

        private void handshakeCompleted(WebSocket webSocket, Throwable failure) {
            result.transportCompleted();
            owner.handshakeCompleted(this, webSocket, failure);
        }

        private void failBeforeHandshake(Throwable failure) {
            owner.handshakeCompleted(this, null, failure);
        }

        private boolean complete(WebSocket webSocket) {
            return result.completeFromTransport(webSocket);
        }

        private void completeExceptionally(Throwable failure) {
            result.completeExceptionallyFromTransport(failure);
        }

        private void cancel() {
            result.cancel(true);
        }

        private void socket(WebSocket value) {
            socket = value;
        }

        private WebSocket socket() {
            return socket;
        }

        private boolean listenerTerminated() {
            return listenerTerminated;
        }

        private void listenerTerminated(boolean value) {
            listenerTerminated = value;
        }

        private void clearListener() {
            listener.clear();
        }
    }

    private static final class PropagatingConnectFuture
            extends CompletableFuture<WebSocket> {

        private final AtomicReference<CompletableFuture<WebSocket>> handshake =
                new AtomicReference<>();
        private final AtomicBoolean cancellationRequested = new AtomicBoolean();
        private volatile boolean interruptOnCancel;

        private void bind(CompletableFuture<WebSocket> value) {
            if (!handshake.compareAndSet(null, value)) {
                throw new IllegalStateException("WebSocket handshake is already bound");
            }
            if (cancellationRequested.get()) {
                value.cancel(interruptOnCancel);
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            interruptOnCancel |= mayInterruptIfRunning;
            cancellationRequested.set(true);
            CompletableFuture<WebSocket> current = handshake.get();
            if (current != null) {
                current.cancel(mayInterruptIfRunning);
            }
            return super.cancel(mayInterruptIfRunning);
        }

        private boolean completeFromTransport(WebSocket webSocket) {
            return super.complete(webSocket);
        }

        private void completeExceptionallyFromTransport(Throwable failure) {
            if (failure instanceof CancellationException) {
                super.cancel(false);
            } else {
                super.completeExceptionally(failure);
            }
        }

        private void transportCompleted() {
            handshake.set(null);
        }
    }

    private static final class ManagedListener implements WebSocket.Listener {

        private final AtomicReference<WebSocket.Listener> delegate;
        private final AtomicReference<java.util.function.Consumer<WebSocket>>
                terminalCallback;
        private final AtomicBoolean terminal = new AtomicBoolean();

        private ManagedListener(
                WebSocket.Listener delegate,
                java.util.function.Consumer<WebSocket> terminalCallback
        ) {
            this.delegate = new AtomicReference<>(
                    Objects.requireNonNull(delegate, "delegate"));
            this.terminalCallback = new AtomicReference<>(terminalCallback);
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            WebSocket.Listener current = delegate.get();
            if (current == null) {
                abortQuietly(webSocket);
                return;
            }
            current.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last
        ) {
            WebSocket.Listener current = delegate.get();
            if (current == null) {
                abortQuietly(webSocket);
                return null;
            }
            return current.onText(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onBinary(
                WebSocket webSocket,
                ByteBuffer data,
                boolean last
        ) {
            WebSocket.Listener current = delegate.get();
            if (current == null) {
                abortQuietly(webSocket);
                return null;
            }
            return current.onBinary(webSocket, data, last);
        }

        @Override
        public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
            WebSocket.Listener current = delegate.get();
            if (current == null) {
                abortQuietly(webSocket);
                return null;
            }
            return current.onPing(webSocket, message);
        }

        @Override
        public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
            WebSocket.Listener current = delegate.get();
            if (current == null) {
                abortQuietly(webSocket);
                return null;
            }
            return current.onPong(webSocket, message);
        }

        @Override
        public CompletionStage<?> onClose(
                WebSocket webSocket,
                int statusCode,
                String reason
        ) {
            try {
                WebSocket.Listener current = delegate.get();
                return current == null
                        ? null
                        : current.onClose(webSocket, statusCode, reason);
            } finally {
                terminate(webSocket);
            }
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            try {
                WebSocket.Listener current = delegate.get();
                if (current != null) {
                    current.onError(webSocket, error);
                }
            } finally {
                terminate(webSocket);
            }
        }

        private void terminate(WebSocket webSocket) {
            if (terminal.compareAndSet(false, true)) {
                delegate.set(null);
                java.util.function.Consumer<WebSocket> callback =
                        terminalCallback.getAndSet(null);
                if (callback != null) {
                    callback.accept(webSocket);
                }
            }
        }

        private void clear() {
            delegate.set(null);
            terminalCallback.set(null);
        }
    }

    private static final class DirectProxySelector extends ProxySelector {

        @Override
        public List<Proxy> select(URI uri) {
            Objects.requireNonNull(uri, "uri");
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(
                URI uri,
                SocketAddress socketAddress,
                IOException failure
        ) {
            Objects.requireNonNull(uri, "uri");
            Objects.requireNonNull(socketAddress, "socketAddress");
            Objects.requireNonNull(failure, "failure");
        }
    }

    private static void abortQuietly(WebSocket webSocket) {
        if (webSocket == null) {
            return;
        }
        try {
            webSocket.abort();
        } catch (VirtualMachineError | ThreadDeath fatal) {
            throw fatal;
        } catch (RuntimeException ignored) {
            // The transport may already be closed; abort remains best-effort.
        }
    }

    private static Throwable firstFailure(Throwable current, Throwable candidate) {
        return current != null ? current : candidate;
    }

    private static void rethrowFatal(Throwable failure) {
        if (failure instanceof VirtualMachineError fatal) {
            throw fatal;
        }
        if (failure instanceof ThreadDeath fatal) {
            throw fatal;
        }
    }
}
