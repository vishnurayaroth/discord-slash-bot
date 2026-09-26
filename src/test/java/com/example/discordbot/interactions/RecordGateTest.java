package com.example.discordbot.interactions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.discord.Interaction;
import com.example.discordbot.persistence.Plan;
import com.example.discordbot.persistence.Planner;
import com.example.discordbot.persistence.Recorder;
import com.example.discordbot.support.FakeRecorder;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RecordGateTest {

    private static final Interaction INTERACTION =
            new Interaction("id-1", 2, "tok", "app", "g1", "c1", "u1", "alice", "status", null);
    private static final Planner PLANNER = (i, s) -> new Plan("handled", false, List.of());

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void stop() {
        executor.shutdownNow();
    }

    private RecordGate gate(Recorder recorder, long deadlineMs, long graceMs) {
        return new RecordGate(recorder, Duration.ofMillis(deadlineMs), Duration.ofMillis(graceMs), executor);
    }

    private static RecordGate.Result run(RecordGate gate, List<String> lateCalls) {
        return gate.record(INTERACTION, PLANNER, lateCalls::add, System.nanoTime());
    }

    @Test
    void aFastRecordIsAccepted() {
        FakeRecorder recorder = new FakeRecorder();
        assertEquals(RecordGate.Result.ACCEPTED, run(gate(recorder, 1000, 200), new CopyOnWriteArrayList<>()));
        assertTrue(recorder.committed.get());
    }

    @Test
    void aDuplicateIsReportedAsSuch() {
        FakeRecorder recorder = new FakeRecorder();
        recorder.outcome = Recorder.Outcome.DUPLICATE;
        assertEquals(RecordGate.Result.DUPLICATE, run(gate(recorder, 1000, 200), new CopyOnWriteArrayList<>()));
        assertFalse(recorder.committed.get());
    }

    @Test
    void deadlineBeforeCommitRefusesAndTheLateWriteIsRolledBack() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        recorder.sleepBeforePermitMillis = 400; // the "database" is slow to reach the commit
        RecordGate.Result result = run(gate(recorder, 100, 200), new CopyOnWriteArrayList<>());
        assertEquals(RecordGate.Result.REFUSED, result);
        Thread.sleep(700); // let the slow write reach its permit request
        assertFalse(recorder.committed.get(), "a refused command must never be committed later");
    }

    @Test
    void commitInFlightThatFinishesWithinTheGraceIsAccepted() {
        FakeRecorder recorder = new FakeRecorder();
        recorder.sleepAfterPermitMillis = 150; // permit granted immediately, commit takes 150 ms
        assertEquals(RecordGate.Result.ACCEPTED, run(gate(recorder, 60, 500), new CopyOnWriteArrayList<>()));
        assertTrue(recorder.committed.get());
    }

    @Test
    void graceExpiringFirstIsUnconfirmedAndALateRecordCallsBackExactlyOnce() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        recorder.sleepAfterPermitMillis = 500;
        List<String> late = new CopyOnWriteArrayList<>();
        assertEquals(RecordGate.Result.UNCONFIRMED, run(gate(recorder, 60, 60), late));
        assertTrue(late.isEmpty(), "the callback must wait for the commit to land");
        Thread.sleep(900);
        assertEquals(List.of("id-1"), late);
    }

    @Test
    void anUnconfirmedCommitThatLaterFailsCallsNothing() throws Exception {
        FakeRecorder recorder = new FakeRecorder();
        recorder.sleepAfterPermitMillis = 300;
        recorder.failAfterPermit = new SQLException("connection lost");
        List<String> late = new CopyOnWriteArrayList<>();
        assertEquals(RecordGate.Result.UNCONFIRMED, run(gate(recorder, 40, 40), late));
        Thread.sleep(600);
        assertTrue(late.isEmpty());
    }

    @Test
    void anUnconfirmedDuplicateCallsNothing() throws Exception {
        // The permit is granted, then the "commit" turns out to be a duplicate: no callback.
        Recorder recorder = (i, p, permit) -> {
            permit.tryBeginCommit();
            try {
                Thread.sleep(300);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return Recorder.Outcome.DUPLICATE;
        };
        List<String> late = new CopyOnWriteArrayList<>();
        assertEquals(RecordGate.Result.UNCONFIRMED, run(gate(recorder, 40, 40), late));
        Thread.sleep(600);
        assertTrue(late.isEmpty());
    }

    @Test
    void aFailureBeforeTheCommitIsARefusal() {
        FakeRecorder recorder = new FakeRecorder();
        recorder.failBeforePermit = new SQLException("database down");
        assertEquals(RecordGate.Result.REFUSED, run(gate(recorder, 1000, 200), new CopyOnWriteArrayList<>()));
        assertFalse(recorder.committed.get());
    }

    @Test
    void aSaturatedExecutorRefusesInsteadOfQueueingForever() throws Exception {
        ThreadPoolExecutor tiny = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch release = new CountDownLatch(1);
        Recorder blocked = (i, p, permit) -> {
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            return Recorder.Outcome.ABANDONED;
        };
        RecordGate gate = new RecordGate(blocked, Duration.ofMillis(50), Duration.ofMillis(50), tiny);
        gate.record(INTERACTION, PLANNER, id -> { }, System.nanoTime()); // occupies the thread
        gate.record(INTERACTION, PLANNER, id -> { }, System.nanoTime()); // fills the queue
        assertEquals(RecordGate.Result.REFUSED, gate.record(INTERACTION, PLANNER, id -> { }, System.nanoTime()));
        release.countDown();
        tiny.shutdownNow();
    }

    /** Exactly one side wins: refused means never committed; accepted or unconfirmed means committed. */
    @Test
    void exactlyOneSideWinsUnderARacingLoop() throws Exception {
        for (int i = 0; i < 150; i++) {
            long jitter = i % 7; // the "commit" lands around the 3 ms deadline
            CountDownLatch finished = new CountDownLatch(1);
            FakeRecorder recorder = new FakeRecorder() {
                @Override
                public Outcome record(Interaction in, Planner p, com.example.discordbot.persistence.CommitPermit permit)
                        throws SQLException {
                    try {
                        return super.record(in, p, permit);
                    } finally {
                        finished.countDown();
                    }
                }
            };
            recorder.sleepBeforePermitMillis = jitter;
            RecordGate.Result result = run(gate(recorder, 3, 25), new CopyOnWriteArrayList<>());
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            if (result == RecordGate.Result.REFUSED) {
                assertFalse(recorder.committed.get(), "iteration " + i + ": refused but committed");
            } else {
                assertTrue(recorder.committed.get(), "iteration " + i + ": " + result + " but not committed");
            }
        }
    }
}
