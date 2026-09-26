package com.example.discordbot.security;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.annotation.WebListener;

/**
 * Hardens the session cookie (research.md R11): HttpOnly, Secure and SameSite=Lax, with a
 * 30-minute inactivity timeout. Registered by annotation so no other file needs to know about it.
 */
@WebListener
public class SessionConfig implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        ServletContext context = event.getServletContext();
        SessionCookieConfig cookie = context.getSessionCookieConfig();
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setAttribute("SameSite", "Lax");
        context.setSessionTimeout(30); // minutes
    }
}
