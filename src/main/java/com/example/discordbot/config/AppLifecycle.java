package com.example.discordbot.config;

import com.example.discordbot.dashboard.ConfigService;
import com.example.discordbot.dashboard.ConnectService;
import com.example.discordbot.discord.DiscordClient;
import com.example.discordbot.interactions.CommandRules;
import com.example.discordbot.interactions.InteractionHandler;
import com.example.discordbot.interactions.RecordGate;
import com.example.discordbot.jobs.ActionDispatcher;
import com.example.discordbot.jobs.ActionRunner;
import com.example.discordbot.jobs.RetryPolicy;
import com.example.discordbot.jobs.RetryScheduler;
import com.example.discordbot.persistence.ActionStore;
import com.example.discordbot.persistence.CommandConfigStore;
import com.example.discordbot.persistence.Database;
import com.example.discordbot.persistence.InteractionStore;
import com.example.discordbot.persistence.ServerConnectionStore;
import com.example.discordbot.security.AdminAuth;
import com.example.discordbot.security.SignatureVerifier;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.time.Clock;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
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
    private ScheduledThreadPoolExecutor jobExecutor;

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
        Clock clock = Clock.systemUTC();

        database = new Database(config);
        database.startSchemaInit();
        InteractionStore interactions = new InteractionStore(database);
        ActionStore actions = new ActionStore(database);
        ServerConnectionStore connections = new ServerConnectionStore(database);
        CommandConfigStore commandConfigs = new CommandConfigStore(database);
        DiscordClient discord = new DiscordClient(config.botToken());

        // Record step: bounded pool, so a stuck database cannot pile up unbounded work.
        gateExecutor = new ThreadPoolExecutor(4, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                daemonThreads("record-gate"), new ThreadPoolExecutor.AbortPolicy());
        RecordGate gate = new RecordGate(interactions, Timing.RECORD_DEADLINE, Timing.COMMIT_GRACE, gateExecutor);

        // Follow-up work: reply, post and mirror run as independent timed tasks.
        jobExecutor = new ScheduledThreadPoolExecutor(4, daemonThreads("actions"));
        jobExecutor.setRemoveOnCancelPolicy(true);
        ActionRunner runner = new ActionRunner(actions, interactions, connections, discord, config,
                new RetryPolicy(), clock);
        ActionDispatcher dispatcher = new ActionDispatcher(actions, runner, jobExecutor, Timing.FIRST_REPLY_DELAY);
        // Retries are in-memory timers; one recovery scan at start-up finishes work accepted before a
        // restart. No periodic polling, so an idle service lets Neon sleep (research.md R4, R5).
        RetryScheduler retries = new RetryScheduler(actions, runner, jobExecutor, clock);
        runner.setRetrier(retries);
        retries.recoverWhenReady(database::schemaReady);

        SignatureVerifier verifier = new SignatureVerifier(config.publicKeyHex(), clock);
        InteractionHandler handler = new InteractionHandler(verifier, gate, new CommandRules(), dispatcher);

        Services.put(context, ConfigService.class, new ConfigService(commandConfigs));
        Services.put(context, ConnectService.class, new ConnectService(discord, connections, config.applicationId()));
        Services.put(context, AdminAuth.class, new AdminAuth(config));
        Services.put(context, AppConfig.class, config);
        Services.put(context, Database.class, database);
        Services.put(context, DiscordClient.class, discord);
        Services.put(context, CommandConfigStore.class, commandConfigs);
        Services.put(context, ServerConnectionStore.class, connections);
        Services.put(context, InteractionStore.class, interactions);
        Services.put(context, InteractionHandler.class, handler);
        LOG.log(System.Logger.Level.INFO, "discord-bot started");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (jobExecutor != null) {
            jobExecutor.shutdownNow();
        }
        if (gateExecutor != null) {
            gateExecutor.shutdownNow();
        }
        if (database != null) {
            database.close();
        }
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
