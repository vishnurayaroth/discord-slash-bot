package com.example.discordbot.dashboard;

import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Keep-warm and Render health check. Answers GET and HEAD (HttpServlet derives HEAD from GET)
 * with 200 "ok". It never touches the database, so pinging it cannot wake Neon (research.md R4).
 */
@WebServlet("/health")
public class HealthServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType("text/plain");
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write("ok");
    }
}
