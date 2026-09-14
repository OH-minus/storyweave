package backend.storyweave;

import java.io.Console;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.CountDownLatch;

public final class Main {
    private Main() {
    }

    public static void main(String[] arguments) throws Exception {
        Map<String, String> options = parseArguments(arguments);
        boolean local = options.containsKey("local");
        StoryService storyService;
        if (local) {
            storyService = new LocalStoryService();
            System.out.println("Using the local story service (no remote LLM calls). ");
        } else {
            String endpoint = requiredInput(options, "url", "Remote LLM chat-completions URL: ", false);
            String apiKey = requiredInput(options, "key", "Remote LLM API key: ", true);
            String model = requiredInput(options, "model", "Remote LLM model name: ", false);
            RemoteStoryService remote = new RemoteStoryService(endpoint, apiKey, model);
            System.out.println("Verifying remote LLM credentials...");
            remote.verify();
            storyService = remote;
            System.out.println("Remote LLM connected.");
        }

        String theme = requiredInput(options, "theme", "Story theme: ", false);
        int port = integerOption(options, "port", 8080);
        int playerCount = requiredIntegerOption(options, "players", "Player count: ");
        int readSeconds = integerOption(options, "read-seconds", 60);
        int playerSeconds = integerOption(options, "player-seconds", 90);
        int turnSeconds = integerOption(options, "turn-seconds", 30);
        GameEngine game = new GameEngine(playerCount, readSeconds, playerSeconds, turnSeconds);
        try (GameServer server = new GameServer(port, game, storyService, theme, playerCount)) {
            server.start();
            Runtime.getRuntime().addShutdownHook(new Thread(server::close));
            System.out.println("Story game server started on http://localhost:" + server.port());
            System.out.println("HTTP log: " + ServerLogger.httpLogFile().toAbsolutePath());
            System.out.println("LLM call log: " + ServerLogger.llmLogFile().toAbsolutePath());
            System.out.println("Waiting for " + playerCount + " players. Press Ctrl+C to stop.");
            new CountDownLatch(1).await();
        }
    }

    private static Map<String, String> parseArguments(String[] arguments) {
        HashMap<String, String> options = new HashMap<>();
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (!argument.startsWith("--")) {
                throw new IllegalArgumentException("Unexpected argument: " + argument);
            }
            String key = argument.substring(2);
            if (key.equals("local")) {
                options.put(key, "true");
            } else if (index + 1 < arguments.length) {
                options.put(key, arguments[++index]);
            } else {
                throw new IllegalArgumentException("Missing value for " + argument);
            }
        }
        return options;
    }

    private static String requiredInput(Map<String, String> options, String key, String prompt, boolean secret) {
        String configured = options.get(key);
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        System.out.print(prompt);
        Console console = System.console();
        String input;
        if (secret && console != null) {
            char[] password = console.readPassword();
            input = password == null ? null : new String(password);
        } else if (console != null) {
            input = console.readLine();
        } else {
            input = new Scanner(System.in).nextLine();
        }
        if (input == null || input.isBlank()) {
            throw new IllegalArgumentException(prompt.replace(": ", "") + " is required");
        }
        return input.strip();
    }

    private static int integerOption(Map<String, String> options, String key, int defaultValue) {
        try {
            return Integer.parseInt(options.getOrDefault(key, Integer.toString(defaultValue)));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + key + " must be an integer", exception);
        }
    }

    private static int requiredIntegerOption(Map<String, String> options, String key, String prompt) {
        try {
            return Integer.parseInt(requiredInput(options, key, prompt, false));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("--" + key + " must be an integer", exception);
        }
    }
}