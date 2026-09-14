package backend.storyweave;

import java.util.List;
import java.util.Map;
import java.util.Random;

final class GameEngineTest {
    static void run() {
        validatesNamesAndTokens();
        preparesPlayersAndAllowsPreGameRenames();
        rejoinsPlayersAndSkipsQuitTurns();
        startsReadingAfterStoriesAreAssigned();
        runsTurnsAndBuildsStory();
        expiresTimersAndRanksScores();
    }

    private static void validatesNamesAndTokens() {
        GameEngine game = new GameEngine(2, 10, 30, 5);
        expectGameError(400, () -> game.join("has space", "Story"));
        expectGameError(400, () -> game.join("Åsa", "Story"));

        check(GameEngine.isValidToken("hello"), "a word should be valid");
        check(GameEngine.isValidToken("isn't"), "an apostrophized word should be valid");
        check(GameEngine.isValidToken("!"), "one punctuation mark should be valid");
        check(!GameEngine.isValidToken("two words"), "spaces should be rejected");
        check(!GameEngine.isValidToken("!!"), "multiple punctuation marks should be rejected");
        check(!GameEngine.isValidToken("123"), "numbers should not be accepted as words");
    }

    private static void preparesPlayersAndAllowsPreGameRenames() {
        GameEngine game = new GameEngine(2, 10, 30, 5);
        GameEngine.JoinResult alice = game.join("Alice", "");
        check(game.phase() == GameEngine.Phase.WAITING, "an incomplete lobby should keep waiting");
        GameEngine.JoinResult bob = game.join("Bob", "");
        check(game.phase() == GameEngine.Phase.PREPARATION, "a full lobby should enter preparation");

        game.rename(alice.playerId(), alice.connectionId(), "Alicia");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> renamedPlayers =
                (List<Map<String, Object>>) game.snapshot(alice.playerId()).get("players");
        check(renamedPlayers.stream().anyMatch(player -> player.get("name").equals("Alicia")),
                "players should be able to rename themselves before the game starts");
        expectGameError(409, () -> game.rename(bob.playerId(), bob.connectionId(), "ALICIA"));
        expectGameError(409, () -> game.ready(alice.playerId(), "stale-connection"));

        check(!game.ready(alice.playerId(), alice.connectionId()),
                "one ready player should not start a two-player game");
        expectGameError(409, () -> game.assignStories(Map.of(
                alice.playerId(), "The gate opened. Story A",
                bob.playerId(), "The gate opened. Story B"), "The gate opened."));
        check(game.ready(bob.playerId(), bob.connectionId()), "the final ready player should allow the game to start");
        game.cancelReady(bob.playerId(), bob.connectionId());
        expectGameError(409, () -> game.assignStories(Map.of(
                alice.playerId(), "The gate opened. Story A",
                bob.playerId(), "The gate opened. Story B"), "The gate opened."));
        check(game.ready(bob.playerId(), bob.connectionId()),
                "a canceled final ready action should be retryable after generation fails");
        game.assignStories(Map.of(
                alice.playerId(), "The gate opened. Story A",
                bob.playerId(), "The gate opened. Story B"), "The gate opened.");
        expectGameError(409, () -> game.rename(alice.playerId(), alice.connectionId(), "Alice"));
    }

    private static void rejoinsPlayersAndSkipsQuitTurns() {
        GameEngine game = new GameEngine(2, 0, 30, 5, new MutableClock(1_000), new Random(0));
        GameEngine.JoinResult alice = game.join("Alice", "Alice reference");
        GameEngine.JoinResult rejoinedAlice = game.join("alice", "replacement story");
        check(rejoinedAlice.playerId().equals(alice.playerId()),
                "rejoining with the same name should restore the existing player identity");
        check(rejoinedAlice.story().equals("Alice reference"),
                "rejoining should preserve the player's private story");

        GameEngine.JoinResult bob = game.join("Bob", "Bob reference");
        game.ready(alice.playerId(), rejoinedAlice.connectionId());
        game.ready(bob.playerId(), bob.connectionId());
        game.assignStories(Map.of(
                alice.playerId(), "The bell rang. Alice reference",
                bob.playerId(), "The bell rang. Bob reference"), "The bell rang.");
        String current = String.valueOf(game.snapshot(alice.playerId()).get("currentPlayerId"));
        String currentConnectionId = current.equals(alice.playerId())
                ? rejoinedAlice.connectionId()
                : bob.connectionId();
        game.quit(current, currentConnectionId);

        Map<String, Object> state = game.snapshot(alice.playerId());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> players = (List<Map<String, Object>>) state.get("players");
        Map<String, Object> quitter = players.stream()
                .filter(player -> player.get("id").equals(current))
                .findFirst()
                .orElseThrow();
        check(quitter.get("quit").equals(true), "a player who exits should be marked as quit");
        check(!state.get("currentPlayerId").equals(current), "a quit player's current turn should be skipped");

        String quitterName = String.valueOf(quitter.get("name"));
        GameEngine.JoinResult restored = game.join(quitterName, "ignored");
        check(restored.playerId().equals(current), "a quit player should reclaim the same session when rejoining");
        game.quit(current, currentConnectionId);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> restoredPlayers =
                (List<Map<String, Object>>) game.snapshot(alice.playerId()).get("players");
        check(restoredPlayers.stream()
                        .filter(player -> player.get("id").equals(current))
                        .noneMatch(player -> player.get("quit").equals(true)),
                "a rejoined player should no longer be marked as quit");
    }

