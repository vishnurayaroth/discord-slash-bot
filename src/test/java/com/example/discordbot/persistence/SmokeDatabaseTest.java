package com.example.discordbot.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

/** Proves the test database (Testcontainers or TEST_DATABASE_URL) works on this machine. */
class SmokeDatabaseTest {

    @Test
    void connectsAndRunsSelectOne() throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
                Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT 1")) {
            rs.next();
            assertEquals(1, rs.getInt(1));
        }
    }
}
