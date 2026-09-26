package com.example.discordbot.dashboard;

import com.example.discordbot.security.CsrfTokens;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * The log page shell (the script fills it in) and the site root. {@code /} redirects to
 * {@code /dashboard}, which the sign-in filter protects.
 */
@WebServlet(urlPatterns = {"", "/dashboard"})
public class DashboardServlet extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (request.getServletPath().isEmpty() || "/".equals(request.getServletPath())) {
            response.sendRedirect(request.getContextPath() + "/dashboard");
            return;
        }
        // The filter guarantees a signed-in session here.
        request.setAttribute("csrf", CsrfTokens.tokenFor(request.getSession(false)));
        request.setAttribute("page", "log");
        request.getRequestDispatcher("/WEB-INF/views/dashboard.jsp").forward(request, response);
    }
}
