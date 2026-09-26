package com.example.discordbot.interactions;

import com.example.discordbot.discord.Interaction;
import com.example.discordbot.persistence.Planner;
import com.example.discordbot.security.SignatureVerifier;
import java.io.IOException;

/**
 * The interactions endpoint's logic, free of servlet types (contracts/interactions-endpoint.md).
 * Order is fixed: verify, parse, route, record through the gate, respond. Nothing is logged
 * except ids, command names, outcomes and timings (constitution Principle IV).
 */
public final class InteractionHandler {

    private static final System.Logger LOG = System.getLogger(InteractionHandler.class.getName());

    /** A response plus work to start only after the response has been written. */
    public record HandlerResponse(int status, String contentType, String body, Runnable afterResponse) {
        static HandlerResponse json(String body) {
            return new HandlerResponse(200, "application/json", body, null);
        }

        static HandlerResponse text(int status, String body) {
            return new HandlerResponse(status, "text/plain", body, null);
        }
    }

    private final SignatureVerifier verifier;
    private final RecordGate gate;
    private final Planner planner;
    private final ActionStarter starter;

    public InteractionHandler(SignatureVerifier verifier, RecordGate gate, Planner planner, ActionStarter starter) {
        this.verifier = verifier;
        this.gate = gate;
        this.planner = planner;
        this.starter = starter;
    }

    public HandlerResponse handle(String signatureHex, String timestamp, byte[] body) {
        long start = System.nanoTime();
        if (!verifier.isValid(signatureHex, timestamp, body)) {
            LOG.log(System.Logger.Level.INFO, "request rejected: invalid signature or stale timestamp");
            return HandlerResponse.text(401, "invalid request signature");
        }
        Interaction interaction;
        try {
            interaction = Interaction.parse(body);
        } catch (IOException e) {
            return HandlerResponse.text(400, "bad request");
        }
        return switch (interaction.type()) {
            case 1 -> HandlerResponse.json(Responses.pong());
            case 2 -> command(interaction, start);
            default -> HandlerResponse.text(400, "unsupported interaction type");
        };
    }

    private HandlerResponse command(Interaction interaction, long start) {
        if (isBlank(interaction.id()) || isBlank(interaction.command())) {
            return HandlerResponse.text(400, "bad request");
        }
        String id = interaction.id();
        RecordGate.Result result = gate.record(interaction, planner, starter::start, start);
        long millis = (System.nanoTime() - start) / 1_000_000;
        switch (result) {
            case REFUSED -> {
                LOG.log(System.Logger.Level.WARNING,
                        "command {0} ({1}) refused: record not committed in time, {2} ms", id, interaction.command(), millis);
                return HandlerResponse.json(Responses.tryAgain());
            }
            case DUPLICATE -> {
                LOG.log(System.Logger.Level.INFO, "command {0} is a duplicate delivery, ignored", id);
                return HandlerResponse.json(Responses.deferredPrivate());
            }
            case UNCONFIRMED -> {
                LOG.log(System.Logger.Level.WARNING,
                        "command {0} ({1}) acknowledged with the record outcome still unknown, {2} ms", id, interaction.command(), millis);
                return HandlerResponse.json(Responses.deferredPrivate());
            }
            default -> {
                LOG.log(System.Logger.Level.INFO,
                        "command {0} ({1}) accepted, record step {2} ms", id, interaction.command(), millis);
                return new HandlerResponse(200, "application/json", Responses.deferredPrivate(), () -> starter.start(id));
            }
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
