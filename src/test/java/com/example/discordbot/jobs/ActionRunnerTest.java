package com.example.discordbot.jobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runner and dispatcher against a real Postgres and a stub standing in for Discord. */
class ActionRunnerTest {

    private static final String MIRROR_TOKEN = "SECRET-MIRROR-TOKEN";

    private Database db;
    private StubServer stub;
    private ActionStore actions;
    private InteractionStore interactions;
    private ActionRunner runner;
    private ActionDispatcher dispatcher;
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
        env.put("MIRROR_WEBHOOK_URL", stub.base() + "/mirror/hook/" + MIRROR_TOKEN);
        env.put("DATABASE_URL", "u");
        env.put("DB_USER", "u");
        env.put("DB_PASSWORD", "p");
        env.put("ADMIN_USERNAME", "a");
        env.put("ADMIN_PASSWORD_HASH", "h");
        AppConfig config = AppConfig.from(env::get);

        actions = new ActionStore(db);
        interactions = new InteractionStore(db);
        DiscordClient client = new DiscordClient(stub.base(), "bot-secret", Duration.ofSeconds(1), Duration.ofSeconds(3));
        runner = new ActionRunner(actions, interactions, new ServerConnectionStore(db), client, config,
                ActionRunner.simpleHandler(), Clock.systemUTC());
        executor = new ScheduledThreadPoolExecutor(4);
        dispatcher = new ActionDispatcher(actions, runner, executor, Timing.FIRST_REPLY_DELAY);
        record("77", "printer on fire, urgent");
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        stub.close();
    }

    private void record(String id, String text) throws SQLException {
        interactions.record(new Interaction(id, 2, "tok", "app1", "g1", "c1", "u1", "alice", "report", text),
                new CommandRules(), () -> true);
    }

    private PendingAction action(String interactionId, String kind) throws SQLException {
        return actions.loadByInteraction(interactionId).stream()
                .filter(a -> a.kind().equals(kind)).findFirst().orElseThrow();
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMillis;
        while (!condition.getAsBoolean() && System.currentTimeMillis() < end) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "condition not met within " + timeoutMillis + " ms");
    }

    private boolean succeeded(String id, String kind) {
        try {
            return "succeeded".equals(action(id, kind).status());
        } catch (SQLException e) {
            return false;
        }
    }

    private String status(String id, String kind) throws SQLException {
        return action(id, kind).status();
    }

    private String lastError(long actionId) throws SQLException {
        try (Connection c = db.connection();
                java.sql.PreparedStatement ps = c.prepareStatement("SELECT last_error FROM actions WHERE id = ?")) {
            ps.setLong(1, actionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    @Test
    void allThreeActionsSucceedAndUseTheRightDestinations() throws Exception {
        dispatcher.start("77");
        await(() -> succeeded("77", "reply") && succeeded("77", "post") && succeeded("77", "mirror"), 6000);

        StubServer.Hit reply = stub.hits("/webhooks/").get(0);
        assertEquals("PATCH", reply.method());
        assertEquals("/webhooks/app1/tok/messages/@original", reply.path());
        assertNull(reply.authorization());
        assertTrue(reply.body().contains("Flagged HIGH PRIORITY"));

        StubServer.Hit post = stub.hits("/channels/").get(0);
        assertEquals("/channels/chan1/messages", post.path());
        assertEquals("Bot bot-secret", post.authorization());
        assertTrue(post.body().contains("[HIGH PRIORITY] /report from alice"));

        StubServer.Hit mirror = stub.hits("/mirror/").get(0);
        assertEquals("/mirror/hook/" + MIRROR_TOKEN, mirror.path());
        assertNull(mirror.authorization());

        await(() -> {
            try {
                return countTokens() == 0;
            } catch (SQLException e) {
                return false;
            }
        }, 3000);
    }

    private long countTokens() throws SQLException {
        try (Connection c = db.connection(); Statement st = c.createStatement();
                ResultSet rs = st.executeQuery("SELECT count(*) FROM interactions WHERE interaction_token IS NOT NULL")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    @Test
    void theFirstReplyWaitsHalfASecondAfterTheAcknowledgementAndPostAndMirrorGoAtOnce() throws Exception {
        long begin = System.nanoTime();
        dispatcher.start("77");
        await(() -> succeeded("77", "reply") && succeeded("77", "post") && succeeded("77", "mirror"), 6000);

        long replyMs = (stub.hits("/webhooks/").get(0).atNanos() - begin) / 1_000_000;
        long postMs = (stub.hits("/channels/").get(0).atNanos() - begin) / 1_000_000;
        long mirrorMs = (stub.hits("/mirror/").get(0).atNanos() - begin) / 1_000_000;
        assertTrue(replyMs >= 450, "reply went out after " + replyMs + " ms; it must wait about 500 ms");
        assertTrue(postMs < replyMs && mirrorMs < replyMs, "post and mirror do not wait for the reply delay");
    }

    @Test
    void aSlowFailingMirrorNeverBlocksOrDelaysTheReply() throws Exception {
        stub.route("/mirror/", 503, "", 1500); // the mirror channel is down and slow
        dispatcher.start("77");
        await(() -> succeeded("77", "reply"), 3000);
        assertEquals("pending", status("77", "mirror"), "the mirror is still failing while the reply already succeeded");
        await(() -> {
            try {
                return action("77", "mirror").attempts() == 1;
            } catch (SQLException e) {
                return false;
            }
        }, 4000);
        PendingAction mirror = action("77", "mirror");
        assertEquals("pending", mirror.status(), "a retryable failure keeps the action pending");
        assertEquals("HTTP 503", lastError(mirror.id()));
    }

    @Test
    void aPermanentFailureMarksTheActionFailedWithASanitizedReason() throws Exception {
        stub.route("/mirror/", 404, "{\"echo\":\"" + MIRROR_TOKEN + "\",\"code\":10015}", 0);
        dispatcher.start("77");
        await(() -> {
            try {
                return "failed".equals(status("77", "mirror"));
            } catch (SQLException e) {
                return false;
            }
        }, 4000);
        PendingAction mirror = action("77", "mirror");
        String error = lastError(mirror.id());
        assertEquals("HTTP 404 code 10015", error);
        assertFalse(error.contains(MIRROR_TOKEN), "the stored error must never contain the webhook token");
        assertFalse(error.contains("127.0.0.1"));
    }

    @Test
    void runningAnAlreadyFinishedActionDoesNothing() throws Exception {
        dispatcher.start("77");
        await(() -> succeeded("77", "reply") && succeeded("77", "post") && succeeded("77", "mirror"), 6000);
        int hitsBefore = stub.allHits().size();
        for (PendingAction a : actions.loadByInteraction("77")) {
            runner.run(a.id());
        }
        assertEquals(hitsBefore, stub.allHits().size(), "a succeeded action must never be sent again");
    }

    @Test
    void aReplyWithoutATokenFailsPermanently() throws Exception {
        try (Connection c = db.connection(); Statement st = c.createStatement()) {
            st.execute("UPDATE interactions SET interaction_token = NULL WHERE id = '77'");
        }
        runner.run(action("77", "reply").id());
        PendingAction reply = action("77", "reply");
        assertEquals("failed", reply.status());
        assertEquals("interaction token unavailable", lastError(reply.id()));
        assertEquals(List.of(), stub.hits("/webhooks/"));
    }
}