    private static void startsReadingAfterStoriesAreAssigned() {
        MutableClock clock = new MutableClock(1_000);
        GameEngine game = new GameEngine(2, 20, 30, 5, clock, new Random(0));
        GameEngine.JoinResult alice = game.join("Alice", "");
        GameEngine.JoinResult bob = game.join("Bob", "");

        clock.advanceMillis(30_000);
        check(game.phase() == GameEngine.Phase.PREPARATION,
                "reading should not start while private stories are being generated");

        game.ready(alice.playerId(), alice.connectionId());
        game.ready(bob.playerId(), bob.connectionId());

        expectGameError(400, () -> game.assignStories(Map.of(
                alice.playerId(), "The city woke. Story A",
                bob.playerId(), "A different opening. Story B"), "The city woke."));

        game.assignStories(Map.of(
                alice.playerId(), "The city woke. Story A",
                bob.playerId(), "The city woke. Story B"), "The city woke.");
        Map<String, Object> state = game.snapshot(alice.playerId());
        check(state.get("phase").equals("READING"), "story assignment should start reading");
        check(state.get("phaseRemainingMillis").equals(20_000L),
                "reading should start with its full configured duration");
        check(state.get("sharedStory").equals("The city woke."),
                "the common sentence should be visible as soon as reading begins");
    }

    private static void runsTurnsAndBuildsStory() {
        MutableClock clock = new MutableClock(1_000);
        GameEngine game = new GameEngine(2, 0, 20, 5, clock, new Random(4));
        GameEngine.JoinResult alice = game.join("Alice", "Alice reference");
        GameEngine.JoinResult bob = game.join("Bob", "Bob reference");
        game.ready(alice.playerId(), alice.connectionId());
        game.ready(bob.playerId(), bob.connectionId());
        game.assignStories(Map.of(
                alice.playerId(), "A storm covered the harbor. Alice reference",
                bob.playerId(), "A storm covered the harbor. Bob reference"), "A storm covered the harbor.");
        Map<String, Object> state = game.snapshot(alice.playerId());
        check(state.get("phase").equals("PLAYING"), "zero reading time should start play immediately");
        check(state.get("story").equals("A storm covered the harbor. Alice reference"),
                "a player should see only their reference story");

        String current = (String) state.get("currentPlayerId");
        String waiting = current.equals(alice.playerId()) ? bob.playerId() : alice.playerId();
        expectGameError(403, () -> game.submit(waiting, "Wrong"));
        expectGameError(400, () -> game.submit(current, "two words"));
        game.submit(current, "They");
        String second = (String) game.snapshot(alice.playerId()).get("currentPlayerId");
        game.submit(second, ",");
        String third = (String) game.snapshot(alice.playerId()).get("currentPlayerId");
        game.submit(third, "together");
        check(game.sharedStory().equals("A storm covered the harbor. They, together"),
                "player words should be appended and spaced after the common sentence");
        check(!game.entriesFor(current).isEmpty(), "contributions should be attributed to players");
    }

    private static void expiresTimersAndRanksScores() {
        MutableClock clock = new MutableClock(20_000);
        GameEngine game = new GameEngine(2, 0, 1, 1, clock, new Random(0));
        GameEngine.JoinResult alice = game.join("Alice", "Reference A");
        GameEngine.JoinResult bob = game.join("Bob", "Reference B");
        game.ready(alice.playerId(), alice.connectionId());
        game.ready(bob.playerId(), bob.connectionId());
        game.assignStories(Map.of(
                alice.playerId(), "The lantern dimmed. Reference A",
                bob.playerId(), "The lantern dimmed. Reference B"), "The lantern dimmed.");
        clock.advanceMillis(1_000);
        game.phase();
        check(game.phase() == GameEngine.Phase.PLAYING, "one active player should keep the game running");
        clock.advanceMillis(1_000);
        check(game.phase() == GameEngine.Phase.SCORING, "the game should score after every clock expires");
        check(game.beginScoring(), "scoring should be claimed only once");
        check(!game.beginScoring(), "a second scoring worker should be rejected");
        game.finishScoring(Map.of(alice.playerId(), 30, bob.playerId(), 75));
        check(game.phase() == GameEngine.Phase.FINISHED, "finishing scores should end the game");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> scores = (List<Map<String, Object>>) game.snapshot(alice.playerId()).get("scores");
        check(scores.getFirst().get("name").equals("Bob"), "the highest score should rank first");
        check(scores.getFirst().get("rank").equals(1), "the winner should have rank one");
    }

    private static void expectGameError(int status, Runnable action) {
        try {
            action.run();
            throw new AssertionError("Expected GameException with status " + status);
        } catch (GameEngine.GameException exception) {
            check(exception.statusCode() == status, "unexpected error status: " + exception.statusCode());
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}