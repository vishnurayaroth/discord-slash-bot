package com.example.discordbot.interactions;

import com.example.discordbot.config.Services;
import com.example.discordbot.config.Timing;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Thin adapter for POST /interactions: raw bytes and the two signature headers in, the handler's
 * response out. Any other method gets 405 from HttpServlet. Work that must follow the
 * acknowledgement is started only after the response has been flushed.
 */
@WebServlet("/interactions")
public class InteractionsServlet extends HttpServlet {

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        InteractionHandler handler = Services.get(getServletContext(), InteractionHandler.class);
        if (handler == null) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            return;
        }
        if (request.getContentLength() > Timing.MAX_BODY_BYTES) {
            response.setStatus(413);
            return;
        }
        byte[] body = readBounded(request.getInputStream(), Timing.MAX_BODY_BYTES);
        if (body == null) {
            response.setStatus(413);
            return;
        }
        InteractionHandler.HandlerResponse result = handler.handle(
                request.getHeader("X-Signature-Ed25519"), request.getHeader("X-Signature-Timestamp"), body);

        byte[] out = result.body().getBytes(StandardCharsets.UTF_8);
        response.setStatus(result.status());
        response.setContentType(result.contentType());
        response.setCharacterEncoding("UTF-8");
        response.setContentLength(out.length);
        response.getOutputStream().write(out);
        response.flushBuffer();
        if (result.afterResponse() != null) {
            result.afterResponse().run();
        }
    }

    /** Reads at most {@code max} bytes; returns null when the body is larger. */
    static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = in.read(chunk)) != -1) {
            if (buffer.size() + read > max) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }
}
