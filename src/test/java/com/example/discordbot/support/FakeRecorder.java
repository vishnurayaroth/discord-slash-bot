package com.example.discordbot.support;

import com.example.discordbot.discord.Interaction;
import com.example.discordbot.persistence.CommitPermit;
import com.example.discordbot.persistence.Planner;
import com.example.discordbot.persistence.Recorder;
import com.example.discordbot.persistence.Snapshot;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A controllable Recorder: sleeps, fails, or reports a duplicate on demand, and tracks commits. */
public class FakeRecorder implements Recorder {

    public volatile Outcome outcome = Outcome.RECORDED;
    public volatile long sleepBeforePermitMillis = 0;
    public volatile long sleepAfterPermitMillis = 0;
    public volatile SQLException failBeforePermit;
    public volatile SQLException failAfterPermit;

    public final AtomicInteger calls = new AtomicInteger();
    /** True only once the fake has "committed" (after being granted the permit). */
    public final AtomicBoolean committed = new AtomicBoolean();
    public volatile Snapshot lastSnapshot;

    @Override
    public Outcome record(Interaction interaction, Planner planner, CommitPermit permit) throws SQLException {
        calls.incrementAndGet();
        sleep(sleepBeforePermitMillis);
        if (failBeforePermit != null) {
            throw failBeforePermit;
        }
        lastSnapshot = new Snapshot("g1", "c1", Boolean.TRUE, "ok");
        planner.plan(interaction, lastSnapshot);
        if (outcome == Outcome.DUPLICATE) {
            return Outcome.DUPLICATE;
        }
        if (!permit.tryBeginCommit()) {
            return Outcome.ABANDONED;
        }
        sleep(sleepAfterPermitMillis);
        if (failAfterPermit != null) {
            throw failAfterPermit;
        }
        committed.set(true);
        return outcome;
    }

    private static void sleep(long millis) {
        if (millis > 0) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
