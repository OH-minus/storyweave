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
            check(duplicateJoin.statusCode() == 409, "duplicate names should be rejected");
            check(storyService.createStoryCalls().isEmpty(), "duplicate joins must not call story generation");
            HttpResponse<String> bobJoin = post(client, base + "/api/join", Map.of("name", "Bob"));
            check(aliceJoin.statusCode() == 201 && bobJoin.statusCode() == 201, "two players should join");
            List<TrackingStoryService.CreateStoryCall> calls = storyService.createStoryCalls();
            check(calls.size() == 2, "story generation should run once per accepted player when game starts");
            check(calls.get(0).version() == 0 && calls.get(1).version() == 1,
                    "versions should be assigned sequentially without retry inflation");
            check(calls.get(0).playerCount() == 2 && calls.get(1).playerCount() == 2,
                    "each generated story should use expected player count");
            check(calls.get(0).storyContext().isEmpty(),
                    "the first story should be generated without prior-story context");
            check(calls.get(1).storyContext().equals("Variation 1:\nStory v0"),
                    "later stories should receive previously generated variations as context");
            String aliceId = String.valueOf(Json.parseObject(aliceJoin.body()).get("playerId"));
            HttpResponse<String> state = get(client, base + "/api/state?playerId=" + aliceId);
            check(state.statusCode() == 200, "a joined player should retrieve synchronized state");
            check(Json.parseObject(state.body()).get("phase").equals("READING"), "full lobby should enter reading phase");
            check(((Number) Json.parseObject(state.body()).get("phaseRemainingMillis")).longValue() == 20_000,
                    "reading countdown should start after every story has been generated");
            check(!String.valueOf(Json.parseObject(state.body()).get("story")).isBlank(),
                    "private story should be available once the game has started");
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
            return "Story v" + version;
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