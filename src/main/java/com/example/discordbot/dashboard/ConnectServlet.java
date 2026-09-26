package com.example.discordbot.dashboard;

import com.example.discordbot.config.Services;
import com.example.discordbot.discord.DiscordClient.Channel;
import com.example.discordbot.discord.DiscordClient.Guild;
import com.example.discordbot.persistence.ServerConnection;
import com.example.discordbot.persistence.ServerConnectionStore;
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
 * The Connect page: add the bot, refresh the servers it is in, pick a server and a text channel.
 * Guilds and channels are passed to the JSP as maps (the JSP expression language reads maps and
 * JavaBeans, not records). Behind the sign-in filter, which also checks the CSRF token on POST.
 */
@WebServlet("/dashboard/connect")
public class ConnectServlet extends HttpServlet {

    private static final String VIEW = "/WEB-INF/views/connect.jsp";
    private static final String FLASH = "flash";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        render(request, response, request.getParameter("guild"), "1".equals(request.getParameter("refresh")), null);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        ConnectService service = Services.get(getServletContext(), ConnectService.class);
        if (service == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        String guildId = request.getParameter("guildId");
        ConnectService.Outcome outcome = service.connect(guildId, request.getParameter("channelId"));
        if (outcome.saved() && outcome.ok()) {
            request.getSession(false).setAttribute(FLASH, outcome.message());
            response.sendRedirect(request.getContextPath() + "/dashboard/connect");
            return;
        }
        // A failed attempt shows the page again with the message; a saved-but-not-fully-done one says so too.
        render(request, response, guildId, false, outcome.message());
    }

    private void render(HttpServletRequest request, HttpServletResponse response, String guildId, boolean refresh,
            String error) throws ServletException, IOException {
        ConnectService service = Services.get(getServletContext(), ConnectService.class);
        ServerConnectionStore store = Services.get(getServletContext(), ServerConnectionStore.class);
        if (service == null || store == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        HttpSession session = request.getSession(false);
        request.setAttribute("csrf", CsrfTokens.tokenFor(session));
        request.setAttribute("page", "connect");
        request.setAttribute("invite", service.inviteUrl());

        Object flash = session.getAttribute(FLASH);
        if (flash != null) {
            session.removeAttribute(FLASH);
            request.setAttribute("notice", flash);
        }
        String problem = error;
        try {
            store.get().ifPresent(c -> request.setAttribute("connection", describe(c)));
        } catch (SQLException e) {
            problem = problem == null ? "The current connection could not be read right now." : problem;
        }

        if (refresh || (guildId != null && !guildId.isBlank())) {
            ConnectService.Listing<Guild> guilds = service.guilds();
            if (guilds.error() != null && problem == null) {
                problem = guilds.error();
            }
            List<Map<String, String>> guildRows = new ArrayList<>();
            for (Guild g : guilds.items()) {
                guildRows.add(Map.of("id", g.id(), "name", g.name()));
            }
            request.setAttribute("guilds", guildRows);
        }
        if (guildId != null && !guildId.isBlank()) {
            request.setAttribute("guildId", guildId);
            ConnectService.Listing<Channel> channels = service.channels(guildId);
            if (channels.error() != null && problem == null) {
                problem = channels.error();
            }
            List<Map<String, String>> channelRows = new ArrayList<>();
            for (Channel c : channels.items()) {
                channelRows.add(Map.of("id", c.id(), "name", c.name()));
            }
            request.setAttribute("channels", channelRows);
        }
        request.setAttribute("error", problem);
        request.getRequestDispatcher(VIEW).forward(request, response);
    }

    private static Map<String, String> describe(ServerConnection c) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("guildName", c.guildName());
        m.put("channelName", c.channelName());
        m.put("connectedAt", c.connectedAt().toString());
        return m;
    }
}
