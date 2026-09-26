package com.example.discordbot.interactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.persistence.Plan;
import com.example.discordbot.persistence.Planner;
import com.example.discordbot.persistence.Recorder;
import com.example.discordbot.security.SignatureVerifier;
import com.example.discordbot.support.FakeRecorder;
import com.example.discordbot.support.TestKeys;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InteractionHandlerTest {

    private static final long NOW = 1_700_000_000L;
    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    private static final String PING = "{\"type\":1}";
    private static final String COMMAND = """
            {"id":"77","type":2,"token":"tok","application_id":"app","guild_id":"g1","channel_id":"c1",
             "member":{"user":{"id":"u1","username":"alice"}},"data":{"name":"status"}}""";

    private final TestKeys keys = new TestKeys();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final List<String> started = new CopyOnWriteArrayList<>();
    private FakeRecorder recorder;
    private InteractionHandler handler;

    @BeforeEach
    void setUp() {
        recorder = new FakeRecorder();
        handler = handlerWith(recorder, id -> started.add(id), 300, 100);
    }

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    private InteractionHandler handlerWith(Recorder rec, ActionStarter starter, long deadlineMs, long graceMs) {
        Planner planner = (i, s) -> new Plan("handled", false, List.of());
        RecordGate gate = new RecordGate(rec, Duration.ofMillis(deadlineMs), Duration.ofMillis(graceMs), executor);
        return new InteractionHandler(new SignatureVerifier(keys.publicKeyHex(), CLOCK), gate, planner, starter);
    }

    private InteractionHandler.HandlerResponse send(String body) {
        String ts = Long.toString(NOW);
        return handler.handle(keys.sign(ts, body), ts, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void invalidSignatureIsRejectedWithNoSideEffects() {
        String ts = Long.toString(NOW);
        InteractionHandler.HandlerResponse r =
                handler.handle("00".repeat(64), ts, COMMAND.getBytes(StandardCharsets.UTF_8));
        assertEquals(401, r.status());
        assertEquals(0, recorder.calls.get(), "nothing may reach the store");
        assertNull(r.afterResponse());
    }

    @Test
    void staleTimestampIsRejectedEvenWithAGenuineSignature() {
        String old = Long.toString(NOW - 60);
        InteractionHandler.HandlerResponse r =
                handler.handle(keys.sign(old, COMMAND), old, COMMAND.getBytes(StandardCharsets.UTF_8));
        assertEquals(401, r.status());
        assertEquals(0, recorder.calls.get());
    }

    @Test
    void pingIsAnsweredWithPongAndTouchesNothing() {
        InteractionHandler.HandlerResponse r = send(PING);
        assertEquals(200, r.status());
        assertEquals(Responses.pong(), r.body());
        assertEquals(0, recorder.calls.get());
    }

    @Test
    void anAcceptedCommandGetsTheDeferredAcknowledgementOnlyAfterTheRecordCommits() {
        InteractionHandler.HandlerResponse r = send(COMMAND);
        assertEquals(200, r.status());
        assertEquals(Responses.deferredPrivate(), r.body());
        assertEquals(1, recorder.calls.get());
        assertTrue(recorder.committed.get(), "the record was committed before the acknowledgement was returned");
        assertTrue(started.isEmpty(), "actions must not start before the response is written");
        assertNotNull(r.afterResponse());
        r.afterResponse().run();
        assertEquals(List.of("77"), started);
    }

    @Test
    void aDuplicateGetsTheSameAcknowledgementAndStartsNothing() {
        recorder.outcome = Recorder.Outcome.DUPLICATE;
        InteractionHandler.HandlerResponse r = send(COMMAND);
        assertEquals(Responses.deferredPrivate(), r.body());
        assertNull(r.afterResponse());
        assertTrue(started.isEmpty());
    }

    @Test
    void aStoreFailureIsARefusalAndRecordsNothing() {
        recorder.failBeforePermit = new SQLException("database down");
        InteractionHandler.HandlerResponse r = send(COMMAND);
        assertEquals(Responses.tryAgain(), r.body());
        assertNull(r.afterResponse());
        assertFalse(recorder.committed.get());
    }

    @Test
    void aSlowStoreIsRefusedAndTheLateWriteNeverCommits() throws Exception {
        recorder.sleepBeforePermitMillis = 800; // far beyond the 300 ms test deadline
        InteractionHandler.HandlerResponse r = send(COMMAND);
        assertEquals(Responses.tryAgain(), r.body());
        Thread.sleep(1200);
        assertFalse(recorder.committed.get(), "the refused command must not be recorded later");
        assertTrue(started.isEmpty());
    }

    @Test
    void theAcknowledgementReturnsWhileTheDownstreamIsBlocked() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        // The starter stands in for Discord and the mirror channel: it blocks until released.
        InteractionHandler blocking = handlerWith(recorder, id -> {
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, 300, 100);
        handler = blocking;
        long begin = System.nanoTime();
        InteractionHandler.HandlerResponse r = send(COMMAND);
        long millis = (System.nanoTime() - begin) / 1_000_000;
        assertEquals(Responses.deferredPrivate(), r.body());
        assertTrue(millis < 250, "acknowledgement took " + millis + " ms; it must not wait for downstream work");
        release.countDown();
    }

    @Test
    void anUnconfirmedCommitIsAcknowledgedAndStartsTheActionsOnceWhenItLands() throws Exception {
        recorder.sleepAfterPermitMillis = 600; // permit granted at once, commit outlasts deadline + grace
        InteractionHandler.HandlerResponse r = send(COMMAND);
        assertEquals(Responses.deferredPrivate(), r.body());
        assertNull(r.afterResponse(), "no after-response work: the late callback starts the actions");
        assertTrue(started.isEmpty());
        Thread.sleep(1000);
        assertEquals(List.of("77"), started, "started exactly once, no restart or polling");
    }

    @Test
    void anUnconfirmedCommitThatFailsStartsNothing() throws Exception {
        recorder.sleepAfterPermitMillis = 600;
        recorder.failAfterPermit = new SQLException("connection lost");
        InteractionHandler.HandlerResponse r = send(COMMAND);
        assertEquals(Responses.deferredPrivate(), r.body());
        Thread.sleep(1000);
        assertTrue(started.isEmpty());
    }

    @Test
    void otherInteractionTypesAreRejected() {
        InteractionHandler.HandlerResponse r = send("{\"id\":\"5\",\"type\":3}");
        assertEquals(400, r.status());
        assertEquals(0, recorder.calls.get());
    }

    @Test
    void aSignedButMalformedBodyIsABadRequest() {
        assertEquals(400, send("not json at all").status());
        assertEquals(400, send("{\"type\":2}").status(), "a command needs an id and a name");
        assertEquals(0, recorder.calls.get());
    }
}
