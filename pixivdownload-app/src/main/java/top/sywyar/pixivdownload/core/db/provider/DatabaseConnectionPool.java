package top.sywyar.pixivdownload.core.db.provider;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import org.springframework.jdbc.datasource.DelegatingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.HashSet;
import java.util.Set;

/** 物理连接从提供者返回起归池所有，包括尚未完成池初始化的连接。 */
public final class DatabaseConnectionPool extends HikariDataSource {
    private final Connections connections;

    private DatabaseConnectionPool(HikariConfig config, Connections connections) {
        super(config);
        this.connections = connections;
    }

    public static HikariDataSource create(HikariConfig config) {
        var connections = new Connections(config.getDataSource());
        config.setDataSource(connections);
        try {
            return new DatabaseConnectionPool(config, connections);
        } catch (RuntimeException | Error failure) {
            Throwable primary = failure instanceof PoolInitializationException
                    && (failure.getCause() instanceof VirtualMachineError || failure.getCause() instanceof ThreadDeath)
                    ? failure.getCause() : failure;
            try { connections.close(); }
            catch (VirtualMachineError | ThreadDeath fatal) {
                if (fatal != primary) fatal.addSuppressed(primary);
                throw fatal;
            }
            catch (RuntimeException | Error cleanup) { if (cleanup != primary) primary.addSuppressed(cleanup); }
            if (primary instanceof Error error) throw error;
            throw (RuntimeException) primary;
        }
    }

    @Override public synchronized void close() {
        connections.stop();
        try { super.close(); }
        catch (RuntimeException | Error failure) {
            try { connections.close(); }
            catch (VirtualMachineError | ThreadDeath fatal) {
                if (fatal != failure) fatal.addSuppressed(failure);
                throw fatal;
            }
            catch (RuntimeException | Error cleanup) { if (cleanup != failure) failure.addSuppressed(cleanup); }
            throw failure;
        }
        connections.close();
    }

    private static final class Connections extends DelegatingDataSource {
        private final Set<TrackedConnection> open = new HashSet<>();
        private boolean closed;

        private Connections(DataSource source) { super(source); }

        private synchronized void stop() { closed = true; }

        @Override public Connection getConnection() throws SQLException {
            synchronized (this) { requireOpen(); }
            Connection connection = super.getConnection();
            try {
                var tracked = new TrackedConnection(connection, this);
                Connection proxy = tracked.proxy();
                synchronized (this) {
                    requireOpen();
                    open.add(tracked);
                }
                return proxy;
            } catch (SQLException | RuntimeException | Error failure) {
                try { connection.close(); }
                catch (VirtualMachineError | ThreadDeath fatal) {
                    if (fatal != failure) fatal.addSuppressed(failure);
                    throw fatal;
                }
                catch (SQLException | RuntimeException | Error cleanup) {
                    if (cleanup != failure) failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }

        private void requireOpen() throws SQLException {
            if (closed) throw new SQLNonTransientConnectionException("DATABASE_UNAVAILABLE", "08003");
        }

        private synchronized void released(TrackedConnection connection) { open.remove(connection); }

        private void close() {
            TrackedConnection[] remaining;
            synchronized (this) {
                closed = true;
                remaining = open.toArray(TrackedConnection[]::new);
            }
            Throwable problem = null;
            for (var connection : remaining) {
                try { connection.close(); }
                catch (SQLException | RuntimeException | Error failure) {
                    Throwable safe = failure instanceof SQLException
                            ? new IllegalStateException("DATABASE_CONNECTION_CLOSE_FAILED") : failure;
                    if (problem == null) problem = safe;
                    else if (safe == problem) continue;
                    else if (safe instanceof VirtualMachineError || safe instanceof ThreadDeath) {
                        safe.addSuppressed(problem);
                        problem = safe;
                    } else problem.addSuppressed(safe);
                }
            }
            if (problem instanceof Error error) throw error;
            if (problem != null) throw (RuntimeException) problem;
        }
    }

    private static final class TrackedConnection {
        private final Connection delegate;
        private final Connections owner;
        private boolean closed;

        private TrackedConnection(Connection delegate, Connections owner) {
            this.delegate = delegate;
            this.owner = owner;
        }

        private synchronized void close() throws SQLException {
            if (closed) return;
            try { delegate.close(); }
            finally {
                closed = true;
                owner.released(this);
            }
        }

        private Connection proxy() {
            return (Connection) Proxy.newProxyInstance(DatabaseConnectionPool.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            return switch (method.getName()) {
                                case "equals" -> proxy == args[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> "PooledDatabaseConnection";
                                default -> throw new IllegalStateException(method.getName());
                            };
                        }
                        if (method.getName().equals("close")) {
                            close();
                            return null;
                        }
                        try { return method.invoke(delegate, args); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    });
        }
    }
}
