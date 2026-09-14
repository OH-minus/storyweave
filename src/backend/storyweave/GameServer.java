package backend.storyweave;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GameServer implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService requestExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);
    private final GameEngine game;
    private final StoryService storyService;
    private final StoryService fallbackScorer = new LocalStoryService();
    private final String theme;
    private final int expectedPlayers;
    private final AtomicBoolean storiesAssigned = new AtomicBoolean(false);

    public GameServer(int port, GameEngine game, StoryService storyService, String theme, int expectedPlayers)
            throws IOException {
        this.game = game;
        this.storyService = storyService;
        this.theme = theme;
        this.expectedPlayers = expectedPlayers;
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.setExecutor(requestExecutor);
        server.createContext("/api/health", this::handleHealth);
        server.createContext("/api/join", this::handleJoin);
        server.createContext("/api/state", this::handleState);
        server.createContext("/api/submit", this::handleSubmit);
        server.createContext("/api/quit", this::handleQuit);
        server.createContext("/", this::handleStaticFile);
    }

    public void start() {
        server.start();
        scheduler.scheduleAtFixedRate(this::maintainGame, 100, 100, TimeUnit.MILLISECONDS);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        server.stop(0);
        requestExecutor.shutdownNow();
    }

    private void maintainGame() {
        try {
            if (game.phase() == GameEngine.Phase.SCORING && game.beginScoring()) {
                scheduler.execute(this::calculateScores);
            }
        } catch (RuntimeException exception) {
            System.err.println("Game maintenance failed: " + exception.getMessage());
        }
    }

    private void calculateScores() {
        LinkedHashMap<String, Integer> result = new LinkedHashMap<>();
        String sharedStory = game.sharedStory();
        for (GameEngine.PlayerForScoring player : game.playersForScoring()) {
            try {
                int similarity = storyService.scoreSimilarity(sharedStory, player.story());
                int deduction = storyService.scoreDeduction(sharedStory, game.entriesFor(player.id()));
                result.put(player.id(), Math.clamp(similarity - deduction, 0, 100));
            } catch (Exception exception) {
                System.err.println("Remote scoring failed for " + player.name() + "; using local scoring: "
                        + exception.getMessage());
                try {
                    int similarity = fallbackScorer.scoreSimilarity(sharedStory, player.story());
                    int deduction = fallbackScorer.scoreDeduction(sharedStory, game.entriesFor(player.id()));
                    result.put(player.id(), Math.clamp(similarity - deduction, 0, 100));
                } catch (Exception impossible) {
                    result.put(player.id(), 0);
                }
            }
        }
        game.finishScoring(result);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        sendJson(exchange, 200, Map.of("status", "ok", "phase", game.phase().name()));
    }

    private void handleJoin(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            Map<String, Object> body = readJson(exchange);
            String name = string(body, "name");
            GameEngine.validatePlayerName(name);
            GameEngine.JoinResult joined = game.join(name, "");
            assignStoriesWhenReady();
            String story = String.valueOf(game.snapshot(joined.playerId()).get("story"));
            sendJson(exchange, 201, Map.of(
                    "playerId", joined.playerId(),
                    "connectionId", joined.connectionId(),
                    "story", story,
                    "readSeconds", joined.readSeconds()
            ));
        } catch (GameEngine.GameException exception) {
            sendError(exchange, exception.statusCode(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, exception.getMessage());
        } catch (Exception exception) {
            sendError(exchange, 502, "Could not generate a story: " + exception.getMessage());
        }
    }

    private void assignStoriesWhenReady() throws Exception {
        if (storiesAssigned.get() || game.playersForScoring().size() < expectedPlayers) {
            return;
        }
        if (!storiesAssigned.compareAndSet(false, true)) {
            return;
        }
        try {
            List<GameEngine.PlayerForScoring> players = game.playersForScoring();
            Map<String, String> storiesByPlayerId = new HashMap<>();
            StringBuilder storyContext = new StringBuilder();
            for (int version = 0; version < players.size(); version++) {
                GameEngine.PlayerForScoring player = players.get(version);
                String story = storyService.createStory(theme, storyContext.toString(), version, expectedPlayers);
                storiesByPlayerId.put(player.id(), story);
                if (!storyContext.isEmpty()) {
                    storyContext.append("\n\n");
                }
                storyContext.append("Variation ").append(version + 1).append(":\n").append(story);
            }
            game.assignStories(storiesByPlayerId);
        } catch (Exception exception) {
            storiesAssigned.set(false);
            throw exception;
        }
    }

    private void handleState(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        try {
            String playerId = queryParameters(exchange.getRequestURI()).get("playerId");
            sendJson(exchange, 200, game.snapshot(playerId));
        } catch (GameEngine.GameException exception) {
            sendError(exchange, exception.statusCode(), exception.getMessage());
        }
    }

    private void handleSubmit(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            Map<String, Object> body = readJson(exchange);
            game.submit(string(body, "playerId"), string(body, "token"));
            sendJson(exchange, 200, Map.of("accepted", true));
        } catch (GameEngine.GameException exception) {
            sendError(exchange, exception.statusCode(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, exception.getMessage());
        }
    }

    private void handleQuit(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "POST")) {
            return;
        }
        try {
            Map<String, Object> body = readJson(exchange);
            game.quit(string(body, "playerId"), string(body, "connectionId"));
            sendJson(exchange, 200, Map.of("status", "ok"));
        } catch (GameEngine.GameException exception) {
            sendError(exchange, exception.statusCode(), exception.getMessage());
        } catch (IllegalArgumentException exception) {
            sendError(exchange, 400, exception.getMessage());
        }
    }

    private void handleStaticFile(HttpExchange exchange) throws IOException {
        if (!requireMethod(exchange, "GET")) {
            return;
        }
        String requestPath = exchange.getRequestURI().getPath();
        String fileName = requestPath.equals("/") ? "index.html" : requestPath.substring(1);
        if (!fileName.equals("index.html") && !fileName.equals("app.js") && !fileName.equals("styles.css")) {
            sendError(exchange, 404, "Not found");
            return;
        }
        byte[] content;
        try (InputStream resource = GameServer.class.getResourceAsStream("/frontend/" + fileName)) {
            if (resource == null) {
                sendError(exchange, 404, "Frontend asset not found");
                return;
            }
            content = resource.readAllBytes();
        }
        String contentType = fileName.endsWith(".html") ? "text/html; charset=utf-8"
                : fileName.endsWith(".css") ? "text/css; charset=utf-8" : "text/javascript; charset=utf-8";
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, content.length);
        exchange.getResponseBody().write(content);
        ServerLogger.logHttp(exchange.getRequestMethod(), pathAndQuery(exchange.getRequestURI()), requestBody(exchange), 200,
                "<" + fileName + ", bytes=" + content.length + ", contentType=" + contentType + ">");
        exchange.close();
    }

    private static Map<String, Object> readJson(HttpExchange exchange) throws IOException {
        try (InputStream body = exchange.getRequestBody()) {
            String text = new String(body.readAllBytes(), StandardCharsets.UTF_8);
            exchange.setAttribute("requestBody", text);
            return Json.parseObject(text);
        }
    }

    private static String string(Map<String, Object> body, String key) {
        Object value = body.get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Missing string field: " + key);
        }
        return text;
    }

    private static Map<String, String> queryParameters(URI uri) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        if (uri.getRawQuery() == null) {
            return result;
        }
        for (String parameter : uri.getRawQuery().split("&")) {
            String[] parts = parameter.split("=", 2);
            String value = parts.length == 2 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "";
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), value);
        }
        return result;
    }

    private static boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        addCommonHeaders(exchange.getResponseHeaders());
        if (exchange.getRequestMethod().equals("OPTIONS")) {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
            return false;
        }
        if (!exchange.getRequestMethod().equals(method)) {
            sendError(exchange, 405, "Method not allowed");
            return false;
        }
        return true;
    }

    private static void sendJson(HttpExchange exchange, int status, Object body) throws IOException {
        String responseText = Json.stringify(body);
        byte[] content = responseText.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        addCommonHeaders(headers);
        headers.set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, content.length);
        exchange.getResponseBody().write(content);
        ServerLogger.logHttp(exchange.getRequestMethod(), pathAndQuery(exchange.getRequestURI()), requestBody(exchange), status,
                responseText);
        exchange.close();
    }

    private static void sendError(HttpExchange exchange, int status, String message) throws IOException {
        sendJson(exchange, status, Map.of("error", message));
    }

    private static void addCommonHeaders(Headers headers) {
        headers.set("Access-Control-Allow-Origin", "*");
        headers.set("Access-Control-Allow-Headers", "Content-Type");
        headers.set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        headers.set("Cache-Control", "no-store");
    }

    private static String requestBody(HttpExchange exchange) {
        Object value = exchange.getAttribute("requestBody");
        return value == null ? "" : String.valueOf(value);
    }

    private static String pathAndQuery(URI uri) {
        String query = uri.getRawQuery();
        return query == null ? uri.getPath() : uri.getPath() + "?" + query;
    }
}