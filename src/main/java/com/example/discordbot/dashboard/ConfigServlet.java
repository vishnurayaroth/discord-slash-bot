package com.example.discordbot.dashboard;

import com.example.discordbot.config.AppConfig;
import com.example.discordbot.config.Services;
import com.example.discordbot.persistence.CommandConfig;
import com.example.discordbot.security.CsrfTokens;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Commands page: each command's enabled flag and reply text, plus the second channel's address
 * in masked form only (FR-025). Behind the sign-in filter, which also checks the CSRF token on POST.
 */
@WebServlet("/dashboard/config")
public class ConfigServlet extends HttpServlet {

    private static final String VIEW = "/WEB-INF/views/config.jsp";
    private static final String FLASH = "flash";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        render(request, response, null);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ConfigService service = Services.get(getServletContext(), ConfigService.class);
        if (service == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        ConfigService.Result result = service.save(request.getParameter("command"),
                request.getParameter("enabled"), request.getParameter("replyText"));
        if (result.ok()) {
            request.getSession(false).setAttribute(FLASH, result.message());
            response.sendRedirect(request.getContextPath() + "/dashboard/config");
            return;
        }
        render(request, response, result.message()); // the page shows the saved (old) values
    }

    private void render(HttpServletRequest request, HttpServletResponse response, String error)
            throws ServletException, IOException {
        ConfigService service = Services.get(getServletContext(), ConfigService.class);
        AppConfig config = Services.get(getServletContext(), AppConfig.class);
        if (service == null || config == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        HttpSession session = request.getSession(false);
        request.setAttribute("csrf", CsrfTokens.tokenFor(session));
        request.setAttribute("page", "config");
        Object flash = session.getAttribute(FLASH);
        if (flash != null) {
            session.removeAttribute(FLASH);
            request.setAttribute("notice", flash);
        }
        String problem = error;
        List<Map<String, Object>> rows = new ArrayList<>();
        try {
            for (CommandConfig c : service.all()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("command", c.command());
                row.put("enabled", c.enabled());
                row.put("replyText", c.replyText());
                row.put("updatedAt", c.updatedAt().toString());
                rows.add(row);
            }
        } catch (SQLException e) {
            problem = problem == null ? "The command settings could not be read right now." : problem;
        }
        request.setAttribute("commands", rows);
        request.setAttribute("mirror", config.maskedMirrorAddress());
        request.setAttribute("error", problem);
        request.getRequestDispatcher(VIEW).forward(request, response);
    }
}
