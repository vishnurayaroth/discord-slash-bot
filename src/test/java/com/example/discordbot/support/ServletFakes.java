package com.example.discordbot.support;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Tiny fakes for the servlet API built on java.lang.reflect.Proxy (no mocking library, constitution
 * Principle VI). Only the methods the code under test calls are implemented; every other method
 * returns a neutral default.
 */
public final class ServletFakes {

    private ServletFakes() {}

    private static final Object UNHANDLED = new Object();

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<Method, Object[], Object> handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (p, method, args) -> {
            Object result = handler.apply(method, args == null ? new Object[0] : args);
            return result == UNHANDLED ? neutral(method.getReturnType()) : result;
        });
    }

    private static Object neutral(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        return null;
    }

    public static final class FakeSession {
        public final Map<String, Object> attributes = new HashMap<>();
        public boolean invalidated;
        public final HttpSession session = proxy(HttpSession.class, (m, a) -> switch (m.getName()) {
            case "getAttribute" -> attributes.get((String) a[0]);
            case "setAttribute" -> {
                attributes.put((String) a[0], a[1]);
                yield null;
            }
            case "removeAttribute" -> {
                attributes.remove((String) a[0]);
                yield null;
            }
            case "invalidate" -> {
                invalidated = true;
                yield null;
            }
            default -> UNHANDLED;
        });
    }

    public static final class FakeRequest {
        public String method = "GET";
        public String servletPath = "/";
        public String contextPath = "";
        public final Map<String, String> parameters = new HashMap<>();
        public FakeSession session;

        public HttpServletRequest request() {
            return proxy(HttpServletRequest.class, (m, a) -> switch (m.getName()) {
                case "getMethod" -> method;
                case "getServletPath" -> servletPath;
                case "getContextPath" -> contextPath;
                case "getParameter" -> parameters.get((String) a[0]);
                case "getSession" -> (a.length == 1 && Boolean.FALSE.equals(a[0]) && session == null)
                        ? null
                        : sessionOrCreate();
                default -> UNHANDLED;
            });
        }

        private HttpSession sessionOrCreate() {
            if (session == null) {
                session = new FakeSession();
            }
            return session.session;
        }
    }

    public static final class FakeResponse {
        public int status = 200;
        public String redirect;
        public int errorStatus;
        public String contentType;
        private final StringWriter body = new StringWriter();

        public String body() {
            return body.toString();
        }

        public HttpServletResponse response() {
            return proxy(HttpServletResponse.class, (m, a) -> switch (m.getName()) {
                case "setStatus" -> {
                    status = (Integer) a[0];
                    yield null;
                }
                case "sendRedirect" -> {
                    redirect = (String) a[0];
                    yield null;
                }
                case "sendError" -> {
                    errorStatus = (Integer) a[0];
                    yield null;
                }
                case "setContentType" -> {
                    contentType = (String) a[0];
                    yield null;
                }
                case "getWriter" -> new PrintWriter(body, true);
                default -> UNHANDLED;
            });
        }
    }

    public static final class FakeChain implements FilterChain {
        public boolean called;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            called = true;
        }
    }
}
