package com.example.discordbot.dashboard;

import com.example.discordbot.config.Services;
import com.example.discordbot.security.AdminAuth;
import com.example.discordbot.security.AdminAuthFilter;
import com.example.discordbot.security.CsrfTokens;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;

/**
 * Sign-in page. Wrong username and wrong password give the same generic message. On success the
 * session id is replaced (against session fixation) and the admin flag and CSRF token are set.
 */
@WebServlet("/login")
public class LoginServlet extends HttpServlet {

    private static final String VIEW = "/WEB-INF/views/login.jsp";

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.getRequestDispatcher(VIEW).forward(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        AdminAuth auth = Services.get(getServletContext(), AdminAuth.class);
        if (auth != null && auth.check(request.getParameter("username"), request.getParameter("password"))) {
            request.getSession(true);
            request.changeSessionId();
            HttpSession session = request.getSession(false);
            session.setAttribute(AdminAuthFilter.SESSION_ADMIN, Boolean.TRUE);
            CsrfTokens.tokenFor(session);
            response.sendRedirect(request.getContextPath() + "/dashboard");
            return;
        }
        request.setAttribute("error", "Sign-in failed.");
        request.getRequestDispatcher(VIEW).forward(request, response);
    }
}
