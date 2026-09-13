package backend.storyweave;

import java.io.Serial;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.regex.Pattern;

/** Thread-safe state machine for a complete storytelling game. */
public final class GameEngine {
    public enum Phase {
        WAITING, READING, PLAYING, SCORING, FINISHED
    }

    public record Entry(String playerId, String token) {
    }

    public record JoinResult(String playerId, String story, int readSeconds) {
    }

    public record Score(String playerId, String name, int score, int rank) {
    }

    private static final Pattern PLAYER_NAME = Pattern.compile("[!-~]{1,24}");
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{M}]+(?:['’-][\\p{L}\\p{M}]+)*");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\p{Punct}\\p{P}]");

    private final int expectedPlayers;
    private final long readDurationMillis;
    private final long playerDurationMillis;
    private final long turnDurationMillis;
    private final Clock clock;
    private final Random random;
    private final ArrayList<Player> players = new ArrayList<>();
    private final ArrayList<Entry> entries = new ArrayList<>();
    private final StringBuilder sharedStory = new StringBuilder();
    private Phase phase = Phase.WAITING;
    private long phaseDeadline;
    private long turnStartedAt;
    private long turnDeadline;
    private int currentPlayerIndex = -1;
    private List<Score> scores = List.of();

    public GameEngine(int expectedPlayers, int readSeconds, int playerSeconds, int turnSeconds) {
        this(expectedPlayers, readSeconds, playerSeconds, turnSeconds, Clock.systemUTC(), new Random());
    }

    public GameEngine(int expectedPlayers, int readSeconds, int playerSeconds, int turnSeconds, Clock clock,
            Random random) {
        if (expectedPlayers < 2 || expectedPlayers > 12) {
            throw new IllegalArgumentException("Player count must be between 2 and 12");
        }
        if (readSeconds < 0 || playerSeconds < 1 || turnSeconds < 1) {
            throw new IllegalArgumentException("Game durations are invalid");
        }
        this.expectedPlayers = expectedPlayers;
        this.readDurationMillis = readSeconds * 1_000L;
        this.playerDurationMillis = playerSeconds * 1_000L;
        this.turnDurationMillis = turnSeconds * 1_000L;
        this.clock = Objects.requireNonNull(clock);
        this.random = Objects.requireNonNull(random);
    }

    public synchronized JoinResult join(String name, String story) {
        processDeadlines();
        if (phase != Phase.WAITING || players.size() >= expectedPlayers) {
            throw new GameException(409, "This game is no longer accepting players");
        }
        validatePlayerName(name);
        if (players.stream().anyMatch(player -> player.name.equalsIgnoreCase(name))) {
            throw new GameException(409, "That player name is already in use");
        }
        Player player = new Player(UUID.randomUUID().toString(), name, Objects.requireNonNull(story), playerDurationMillis);
        players.add(player);
        return new JoinResult(player.id, player.story, Math.toIntExact(readDurationMillis / 1_000));
    }

    public synchronized void submit(String playerId, String token) {
        processDeadlines();
        if (phase != Phase.PLAYING) {
            throw new GameException(409, "The game is not accepting words right now");
        }
        Player current = players.get(currentPlayerIndex);
        if (!current.id.equals(playerId)) {
            throw new GameException(403, "It is not your turn");
        }
        if (!isValidToken(token)) {
            throw new GameException(400, "Enter exactly one word or one punctuation mark, without spaces");
        }
        debitCurrentPlayer(now());
        appendToken(token);
        entries.add(new Entry(playerId, token));
        advanceTurn();
    }

    public synchronized Map<String, Object> snapshot(String playerId) {
        processDeadlines();
        Player viewer = findPlayer(playerId);
        long currentTime = now();
        ArrayList<Map<String, Object>> playerViews = new ArrayList<>();
        for (int index = 0; index < players.size(); index++) {
            Player player = players.get(index);
            long remaining = player.remainingMillis;
            if (phase == Phase.PLAYING && index == currentPlayerIndex) {
                remaining = Math.max(0, remaining - (currentTime - turnStartedAt));
            }
            playerViews.add(Map.of(
                    "id", player.id,
                    "name", player.name,
                    "remainingMillis", remaining,
                    "active", remaining > 0
            ));
        }
        long phaseRemaining = phase == Phase.READING ? Math.max(0, phaseDeadline - currentTime) : 0;
        long turnRemaining = phase == Phase.PLAYING ? Math.max(0, turnDeadline - currentTime) : 0;
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        result.put("phase", phase.name());
        result.put("story", viewer.story);
        result.put("sharedStory", sharedStory.toString());
        result.put("players", playerViews);
        result.put("expectedPlayers", expectedPlayers);
        result.put("currentPlayerId", phase == Phase.PLAYING ? players.get(currentPlayerIndex).id : null);
        result.put("phaseRemainingMillis", phaseRemaining);
        result.put("turnRemainingMillis", turnRemaining);
        result.put("scores", scoreViews());
        return result;
    }

    public synchronized Phase phase() {
        processDeadlines();
        return phase;
    }

    public synchronized List<Entry> entriesFor(String playerId) {
        return entries.stream().filter(entry -> entry.playerId.equals(playerId)).toList();
    }

    public synchronized List<PlayerForScoring> playersForScoring() {
        return players.stream().map(player -> new PlayerForScoring(player.id, player.name, player.story)).toList();
    }

    public synchronized void assignStories(Map<String, String> storiesByPlayerId) {
        if (phase != Phase.WAITING || players.size() != expectedPlayers) {
            throw new GameException(409, "Stories can only be assigned after all players join");
        }
        Map<String, String> incoming = new HashMap<>(storiesByPlayerId);
        for (Player player : players) {
            String story = incoming.remove(player.id);
            if (story == null || story.isBlank()) {
                throw new GameException(400, "Missing story for player " + player.name);
            }
            player.story = story;
        }
        if (!incoming.isEmpty()) {
            throw new GameException(400, "Received stories for unknown players");
        }
        phase = Phase.READING;
        phaseDeadline = now() + readDurationMillis;
        if (readDurationMillis == 0) {
            beginPlaying();
        }
    }

    public synchronized String sharedStory() {
        return sharedStory.toString();
    }

    public synchronized boolean beginScoring() {
        processDeadlines();
        if (phase != Phase.SCORING || !scores.isEmpty()) {
            return false;
        }
        scores = List.of(new Score("", "", -1, -1));
        return true;
    }

    public synchronized void finishScoring(Map<String, Integer> calculatedScores) {
        if (phase != Phase.SCORING) {
            return;
        }
        ArrayList<Score> ranked = new ArrayList<>();
        players.stream()
                .sorted(Comparator.comparingInt((Player player) -> calculatedScores.getOrDefault(player.id, 0)).reversed()
                        .thenComparing(player -> player.name))
                .forEach(player -> ranked.add(new Score(player.id, player.name,
                        Math.clamp(calculatedScores.getOrDefault(player.id, 0), 0, 100), ranked.size() + 1)));
        scores = List.copyOf(ranked);
        phase = Phase.FINISHED;
    }

    public static boolean isValidToken(String token) {
        return token != null && (WORD.matcher(token).matches() || PUNCTUATION.matcher(token).matches());
    }

    public static void validatePlayerName(String name) {
        if (name == null || !PLAYER_NAME.matcher(name).matches()) {
            throw new GameException(400, "Name must be 1-24 visible ASCII characters without spaces");
        }
    }

    private void processDeadlines() {
        long currentTime = now();
        if (phase == Phase.READING && currentTime >= phaseDeadline) {
            beginPlaying();
        }
        int guard = players.size() + 1;
        while (phase == Phase.PLAYING && currentTime >= turnDeadline && guard-- > 0) {
            debitCurrentPlayer(turnDeadline);
            advanceTurn();
            currentTime = now();
        }
    }

    private void beginPlaying() {
        phase = Phase.PLAYING;
        currentPlayerIndex = random.nextInt(players.size());
        startTurn();
    }

    private void debitCurrentPlayer(long endTime) {
        Player current = players.get(currentPlayerIndex);
        current.remainingMillis = Math.max(0, current.remainingMillis - Math.max(0, endTime - turnStartedAt));
    }

    private void advanceTurn() {
        int nextIndex = currentPlayerIndex;
        for (int checked = 0; checked < players.size(); checked++) {
            nextIndex = (nextIndex + 1) % players.size();
            if (players.get(nextIndex).remainingMillis > 0) {
                currentPlayerIndex = nextIndex;
                startTurn();
                return;
            }
        }
        currentPlayerIndex = -1;
        phase = Phase.SCORING;
    }

    private void startTurn() {
        turnStartedAt = now();
        Player current = players.get(currentPlayerIndex);
        turnDeadline = turnStartedAt + Math.min(turnDurationMillis, current.remainingMillis);
    }

    private void appendToken(String token) {
        if (!sharedStory.isEmpty() && WORD.matcher(token).matches()) {
            sharedStory.append(' ');
        }
        sharedStory.append(token);
    }

    private Player findPlayer(String playerId) {
        return players.stream().filter(player -> player.id.equals(playerId)).findFirst()
                .orElseThrow(() -> new GameException(404, "Unknown player"));
    }

    private List<Map<String, Object>> scoreViews() {
        if (phase != Phase.FINISHED) {
            return List.of();
        }
        return scores.stream().map(score -> Map.<String, Object>of(
                "playerId", score.playerId,
                "name", score.name,
                "score", score.score,
                "rank", score.rank
        )).toList();
    }

    private long now() {
        return clock.millis();
    }

    public record PlayerForScoring(String id, String name, String story) {
    }

    private static final class Player {
        private final String id;
        private final String name;
        private String story;
        private long remainingMillis;

        private Player(String id, String name, String story, long remainingMillis) {
            this.id = id;
            this.name = name;
            this.story = story;
            this.remainingMillis = remainingMillis;
        }
    }

    public static final class GameException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;
        private final int statusCode;

        public GameException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        public int statusCode() {
            return statusCode;
        }
    }
}