package top.sywyar.pixivdownload.core.db.provider;

import top.sywyar.pixivdownload.plugin.api.database.DatabaseConnectionTarget;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ParameterMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.Statement;
import java.util.function.BooleanSupplier;

/** 已借出的 JDBC 对象仍受原 provider 状态约束；失效后只允许释放资源和回滚。 */
public final class DatabaseConnectionLease implements InvocationHandler {
    private static final Class<?>[] VALUE_TYPES = {java.sql.NClob.class, java.sql.Clob.class,
            java.sql.Blob.class, java.sql.SQLXML.class, java.sql.Array.class, java.sql.Ref.class};
    private final Object delegate;
    private final BooleanSupplier available;
    private Connection connection;
    private final Statement statement;
    private volatile boolean closed;

    private DatabaseConnectionLease(Object delegate, BooleanSupplier available,
                                    Connection connection, Statement statement) {
        this.delegate = delegate;
        this.available = available;
        this.connection = connection;
        this.statement = statement;
    }

    public static Connection wrap(Connection delegate, BooleanSupplier available) throws SQLException {
        if (delegate == null) throw unavailable();
        try {
            if (!available.getAsBoolean()) throw unavailable();
            var lease = new DatabaseConnectionLease(delegate, available, null, null);
            var connection = (Connection) lease.proxy(Connection.class);
            lease.connection = connection;
            return connection;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                delegate.close();
            } catch (VirtualMachineError | ThreadDeath fatal) {
                if (fatal != failure) fatal.addSuppressed(failure);
                throw fatal;
            } catch (SQLException | RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** 方言事务准备可使用驱动 API；普通调用方不能通过 unwrap 取得未门控的连接。 */
    public static void prepareTransaction(Connection connection, DatabaseConnectionTarget target,
                                          boolean readOnly) throws SQLException {
        connection = org.springframework.jdbc.datasource.DataSourceUtils.getTargetConnection(connection);
        if (Proxy.isProxyClass(connection.getClass())
                && Proxy.getInvocationHandler(connection) instanceof DatabaseConnectionLease lease) {
            lease.requireAvailable();
            target.prepareTransaction((Connection) lease.delegate, readOnly);
        } else {
            target.prepareTransaction(connection, readOnly);
        }
    }

    @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        String name = method.getName();
        if (method.getDeclaringClass() == Object.class) {
            return switch (name) {
                case "toString" -> "DatabaseConnectionLease";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> throw new IllegalStateException(name);
            };
        }
        var root = root();
        if (name.equals("isClosed") && root.closed) return true;
        if (name.equals("close") || name.equals("free")) {
            if (root.closed || !root.available.getAsBoolean()) {
                root.discard();
                return null;
            }
            if (delegate instanceof Connection) root.closed = true;
        }
        if (!name.equals("close") && !name.equals("free") && !name.equals("rollback") && !name.equals("abort") && !name.equals("isClosed")) {
            requireAvailable();
        }
        if (name.equals("unwrap")) {
            Class<?> type = (Class<?>) args[0];
            if (type.isInstance(proxy)) return proxy;
            throw new SQLException("DATABASE_UNWRAP_DENIED", "08004");
        }
        if (name.equals("isWrapperFor")) return ((Class<?>) args[0]).isInstance(proxy);
        if (name.equals("getConnection") && (delegate instanceof Statement || delegate instanceof DatabaseMetaData)) {
            return connection;
        }
        if (name.equals("getStatement") && delegate instanceof ResultSet) return statement;
        if (name.equals("abort")) root.closed = true;
        Object result;
        try {
            result = method.invoke(delegate, driverArguments(args));
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
        if (result instanceof Statement next && Statement.class.isAssignableFrom(method.getReturnType())) {
            Class<?> type = next instanceof CallableStatement ? CallableStatement.class
                    : next instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
            return new DatabaseConnectionLease(next, available, connection, null).proxy(type);
        }
        if (result instanceof ResultSet rows && method.getReturnType().isAssignableFrom(ResultSet.class)) {
            return new DatabaseConnectionLease(rows, available, connection,
                    proxy instanceof Statement current ? current : statement).proxy(ResultSet.class);
        }
        if (result instanceof DatabaseMetaData metadata) {
            return new DatabaseConnectionLease(metadata, available, connection, null).proxy(DatabaseMetaData.class);
        }
        if (result instanceof ResultSetMetaData metadata) {
            return new DatabaseConnectionLease(metadata, available, connection, statement).proxy(ResultSetMetaData.class);
        }
        if (result instanceof ParameterMetaData metadata) {
            return new DatabaseConnectionLease(metadata, available, connection, statement).proxy(ParameterMetaData.class);
        }
        for (Class<?> type : VALUE_TYPES) {
            if (type.isInstance(result) && method.getReturnType().isAssignableFrom(type)) {
                return new DatabaseConnectionLease(result, available, connection, statement).proxy(type);
            }
        }
        if (result instanceof java.io.InputStream stream) {
            return new java.io.FilterInputStream(stream) {
                @Override public int read() throws java.io.IOException { requireIoAvailable(); return in.read(); }
                @Override public int read(byte[] bytes, int offset, int length) throws java.io.IOException {
                    requireIoAvailable(); return in.read(bytes, offset, length);
                }
                @Override public long skip(long count) throws java.io.IOException { requireIoAvailable(); return in.skip(count); }
                @Override public int available() throws java.io.IOException { requireIoAvailable(); return in.available(); }
                @Override public void reset() throws java.io.IOException { requireIoAvailable(); in.reset(); }
                @Override public void close() throws java.io.IOException {
                    if (!root().closed && available.getAsBoolean()) in.close(); else discardIo();
                }
            };
        }
        if (result instanceof java.io.OutputStream stream) {
            return new java.io.FilterOutputStream(stream) {
                @Override public void write(int value) throws java.io.IOException { requireIoAvailable(); out.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws java.io.IOException {
                    requireIoAvailable(); out.write(bytes, offset, length);
                }
                @Override public void flush() throws java.io.IOException { requireIoAvailable(); out.flush(); }
                @Override public void close() throws java.io.IOException {
                    if (!root().closed && available.getAsBoolean()) out.close(); else discardIo();
                }
            };
        }
        if (result instanceof java.io.Reader reader) {
            return new java.io.FilterReader(reader) {
                @Override public int read() throws java.io.IOException { requireIoAvailable(); return in.read(); }
                @Override public int read(char[] chars, int offset, int length) throws java.io.IOException {
                    requireIoAvailable(); return in.read(chars, offset, length);
                }
                @Override public long skip(long count) throws java.io.IOException { requireIoAvailable(); return in.skip(count); }
                @Override public boolean ready() throws java.io.IOException { requireIoAvailable(); return in.ready(); }
                @Override public void reset() throws java.io.IOException { requireIoAvailable(); in.reset(); }
                @Override public void close() throws java.io.IOException {
                    if (!root().closed && available.getAsBoolean()) in.close(); else discardIo();
                }
            };
        }
        if (result instanceof java.io.Writer writer) {
            return new java.io.FilterWriter(writer) {
                @Override public void write(int value) throws java.io.IOException { requireIoAvailable(); out.write(value); }
                @Override public void write(char[] chars, int offset, int length) throws java.io.IOException {
                    requireIoAvailable(); out.write(chars, offset, length);
                }
                @Override public void write(String text, int offset, int length) throws java.io.IOException {
                    requireIoAvailable(); out.write(text, offset, length);
                }
                @Override public void flush() throws java.io.IOException { requireIoAvailable(); out.flush(); }
                @Override public void close() throws java.io.IOException {
                    if (!root().closed && available.getAsBoolean()) out.close(); else discardIo();
                }
            };
        }
        return result;
    }

    private Object[] driverArguments(Object[] args) throws SQLException {
        if (args == null) return null;
        Object[] resolved = args;
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            if (value != null && Proxy.isProxyClass(value.getClass())
                    && Proxy.getInvocationHandler(value) instanceof DatabaseConnectionLease lease) {
                lease.requireAvailable();
                if (lease.root() != root()) throw new SQLException("DATABASE_OWNER_MISMATCH", "08004");
                if (resolved == args) resolved = args.clone();
                // 驱动需要自己的 Array / LOB 类型；只在同一有效租约内部还原，不向调用方暴露。
                resolved[i] = lease.delegate;
            }
        }
        return resolved;
    }

    private void requireIoAvailable() throws java.io.IOException {
        try { requireAvailable(); }
        catch (SQLException failure) { throw new java.io.IOException("DATABASE_UNAVAILABLE"); }
    }

    private void discardIo() throws java.io.IOException {
        // pgjdbc 的 Blob.free 也会刷新写流；先结束连接，不再调用旧 LOB 的任何释放方法。
        try { root().discard(); }
        catch (SQLException failure) { throw new java.io.IOException("DATABASE_UNAVAILABLE"); }
    }

    private DatabaseConnectionLease root() {
        return (DatabaseConnectionLease) Proxy.getInvocationHandler(connection);
    }

    private synchronized void discard() throws SQLException {
        if (closed) return;
        closed = true;
        Connection physical = (Connection) delegate;
        try {
            physical.abort(Runnable::run);
        } catch (SQLException unsupported) {
            // close 仍回滚并释放租约；不再把旧 LOB 方法转交给驱动。
        } finally {
            try {
                physical.close();
            } catch (SQLException failure) {
                // 池关闭时可能再次回滚已 abort 的物理连接；只接受已确认关闭的 08003。
                if (!"08003".equals(failure.getSQLState()) || !physical.isClosed()) throw failure;
            }
        }
    }

    private Object proxy(Class<?> type) {
        return Proxy.newProxyInstance(DatabaseConnectionLease.class.getClassLoader(), new Class<?>[]{type}, this);
    }

    private void requireAvailable() throws SQLException {
        var root = root();
        if (root.closed || !available.getAsBoolean()) {
            root.discard();
            throw unavailable();
        }
    }

    private static SQLException unavailable() {
        return new SQLNonTransientConnectionException("DATABASE_UNAVAILABLE", "08003");
    }
}
