package com.example.discordbot.dashboard;

import com.example.discordbot.config.Services;
import com.example.discordbot.persistence.InteractionStore;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.sql.SQLException;

/** GET /api/log: the newest log entries as JSON. Behind the sign-in filter. */
@WebServlet("/api/log")
public class LogApiServlet extends HttpServlet {

    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        InteractionStore store = Services.get(getServletContext(), InteractionStore.class);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        if (store == null) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.getWriter().write("{\"error\":\"unavailable\"}");
            return;
        }
        try {
            response.getWriter().write(LogView.toJson(store.latest(limit(request.getParameter("limit")))));
        } catch (SQLException e) {
            response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            response.getWriter().write("{\"error\":\"database unavailable\"}");
        }
    }

    static int limit(String raw) {
        if (raw == null) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(raw.trim())));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }
}
