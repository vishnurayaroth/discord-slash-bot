package com.example.discordbot.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.discord.DiscordClient.Channel;
import com.example.discordbot.discord.DiscordClient.Fetched;
import com.example.discordbot.discord.DiscordClient.Guild;
import com.example.discordbot.support.StubServer;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The dashboard-side Discord calls against a stub: nothing here touches the network. */
class DiscordClientConnectTest {

    private StubServer stub;
    private DiscordClient client;

    @BeforeEach
    void start() {
        stub = new StubServer();
        client = new DiscordClient(stub.base(), "BOT-SECRET", Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @AfterEach
    void stop() {
        stub.close();
    }

    @Test
    void listsTheServersTheBotIsIn() {
        stub.route("/users/@me/guilds", 200, "[{\"id\":\"g1\",\"name\":\"Alpha\"},{\"id\":\"g2\",\"name\":\"Beta\"}]", 0);
        Fetched<List<Guild>> fetched = client.listGuilds();
        assertTrue(fetched.result().isSuccess());
        assertEquals(List.of(new Guild("g1", "Alpha"), new Guild("g2", "Beta")), fetched.value());
        StubServer.Hit hit = stub.hits("/users/@me/guilds").get(0);
        assertEquals("GET", hit.method());
        assertEquals("Bot BOT-SECRET", hit.authorization());
    }

    @Test
    void keepsOnlyTextChannels() {
        stub.route("/guilds/g1/channels", 200, """
                [{"id":"c1","name":"general","type":0},{"id":"c2","name":"Voice","type":2},
                 {"id":"c3","name":"Category","type":4},{"id":"c4","name":"reports","type":0}]""", 0);
        Fetched<List<Channel>> fetched = client.listChannels("g1");
        assertEquals(List.of(new Channel("c1", "general"), new Channel("c4", "reports")), fetched.value());
        assertEquals("Bot BOT-SECRET", stub.hits("/guilds/g1/channels").get(0).authorization());
    }

    @Test
    void registersTheCommandsWithABulkOverwrite() {
        assertTrue(client.registerCommands("app1", "g1").isSuccess());
        StubServer.Hit hit = stub.hits("/applications/").get(0);
        assertEquals("PUT", hit.method());
        assertEquals("/applications/app1/guilds/g1/commands", hit.path());
        assertEquals("Bot BOT-SECRET", hit.authorization());
        assertEquals(CommandDefinitions.json(), hit.body());
    }

    @Test
    void failuresAreClassifiedAndCarryNoTokenOrBody() {
        stub.route("/users/@me/guilds", 403, "{\"code\":50001,\"message\":\"echo BOT-SECRET\"}", 0);
        Fetched<List<Guild>> fetched = client.listGuilds();
        assertFalse(fetched.result().isSuccess());
        assertEquals(DiscordResult.Kind.PERMANENT, fetched.result().kind());
        assertEquals("HTTP 403 code 50001", fetched.result().error());
        assertTrue(fetched.value().isEmpty());
        assertFalse(fetched.result().toString().contains("BOT-SECRET"));
    }

    @Test
    void aNonJsonBodyGivesAnEmptyListNotACrash() {
        stub.route("/guilds/g1/channels", 200, "this is not json", 0);
        assertTrue(client.listChannels("g1").value().isEmpty());
    }
}
