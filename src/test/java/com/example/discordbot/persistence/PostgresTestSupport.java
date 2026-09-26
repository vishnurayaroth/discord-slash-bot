package com.example.discordbot.persistence;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Real Postgres for persistence tests. Uses TEST_DATABASE_URL (with TEST_DATABASE_USER and
 * TEST_DATABASE_PASSWORD) when set, otherwise starts a throwaway container with Testcontainers.
 * One container is shared by every test class in the run.
 */
public final class PostgresTestSupport {

    private static PGSimpleDataSource dataSource;
    private static PostgreSQLContainer container;

    private PostgresTestSupport() {}

    public static synchronized DataSource dataSource() {
        if (dataSource == null) {
            PGSimpleDataSource ds = new PGSimpleDataSource();
            String url = System.getenv("TEST_DATABASE_URL");
            if (url != null && !url.isBlank()) {
                ds.setUrl(url);
                ds.setUser(System.getenv("TEST_DATABASE_USER"));
                ds.setPassword(System.getenv("TEST_DATABASE_PASSWORD"));
            } else {
                container = new PostgreSQLContainer("postgres:16-alpine");
                container.start();
                ds.setUrl(container.getJdbcUrl());
                ds.setUser(container.getUsername());
                ds.setPassword(container.getPassword());
            }
            dataSource = ds;
        }
        return dataSource;
    }

    /** A Database over a freshly wiped and re-created schema. */
    public static Database freshDatabase() throws SQLException {
        DataSource ds = dataSource();
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
        }
        Database db = new Database(ds);
        db.applySchema();
        return db;
    }
}
