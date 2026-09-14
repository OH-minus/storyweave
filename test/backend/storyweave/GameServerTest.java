package backend.storyweave;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class GameServerTest {
    static void run() throws Exception {
        MutableClock clock = new MutableClock(1_000);
        GameEngine game = new GameEngine(2, 20, 30, 5, clock, new java.util.Random(0));
        TrackingStoryService storyService = new TrackingStoryService(clock);
        try (GameServer server = new GameServer(0, game, storyService, "lost cities", 2)) {
            server.start();
            String base = "http://localhost:" + server.port();
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> health = get(client, base + "/api/health");
            check(health.statusCode() == 200, "health endpoint should respond");
            check(Json.parseObject(health.body()).get("status").equals("ok"), "health response should be JSON");

            HttpResponse<String> page = get(client, base + "/");
            check(page.statusCode() == 200 && page.body().contains("Storyweave"), "server should host the frontend");

            HttpResponse<String> badJoin = post(client, base + "/api/join", Map.of("name", "bad name"));
            check(badJoin.statusCode() == 400, "invalid names should be rejected before joining");
            HttpResponse<String> aliceJoin = post(client, base + "/api/join", Map.of("name", "Alice"));
            check(storyService.createStoryCalls().isEmpty(), "stories should not be generated before lobby is full");
            HttpResponse<String> duplicateJoin = post(client, base + "/api/join", Map.of("name", "Alice"));
            check(duplicateJoin.statusCode() == 201, "the same name should rejoin successfully");
            check(Json.parseObject(duplicateJoin.body()).get("playerId")
                            .equals(Json.parseObject(aliceJoin.body()).get("playerId")),
                    "rejoining should return the original player identity");
            check(storyService.createStoryCalls().isEmpty(), "duplicate joins must not call story generation");
            HttpResponse<String> bobJoin = post(client, base + "/api/join", Map.of("name", "Bob"));
            check(aliceJoin.statusCode() == 201 && bobJoin.statusCode() == 201, "two players should join");
            check(storyService.createStoryCalls().isEmpty(),
                    "a full lobby should not generate stories before every player is ready");
            String aliceId = String.valueOf(Json.parseObject(aliceJoin.body()).get("playerId"));
            String aliceConnectionId = String.valueOf(Json.parseObject(duplicateJoin.body()).get("connectionId"));
            String bobId = String.valueOf(Json.parseObject(bobJoin.body()).get("playerId"));
            String bobConnectionId = String.valueOf(Json.parseObject(bobJoin.body()).get("connectionId"));
            Map<String, Object> preparation = Json.parseObject(
                    get(client, base + "/api/state?playerId=" + aliceId).body());
            check(preparation.get("phase").equals("PREPARATION"),
                    "a full lobby should enter the preparation phase");

            HttpResponse<String> rename = post(client, base + "/api/rename",
                    Map.of("playerId", aliceId, "connectionId", aliceConnectionId, "name", "Alicia"));
            check(rename.statusCode() == 200, "a player should be able to rename before the game starts");
            HttpResponse<String> aliceReady = post(client, base + "/api/ready",
                    Map.of("playerId", aliceId, "connectionId", aliceConnectionId));
            check(aliceReady.statusCode() == 200, "a player should be able to become ready");
            check(storyService.createStoryCalls().isEmpty(),
                    "one ready player should not generate stories or start the game");
            HttpResponse<String> bobReady = post(client, base + "/api/ready",
                    Map.of("playerId", bobId, "connectionId", bobConnectionId));
            check(bobReady.statusCode() == 200, "the final player should be able to become ready");
            List<TrackingStoryService.CreateStoryCall> calls = storyService.createStoryCalls();
            check(calls.size() == 2, "story generation should run once per accepted player when game starts");
            check(calls.get(0).version() == 0 && calls.get(1).version() == 1,
                    "versions should be assigned sequentially without retry inflation");
            check(calls.get(0).playerCount() == 2 && calls.get(1).playerCount() == 2,
                    "each generated story should use expected player count");
            check(calls.get(0).storyContext().isEmpty(),
                    "the first story should be generated without prior-story context");
            check(calls.get(1).storyContext().equals(
                            "Variation 1:\nMira entered the moonlit archive. Story v0"),
                    "later stories should receive previously generated variations as context");
            HttpResponse<String> state = get(client, base + "/api/state?playerId=" + aliceId);
            check(state.statusCode() == 200, "a joined player should retrieve synchronized state");
            check(Json.parseObject(state.body()).get("phase").equals("READING"), "full lobby should enter reading phase");
            check(((Number) Json.parseObject(state.body()).get("phaseRemainingMillis")).longValue() == 20_000,
                    "reading countdown should start after every story has been generated");
            check(!String.valueOf(Json.parseObject(state.body()).get("story")).isBlank(),
                    "private story should be available once the game has started");
            check(Json.parseObject(state.body()).get("sharedStory").equals("Mira entered the moonlit archive."),
                    "the common opening sentence should be shown during reading");
            HttpResponse<String> quit = post(client, base + "/api/quit",
                    Map.of("playerId", aliceId, "connectionId", aliceConnectionId));
            check(quit.statusCode() == 200, "a player should be able to quit");
            Map<String, Object> afterQuit = Json.parseObject(
                    get(client, base + "/api/state?playerId=" + bobId).body());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> players = (List<Map<String, Object>>) afterQuit.get("players");
            check(players.stream()
                            .filter(player -> player.get("id").equals(aliceId))
                            .anyMatch(player -> player.get("quit").equals(true)),
                    "other players should see that Alice quit");
            HttpResponse<String> aliceRejoin = post(client, base + "/api/join", Map.of("name", "Alicia"));
            check(Json.parseObject(aliceRejoin.body()).get("playerId").equals(aliceId),
                    "Alice should reclaim her existing session");
            post(client, base + "/api/quit", Map.of("playerId", aliceId, "connectionId", aliceConnectionId));
            Map<String, Object> afterStaleQuit = Json.parseObject(
                    get(client, base + "/api/state?playerId=" + bobId).body());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> rejoinedPlayers = (List<Map<String, Object>>) afterStaleQuit.get("players");
            check(rejoinedPlayers.stream()
                            .filter(player -> player.get("id").equals(aliceId))
                            .noneMatch(player -> player.get("quit").equals(true)),
                    "a delayed exit notification from the old page must not quit the rejoined session");
            check(get(client, base + "/api/state?playerId=unknown").statusCode() == 404,
                    "unknown players should not read game state");
        }
    }

    private static final class TrackingStoryService implements StoryService {
        private final List<CreateStoryCall> createStoryCalls = new ArrayList<>();
        private final MutableClock clock;

        private TrackingStoryService(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public synchronized String createStory(String theme, String storyContext, int version, int playerCount) {
            createStoryCalls.add(new CreateStoryCall(theme, storyContext, version, playerCount));
            clock.advanceMillis(15_000);
            return "Mira entered the moonlit archive. Story v" + version;
        }

        @Override
        public int scoreSimilarity(String sharedStory, String referenceStory) {
            return 0;
        }

        @Override
        public int scoreDeduction(String sharedStory, List<GameEngine.Entry> entries) {
            return 0;
        }

        private synchronized List<CreateStoryCall> createStoryCalls() {
            return List.copyOf(createStoryCalls);
        }

        private record CreateStoryCall(String theme, String storyContext, int version, int playerCount) {
        }
    }

    private static HttpResponse<String> get(HttpClient client, String url) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> post(HttpClient client, String url, Object body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}