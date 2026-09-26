package com.example.discordbot.dashboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.discordbot.discord.DiscordClient;
import com.example.discordbot.persistence.PostgresTestSupport;
import com.example.discordbot.persistence.ServerConnection;
import com.example.discordbot.persistence.ServerConnectionStore;
import com.example.discordbot.support.StubServer;
import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConnectServiceTest {

    private StubServer stub;
    private ServerConnectionStore store;
    private ConnectService service;

    @BeforeEach
    void setUp() throws SQLException {
        stub = new StubServer();
        stub.route("/users/@me/guilds", 200, "[{\"id\":\"g1\",\"name\":\"Alpha\"}]", 0);
        stub.route("/guilds/g1/channels", 200,
                "[{\"id\":\"c1\",\"name\":\"general\",\"type\":0},{\"id\":\"c2\",\"name\":\"voice\",\"type\":2}]", 0);
        stub.route("/channels/", 200, "", 0);
        stub.route("/applications/", 200, "", 0);
        store = new ServerConnectionStore(PostgresTestSupport.freshDatabase());
        DiscordClient client = new DiscordClient(stub.base(), "bot-secret", Duration.ofSeconds(1), Duration.ofSeconds(2));
        service = new ConnectService(client, store, "app1");
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    @Test
    void successProvesPostingThenSavesThenRegistersInThatOrder() throws Exception {
        ConnectService.Outcome outcome = service.connect("g1", "c1");
        assertTrue(outcome.saved() && outcome.ok(), outcome.message());
        assertTrue(outcome.message().contains("Alpha") && outcome.message().contains("#general"));

        ServerConnection saved = store.get().orElseThrow();
        assertEquals("g1", saved.guildId());
        assertEquals("c1", saved.channelId());
        assertEquals("general", saved.channelName());

        var hits = stub.allHits();
        int test = indexOf(hits, "/channels/c1/messages");
        int register = indexOf(hits, "/applications/app1/guilds/g1/commands");
        assertTrue(test >= 0 && register > test, "test message first, then registration");
    }

    private static int indexOf(java.util.List<StubServer.Hit> hits, String path) {
        for (int i = 0; i < hits.size(); i++) {
            if (hits.get(i).path().equals(path)) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void ifTheTestMessageFailsNothingIsSavedAndNothingIsRegistered() throws Exception {
        stub.route("/channels/", 403, "{\"code\":50013,\"message\":\"Missing Permissions\"}", 0);
        ConnectService.Outcome outcome = service.connect("g1", "c1");
        assertFalse(outcome.saved());
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("Nothing was saved"));
        assertTrue(store.get().isEmpty(), "no connection may be stored");
        assertTrue(stub.hits("/applications/").isEmpty(), "commands are not registered for an unusable channel");
    }

    @Test
    void aChannelThatDiscordDidNotListIsRejectedWithoutAnyPost() throws Exception {
        assertFalse(service.connect("g1", "not-a-listed-channel").saved());
        assertFalse(service.connect("g1", "c2").saved(), "c2 is a voice channel, not a text channel");
        assertTrue(stub.hits("/channels/").isEmpty());
        assertTrue(store.get().isEmpty());
    }

    @Test
    void aServerTheBotIsNotInIsRejected() throws Exception {
        ConnectService.Outcome outcome = service.connect("other", "c1");
        assertFalse(outcome.saved());
        assertTrue(outcome.message().contains("not one the bot has been added to"));
        assertTrue(store.get().isEmpty());
    }

    @Test
    void ifRegistrationFailsTheConnectionIsKeptAndTheMessageSaysToSaveAgain() throws Exception {
        stub.route("/applications/", 500, "", 0);
        ConnectService.Outcome outcome = service.connect("g1", "c1");
        assertTrue(outcome.saved());
        assertFalse(outcome.ok());
        assertTrue(outcome.message().contains("registering the commands failed"));
        assertTrue(store.get().isPresent());
    }

    @Test
    void changingTheChannelLaterMovesLaterPosts() throws Exception {
        stub.route("/guilds/g1/channels", 200,
                "[{\"id\":\"c1\",\"name\":\"general\",\"type\":0},{\"id\":\"c9\",\"name\":\"reports\",\"type\":0}]", 0);
        assertTrue(service.connect("g1", "c1").ok());
        assertTrue(service.connect("g1", "c9").ok());
        assertEquals("c9", store.get().orElseThrow().channelId());
    }

    @Test
    void theInviteLinkCarriesTheApplicationIdBothScopesAndThePermissionValue() {
        String url = service.inviteUrl();
        assertTrue(url.startsWith("https://discord.com/oauth2/authorize?"));
        assertTrue(url.contains("client_id=app1"));
        assertTrue(url.contains("scope=bot%20applications.commands"));
        assertTrue(url.contains("permissions=3072"));
    }

    @Test
    void listingErrorsAreReportedWithoutLeakingDetails() {
        stub.route("/users/@me/guilds", 500, "", 0);
        ConnectService.Listing<?> listing = service.guilds();
        assertTrue(listing.items().isEmpty());
        assertTrue(listing.error().contains("HTTP 500"));
        assertFalse(listing.error().contains("bot-secret"));
    }
}
