package com.example.discordbot.config;

import com.example.discordbot.discord.Interaction;
import com.example.discordbot.interactions.ActionStarter;
import com.example.discordbot.interactions.InteractionHandler;
import com.example.discordbot.interactions.RecordGate;
import com.example.discordbot.persistence.Database;
import com.example.discordbot.persistence.InteractionStore;
import com.example.discordbot.persistence.NewAction;
import com.example.discordbot.persistence.Plan;
import com.example.discordbot.persistence.Planner;
import com.example.discordbot.persistence.Snapshot;
import com.example.discordbot.security.SignatureVerifier;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Application start-up and shutdown: the composition root. Configuration is read once and
 * start-up fails fast, naming missing variables (never values), if any is absent.
 */
@WebListener
public class AppLifecycle implements ServletContextListener {

    private static final System.Logger LOG = System.getLogger(AppLifecycle.class.getName());

    private Database database;
    private ThreadPoolExecutor gateExecutor;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        LOG.log(System.Logger.Level.INFO, "discord-bot starting");
        AppConfig config;
        try {
            config = AppConfig.fromEnv();
        } catch (IllegalStateException e) {
            LOG.log(System.Logger.Level.ERROR, e.getMessage()); // names variables, never values
            throw e;
        }
        ServletContext context = event.getServletContext();

        database = new Database(config);
        database.startSchemaInit();
        InteractionStore store = new InteractionStore(database);

        gateExecutor = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                daemonThreads("record-gate"), new ThreadPoolExecutor.AbortPolicy());
        RecordGate gate = new RecordGate(store, Timing.RECORD_DEADLINE, Timing.COMMIT_GRACE, gateExecutor);

        // Until User Story 1 adds the real command rules, every command is "unsupported".
        Planner planner = AppLifecycle::unsupported;
        ActionStarter starter = interactionId -> { };
        SignatureVerifier verifier = new SignatureVerifier(config.publicKeyHex(), Clock.systemUTC());

        Services.put(context, AppConfig.class, config);
        Services.put(context, Database.class, database);
        Services.put(context, InteractionHandler.class, new InteractionHandler(verifier, gate, planner, starter));
        LOG.log(System.Logger.Level.INFO, "discord-bot started");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (gateExecutor != null) {
            gateExecutor.shutdownNow();
        }
        if (database != null) {
            database.close();
        }
    }

    private static Plan unsupported(Interaction interaction, Snapshot snapshot) {
        return new Plan("unsupported", false, List.of(new NewAction("reply", "That command is not supported.")));
    }

    static ThreadFactory daemonThreads(String prefix) {
        int[] counter = {0};
        return runnable -> {
            Thread t = new Thread(runnable, prefix + "-" + (++counter[0]));
            t.setDaemon(true);
            return t;
        };
    }
}
