package backend.storyweave;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

final class RemoteStoryServiceTest {
    public static void main(String[] arguments) throws Exception {
        Map<String, String> options = parseArguments(arguments);
        String endpoint = requiredOption(options, "url");
        String apiKey = requiredOption(options, "key");
        String model = requiredOption(options, "model");

        RemoteStoryService service = new RemoteStoryService(endpoint, apiKey, model);
        int score = service.scoreSimilarity("A silver train crossed the moonlit bridge.",
                "A silver train crossed the moonlit bridge.");
        check(score >= 0 && score <= 100, "score should be an integer in [0, 100]");
        System.out.println("Structured integer score: " + score);
        System.out.println("LLM call log: " + ServerLogger.llmLogFile().toAbsolutePath());
    }

    static void run() throws Exception {
        AtomicReference<Map<String, Object>> requestPayload = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/chat", exchange -> {
            Map<String, Object> payload = Json.parseObject(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requestPayload.set(payload);
            String content = payload.containsKey("response_format")
                    ? "{\"value\":73}"
                    : "A clockwork forest woke at dawn.";
            byte[] response = Json.stringify(Map.of(
                            "choices", List.of(Map.of("message", Map.of("content", content)))))
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try {
            RemoteStoryService service = new RemoteStoryService(
                    "http://localhost:" + server.getAddress().getPort() + "/chat", "test-key", "test-model");
            String previousStory = "Variation 1:\nThe first clockwork forest story.";
            String story = service.createStory("clockwork forest", previousStory, 1, 3);
            check("A clockwork forest woke at dawn.".equals(story), "generated story should be returned");

            Map<String, Object> storyPayload = requestPayload.get();
            check(((Number) storyPayload.get("max_tokens")).intValue() == 3000,
                    "story generation should allow 3000 tokens");
            check(!storyPayload.containsKey("response_format"),
                    "story generation should not request integer output");
            Map<?, ?> storyMessage = (Map<?, ?>) ((List<?>) storyPayload.get("messages")).getFirst();
            String prompt = String.valueOf(storyMessage.get("content"));
            check(prompt.contains("clockwork forest"), "story prompt should contain the theme");
            check(prompt.contains("Variation number: 2 of 3"),
                    "story prompt should contain a one-based variation number and player count");
            check(prompt.contains(previousStory), "story prompt should contain previously generated story context");
            check(prompt.contains("first sentence") && prompt.contains("identical"),
                    "story prompt should require an identical common opening sentence");
            check(prompt.contains("main character") && prompt.contains("setting") && prompt.contains("background"),
                    "story prompt should define what the common sentence introduces");
            check(prompt.contains("follow-up"),
                    "story prompt should require distinct follow-ups after the common sentence");

            check(service.scoreSimilarity("shared", "reference") == 73, "integer score should be parsed");

            Map<?, ?> responseFormat = (Map<?, ?>) requestPayload.get().get("response_format");
            check("json_schema".equals(responseFormat.get("type")), "scoring should request JSON Schema output");
            Map<?, ?> jsonSchema = (Map<?, ?>) responseFormat.get("json_schema");
            check(Boolean.TRUE.equals(jsonSchema.get("strict")), "score schema should be strict");
            Map<?, ?> schema = (Map<?, ?>) jsonSchema.get("schema");
            check("object".equals(schema.get("type")), "score output should be restricted to a JSON object");
            Map<?, ?> properties = (Map<?, ?>) schema.get("properties");
            Map<?, ?> valueProperty = (Map<?, ?>) properties.get("value");
            check("integer".equals(valueProperty.get("type")),
                    "score output field 'value' should be restricted to an integer");
            check(((Number) valueProperty.get("minimum")).intValue() == 1,
                    "score schema should have a minimum of 1");
            check(((Number) valueProperty.get("maximum")).intValue() == 100,
                    "score schema should have a maximum of 100");
            check(List.of("value").equals(schema.get("required")),
                    "score schema should require only the 'value' field");
            check(Boolean.FALSE.equals(schema.get("additionalProperties")),
                    "score schema should disallow additional fields");
        } finally {
            server.stop(0);
        }
    }

    private static Map<String, String> parseArguments(String[] arguments) {
        HashMap<String, String> options = new HashMap<>();
        for (int index = 0; index < arguments.length; index++) {
            String argument = arguments[index];
            if (!argument.startsWith("--") || index + 1 >= arguments.length) {
                throw new IllegalArgumentException(
                        "Usage: RemoteStoryServiceTest --url <url> --key <api-key> --model <model>");
            }
            options.put(argument.substring(2), arguments[++index]);
        }
        List<String> supportedOptions = List.of("url", "key", "model");
        options.keySet().stream()
                .filter(option -> !supportedOptions.contains(option))
                .findFirst()
                .ifPresent(option -> {
                    throw new IllegalArgumentException("Unknown option: --" + option);
                });
        return options;
    }

    private static String requiredOption(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("--" + name + " is required");
        }
        return value.strip();
    }

    private static void check(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }
}