package com.example.discordbot.persistence;

import com.example.discordbot.config.AppConfig;
import com.example.discordbot.config.Timing;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Connection pool and the one shared place that bounds every statement (Principle III).
 *
 * <p>The pool settings exist to let the free Neon database go idle: no minimum idle
 * connections, no keep-alive, and no failure at start-up when the database is asleep
 * (research.md R4, R8).
 */
public final class Database implements AutoCloseable {

    private static final System.Logger LOG = System.getLogger(Database.class.getName());

    private final DataSource dataSource;
    private final HikariDataSource pool;
    private volatile boolean schemaReady;
    private volatile boolean closed;

    /** Production: a HikariCP pool from the environment configuration. */
    public Database(AppConfig config) {
        this.pool = new HikariDataSource(hikariConfig(config.databaseUrl(), config.dbUser(), config.dbPassword()));
        this.dataSource = pool;
    }

    /** Tests: any DataSource. */
    public Database(DataSource dataSource) {
        this.pool = null;
        this.dataSource = dataSource;
    }

    /** The pool settings (a test asserts them so nothing silently keeps Neon awake). */
    public static HikariConfig hikariConfig(String url, String user, String password) {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(url);
        // Under Tomcat's webapp class loader DriverManager does not find the driver on its own
        // ("No suitable driver"), so it is named explicitly.
        c.setDriverClassName("org.postgresql.Driver");
        c.setUsername(user);
        c.setPassword(password);
        c.setMaximumPoolSize(4);
        c.setMinimumIdle(0);
        c.setIdleTimeout(60_000);
        c.setMaxLifetime(600_000);
        c.setConnectionTimeout(Timing.DB_CONNECTION_WAIT_MS);
        c.setInitializationFailTimeout(-1);
        // Must be set explicitly: HikariCP 7's default keepaliveTime is 2 minutes (found by
        // DatabaseConfigTest), and a keep-alive would hold Neon awake.
        c.setKeepaliveTime(0);
        c.setPoolName("discord-bot");
        return c;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    /** The only way stores create statements: applies the shared statement limit. */
    public PreparedStatement prepare(Connection connection, String sql) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        statement.setQueryTimeout(Timing.DB_STATEMENT_LIMIT_SECONDS);
        return statement;
    }

    public boolean schemaReady() {
        return schemaReady;
    }

    /** Creates the tables if missing. Idempotent. */
    public void applySchema() throws SQLException {
        String script = readSchema();
        try (Connection c = connection(); Statement st = c.createStatement()) {
            st.setQueryTimeout(Timing.DB_SCHEMA_LIMIT_SECONDS);
            for (String sql : script.split(";\\s*\\n")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
        }
        schemaReady = true;
    }

    /** Applies the schema in the background, retrying until it succeeds (the database may be asleep). */
    public void startSchemaInit() {
        Thread t = new Thread(() -> {
            int attempt = 0;
            while (!schemaReady && !closed) {
                try {
                    applySchema();
                    LOG.log(System.Logger.Level.INFO, "database schema ready");
                } catch (Exception e) {
                    // Only the class name is logged: messages may carry the database address.
                    LOG.log(System.Logger.Level.WARNING,
                            "database schema not ready yet ({0}), will retry", e.getClass().getSimpleName());
                    try {
                        Thread.sleep(Math.min(30_000L, 2_000L * (++attempt)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "schema-init");
        t.setDaemon(true);
        t.start();
    }

    private static String readSchema() throws SQLException {
        try (InputStream in = Database.class.getResourceAsStream("/db/schema.sql")) {
            if (in == null) {
                throw new SQLException("schema.sql not found on the classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new SQLException("could not read schema.sql");
        }
    }

    @Override
    public void close() {
        closed = true;
        if (pool != null) {
            pool.close();
        }
    }
}
