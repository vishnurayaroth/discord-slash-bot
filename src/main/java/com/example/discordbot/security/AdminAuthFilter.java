package com.example.discordbot.security;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.annotation.WebFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;

/**
 * Guards everything behind the admin sign-in (FR-018, FR-024). Signed-out pages redirect to the
 * sign-in page; signed-out {@code /api/*} calls get 401 and never a redirect. Every POST must also
 * carry the session's anti-forgery token, otherwise it is refused before reaching any servlet.
 */
@WebFilter(urlPatterns = {"/dashboard/*", "/api/*", "/logout"})
public class AdminAuthFilter implements Filter {

    /** Session attribute set by the login servlet. */
    public static final String SESSION_ADMIN = "admin";

    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        HttpSession session = request.getSession(false);
        boolean signedIn = session != null && Boolean.TRUE.equals(session.getAttribute(SESSION_ADMIN));
        if (!signedIn) {
            if (request.getServletPath().startsWith("/api/")) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json");
                response.setCharacterEncoding("UTF-8");
                response.getWriter().write("{\"error\":\"unauthorized\"}");
            } else {
                response.sendRedirect(request.getContextPath() + "/login");
            }
            return;
        }
        if ("POST".equals(request.getMethod()) && !CsrfTokens.valid(session, request.getParameter(CsrfTokens.FIELD))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
            return;
        }
        chain.doFilter(request, response);
    }
}
