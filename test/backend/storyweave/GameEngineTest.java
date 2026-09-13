package backend.storyweave;

import java.util.List;
import java.util.Map;
import java.util.Random;

final class GameEngineTest {
    static void run() {
        validatesNamesAndTokens();
        startsReadingAfterStoriesAreAssigned();
        runsTurnsAndBuildsStory();
        expiresTimersAndRanksScores();
    }

    private static void validatesNamesAndTokens() {
        GameEngine game = new GameEngine(2, 10, 30, 5);
        expectGameError(400, () -> game.join("has space", "Story"));
        expectGameError(400, () -> game.join("Åsa", "Story"));
        game.join("ALICE", "Story A");
        expectGameError(409, () -> game.join("alice", "Story B"));

        check(GameEngine.isValidToken("hello"), "a word should be valid");
        check(GameEngine.isValidToken("isn't"), "an apostrophized word should be valid");
        check(GameEngine.isValidToken("!"), "one punctuation mark should be valid");
        check(!GameEngine.isValidToken("two words"), "spaces should be rejected");
        check(!GameEngine.isValidToken("!!"), "multiple punctuation marks should be rejected");
        check(!GameEngine.isValidToken("123"), "numbers should not be accepted as words");
    }

    private static void startsReadingAfterStoriesAreAssigned() {
        MutableClock clock = new MutableClock(1_000);
        GameEngine game = new GameEngine(2, 20, 30, 5, clock, new Random(0));
        GameEngine.JoinResult alice = game.join("Alice", "");
        GameEngine.JoinResult bob = game.join("Bob", "");

        clock.advanceMillis(30_000);
        check(game.phase() == GameEngine.Phase.WAITING,
                "reading should not start while private stories are being generated");

        game.assignStories(Map.of(alice.playerId(), "Story A", bob.playerId(), "Story B"));
        Map<String, Object> state = game.snapshot(alice.playerId());
        check(state.get("phase").equals("READING"), "story assignment should start reading");
        check(state.get("phaseRemainingMillis").equals(20_000L),
                "reading should start with its full configured duration");
    }

    private static void runsTurnsAndBuildsStory() {
        MutableClock clock = new MutableClock(1_000);
        GameEngine game = new GameEngine(2, 0, 20, 5, clock, new Random(4));
        GameEngine.JoinResult alice = game.join("Alice", "Alice reference");
        GameEngine.JoinResult bob = game.join("Bob", "Bob reference");
        game.assignStories(Map.of(alice.playerId(), "Alice reference", bob.playerId(), "Bob reference"));
        Map<String, Object> state = game.snapshot(alice.playerId());
        check(state.get("phase").equals("PLAYING"), "zero reading time should start play immediately");
        check(state.get("story").equals("Alice reference"), "a player should see only their reference story");

        String current = (String) state.get("currentPlayerId");
        String waiting = current.equals(alice.playerId()) ? bob.playerId() : alice.playerId();
        expectGameError(403, () -> game.submit(waiting, "Wrong"));
        expectGameError(400, () -> game.submit(current, "two words"));
        game.submit(current, "Once");
        String second = (String) game.snapshot(alice.playerId()).get("currentPlayerId");
        game.submit(second, ",");
        String third = (String) game.snapshot(alice.playerId()).get("currentPlayerId");
        game.submit(third, "together");
        check(game.sharedStory().equals("Once, together"), "words and punctuation should be spaced correctly");
        check(!game.entriesFor(current).isEmpty(), "contributions should be attributed to players");
    }

    private static void expiresTimersAndRanksScores() {
        MutableClock clock = new MutableClock(20_000);
        GameEngine game = new GameEngine(2, 0, 1, 1, clock, new Random(0));
        GameEngine.JoinResult alice = game.join("Alice", "Reference A");
        GameEngine.JoinResult bob = game.join("Bob", "Reference B");
        game.assignStories(Map.of(alice.playerId(), "Reference A", bob.playerId(), "Reference B"));
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