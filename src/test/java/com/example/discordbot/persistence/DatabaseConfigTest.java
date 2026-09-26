package com.example.discordbot.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.zaxxer.hikari.HikariConfig;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Guards the free-tier settings: a regression here could keep Neon awake all month or leave a
 * statement unbounded (constitution: Deployment and cost, Principle III).
 */
class DatabaseConfigTest {

    @Test
    void poolNeverRefillsItselfAndNeverKeepsTheDatabaseAwake() {
        HikariConfig c = Database.hikariConfig("jdbc:postgresql://host/db", "user", "pass");
        assertEquals("org.postgresql.Driver", c.getDriverClassName(), "found only when named, under Tomcat");
        assertEquals(4, c.getMaximumPoolSize());
        assertEquals(0, c.getMinimumIdle(), "minimumIdle > 0 makes the pool reconnect and wakes Neon");
        assertEquals(60_000, c.getIdleTimeout());
        assertEquals(600_000, c.getMaxLifetime());
        assertEquals(1_500, c.getConnectionTimeout());
        assertEquals(0, c.getKeepaliveTime(), "a keep-alive would hold the database awake");
        assertEquals(-1, c.getInitializationFailTimeout(), "start-up must not touch or fail on a sleeping database");
    }

    @Test
    void everyStatementGetsTheSharedOneSecondLimit() throws Exception {
        AtomicInteger limit = new AtomicInteger(-1);
        PreparedStatement statement = (PreparedStatement) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (proxy, method, args) -> {
                    if (method.getName().equals("setQueryTimeout")) {
                        limit.set((Integer) args[0]);
                    }
                    return null;
                });
        Connection connection = (Connection) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                    if (method.getName().equals("prepareStatement")) {
                        return statement;
                    }
                    return null;
                });
        Database db = new Database((javax.sql.DataSource) null);
        db.prepare(connection, "SELECT 1");
        assertEquals(1, limit.get());
    }
}
