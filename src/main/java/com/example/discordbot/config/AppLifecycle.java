package com.example.discordbot.config;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;

/** Application start-up and shutdown. Later tasks extend it with the object graph. */
@WebListener
public class AppLifecycle implements ServletContextListener {

    private static final System.Logger LOG = System.getLogger(AppLifecycle.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent event) {
        LOG.log(System.Logger.Level.INFO, "discord-bot started");
    }
}
