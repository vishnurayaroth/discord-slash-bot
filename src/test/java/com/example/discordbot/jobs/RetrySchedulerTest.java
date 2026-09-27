package com.example.discordbot.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.config.AppConfig;
import com.example.discordbot.config.Timing;
import com.example.discordbot.discord.DiscordClient;
import com.example.discordbot.discord.Interaction;
import com.example.discordbot.interactions.CommandRules;
import com.example.discordbot.persistence.ActionStore;
import com.example.discordbot.persistence.Database;
import com.example.discordbot.persistence.InteractionStore;
import com.example.discordbot.persistence.PendingAction;
import com.example.discordbot.persistence.PostgresTestSupport;
import com.example.discordbot.persistence.ServerConnectionStore;
import com.example.discordbot.support.StubServer;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Retries and recovery against a real Postgres and a stub Discord, with short delays. */
class RetrySchedulerTest {

    private Database db;
    private StubServer stub;
    private ActionStore actions;
    private InteractionStore interactions;
    private AppConfig config;
    private DiscordClient client;
    private ScheduledThreadPoolExecutor executor;

    @BeforeEach
    void setUp() throws SQLException {
        db = PostgresTestSupport.freshDatabase();
        stub = new StubServer();
        stub.route("/webhooks/", 200, "", 0);
        stub.route("/channels/", 200, "", 0);
        stub.route("/mirror/", 204, "", 0);
        try (Connection c = db.connection(); Statement st = c.createStatement()) {
            st.execute("INSERT INTO server_connection (id, guild_id, guild_name, channel_id, channel_name) "
                    + "VALUES (1, 'g1', 'Guild', 'chan1', 'reports')");
        }
        Map<String, String> env = new HashMap<>();
        env.put("DISCORD_APPLICATION_ID", "app1");
        env.put("DISCORD_PUBLIC_KEY", "k");
        env.put("DISCORD_BOT_TOKEN", "bot-secret");
        env.put("MIRROR_WEBHOOK_URL", stub.base() + "/mirror/hook/abc");
        env.put("DATABASE_URL", "jdbc:postgresql://test-host/test-db");
        env.put("DB_USER", "u");
        env.put("DB_PASSWORD", "p");
        env.put("ADMIN_USERNAME", "a");
        env.put("ADMIN_PASSWORD_HASH", "h");
        config = AppConfig.from(env::get);
        actions = new ActionStore(db);
        interactions = new InteractionStore(db);
        client = new DiscordClient(stub.base(), "bot-secret", Duration.ofSeconds(1), Duration.ofSeconds(3));
        executor = new ScheduledThreadPoolExecutor(4);
        interactions.record(new Interaction("77", 2, "tok", "app1", "g1", "c1", "u1", "alice", "status", null),
                new CommandRules(), () -> true);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        stub.close();
    }

    /** Runner + scheduler with short delays and a small attempt limit. */
    private ActionDispatcher wire(int maxAttempts) {
        RetryPolicy fast = new RetryPolicy(() -> 0.0, Duration.ofMillis(60), Duration.ofMillis(200),
                Duration.ofMillis(60), Duration.ofMillis(200), maxAttempts);
        ActionRunner runner = new ActionRunner(actions, interactions, new ServerConnectionStore(db), client, config,
                fast, Clock.systemUTC());
        RetryScheduler scheduler = new RetryScheduler(actions, runner, executor, Clock.systemUTC());
        runner.setRetrier(scheduler);
        return new ActionDispatcher(actions, runner, executor, Timing.FIRST_REPLY_DELAY);
    }

    private PendingAction mirror() throws SQLException {
        return actions.loadByInteraction("77").stream().filter(a -> a.kind().equals("mirror")).findFirst().orElseThrow();
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within " + timeoutMillis + " ms");
    }

    private boolean mirrorStatusIs(String status) {
        try {
            return status.equals(mirror().status());
        } catch (SQLException e) {
            return false;
        }
    }

    /** SC-004: a mirror that fails and then recovers is delivered automatically, with attempts recorded. */
    @Test
    void aMirrorThatFailsTwiceThenRecoversEndsSucceededWithTheAttemptCountRecorded() throws Exception {
        StubServer.Route route = stub.route("/mirror/", 503, "", 0);
        wire(20).start("77");
        await(() -> stub.hits("/mirror/").size() >= 2, 5000);
        route.status = 204; // the channel is back
        await(() -> mirrorStatusIs("succeeded"), 5000);
        assertEquals(3, mirror().attempts(), "two failures and one success");
        assertEquals(3, stub.hits("/mirror/").size());
    }

    @Test
    void exhaustingTheRetryLimitMarksItFailedWithTheLastError() throws Exception {
        stub.route("/mirror/", 503, "", 0);
        wire(3).start("77");
        await(() -> mirrorStatusIs("failed"), 6000);
        PendingAction mirror = mirror();
        assertEquals(3, mirror.attempts());
        assertEquals(3, stub.hits("/mirror/").size(), "no attempt beyond the limit");
        try (Connection c = db.connection();
                java.sql.PreparedStatement ps = c.prepareStatement("SELECT last_error FROM actions WHERE id = ?")) {
            ps.setLong(1, mirror.id());
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                rs.next();
                assertEquals("retry limit reached (last error: HTTP 503)", rs.getString(1));
            }
        }
    }

    /** FR-016: work accepted before a restart is finished afterwards. */
    @Test
    void aNewSchedulerFinishesPendingWorkAfterARestart() throws Exception {
        // "Before the restart": nothing was started, so all three actions are still pending in the database.
        assertEquals(2, actions.pending().size(), "status has a reply and a mirror");

        // "After the restart": a brand-new runner and scheduler run the recovery scan.
        RetryPolicy fast = new RetryPolicy(() -> 0.0, Duration.ofMillis(60), Duration.ofMillis(200),
                Duration.ofMillis(60), Duration.ofMillis(200), 5);
        ActionRunner runner = new ActionRunner(new ActionStore(db), new InteractionStore(db),
                new ServerConnectionStore(db), client, config, fast, Clock.systemUTC());
        RetryScheduler scheduler = new RetryScheduler(new ActionStore(db), runner, executor, Clock.systemUTC());
        assertEquals(2, scheduler.recover());

        await(() -> {
            try {
                return actions.pending().isEmpty();
            } catch (SQLException e) {
                return false;
            }
        }, 5000);
        assertEquals(1, stub.hits("/mirror/").size());
        assertEquals(1, stub.hits("/webhooks/").size());
    }

    /** Research R4: an idle scheduler must not query the database, or Neon would never sleep. */
    @Test
    void anIdleSchedulerMakesNoDatabaseQueries() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        DataSource real = PostgresTestSupport.dataSource();
        DataSource counting = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getConnection")) {
                        connections.incrementAndGet();
                    }
                    try {
                        return method.invoke(real, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
        Database countingDb = new Database(counting);
        ActionStore countedActions = new ActionStore(countingDb);
        ActionRunner runner = new ActionRunner(countedActions, new InteractionStore(countingDb),
                new ServerConnectionStore(countingDb), client, config, ActionRunner.simpleHandler(), Clock.systemUTC());
        new RetryScheduler(countedActions, runner, executor, Clock.systemUTC());
        Thread.sleep(700);
        assertEquals(0, connections.get(), "no periodic polling: idle means zero queries");
    }
}
