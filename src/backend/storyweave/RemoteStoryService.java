package backend.storyweave;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Client for an OpenAI-compatible chat-completions endpoint. */
public final class RemoteStoryService implements StoryService {
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final HttpClient client;

    public RemoteStoryService(String endpoint, String apiKey, String model) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("The LLM URL is required");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("The API key is required");
        }
        this.endpoint = URI.create(endpoint);
        this.apiKey = apiKey;
        this.model = model;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void verify() throws IOException, InterruptedException {
        complete("Reply with only OK.", 8);
    }

    @Override
    public String createStory(String theme, String storyContext, int version, int playerCount) throws Exception {
        String prompt = PromptTemplates.load("story-generation.md", Map.of(
                "theme", theme,
                "storyContext", storyContext,
                "versionIndex", Integer.toString(version + 1),
                "playerCount", Integer.toString(playerCount)
        ));
        return complete(prompt, 3000, false).strip();
    }

    @Override
    public int scoreSimilarity(String sharedStory, String referenceStory) throws Exception {
        String prompt = PromptTemplates.load("similarity-score.md", Map.of(
                "sharedStory", sharedStory,
                "referenceStory", referenceStory
        ));
        return parseScore(complete(prompt, 20, true, true));
    }

    @Override
    public int scoreDeduction(String sharedStory, List<GameEngine.Entry> entries) throws Exception {
        String insertedTokens = entries.stream().map(GameEngine.Entry::token).toList().toString();
        String prompt = PromptTemplates.load("error-deduction.md", Map.of(
                "sharedStory", sharedStory,
                "insertedTokens", insertedTokens
        ));
        return parseScore(complete(prompt, 20, true, true));
    }

    private String complete(String prompt, int maxTokens) throws IOException, InterruptedException {
        return complete(prompt, maxTokens, true, false);
    }

    private String complete(String prompt, int maxTokens, boolean disableReasoning) throws IOException, InterruptedException {
        return complete(prompt, maxTokens, disableReasoning, false);
    }

    private String complete(String prompt, int maxTokens, boolean disableReasoning, boolean integerOutput)
            throws IOException, InterruptedException {
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", model);
        payload.put("temperature", 0.4);
        payload.put("max_tokens", maxTokens);
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        if (supportsReasoningToggle(model)) {
            payload.put("thinking", Map.of("type", disableReasoning ? "disabled" : "enabled"));
        }
        if (integerOutput) {
            payload.put("response_format", Map.of(
                    "type", "json_schema",
                    "json_schema", Map.of(
                            "name", "score",
                            "strict", true,
                            "schema", Map.of(
                                    "type", "integer",
                                    "minimum", 0,
                                    "maximum", 100
                            )
                    )
            ));
        }
        ServerLogger.logLlmRequest(endpoint.toString(), model, maxTokens, prompt);
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(payload)))
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        ServerLogger.logLlmResponse(endpoint.toString(), response.statusCode(), response.body());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(describeHttpError(response.statusCode(), response.body()));
        }
        try {
            Map<String, Object> body = Json.parseObject(response.body());
            List<?> choices = (List<?>) body.get("choices");
            Map<?, ?> firstChoice = (Map<?, ?>) choices.getFirst();
            Map<?, ?> message = (Map<?, ?>) firstChoice.get("message");
            return String.valueOf(message.get("content"));
        } catch (RuntimeException exception) {
            throw new IOException("LLM response did not contain choices[0].message.content", exception);
        }
    }

    private static String describeHttpError(int statusCode, String responseBody) {
        String details = extractErrorDetails(responseBody);
        if (statusCode == 503 && details.contains("code=no_providers_configured")) {
            return "LLM returned HTTP 503 (no providers configured). Configure at least one upstream provider API key "
                    + "for the selected model in your LLM dashboard, choose a model that already has a key, or run "
                    + "with --local. Remote details: " + details;
        }
        return "LLM returned HTTP " + statusCode + ": " + details;
    }

    private static String extractErrorDetails(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "(empty response body)";
        }
        try {
            Map<String, Object> parsed = Json.parseObject(responseBody);
            Object errorValue = parsed.get("error");
            if (errorValue instanceof Map<?, ?> error) {
                Object code = error.get("code");
                Object message = error.get("message");
                String codeText = code == null ? "unknown" : String.valueOf(code);
                String messageText = message == null ? responseBody : String.valueOf(message);
                return "code=" + codeText + ", message=" + messageText;
            }
        } catch (RuntimeException ignored) {
            // Keep the original body when it is not valid JSON or does not follow the expected shape.
        }
        return responseBody;
    }

    private static int parseScore(String response) throws IOException {
        String integer = response.strip();
        if (!integer.matches("-?(0|[1-9][0-9]*)")) {
            throw new IOException("LLM did not return an integer score: " + response);
        }
        try {
            return Math.clamp(Integer.parseInt(integer), 0, 100);
        } catch (NumberFormatException exception) {
            throw new IOException("LLM did not return an integer score: " + response, exception);
        }
    }

    private static boolean supportsReasoningToggle(String model) {
        return model != null && model.toLowerCase().contains("deepseek");
    }
}