package com.cogniaix.claude;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A "Hello World" Claude agent on AWS Lambda.
 *
 * <p>It takes a prompt from an HTTP request (delivered through a Lambda Function URL),
 * makes a single call to the Claude Messages API using the official Anthropic Java SDK,
 * and returns Claude's reply as JSON.
 *
 * <p>Lambda handler string: {@code com.cogniaix.claude.HelloClaudeHandler::handleRequest}
 *
 * <p>Required environment variable: {@code ANTHROPIC_API_KEY}.
 * Optional: {@code ANTHROPIC_MODEL} (defaults to {@code claude-opus-4-8}).
 */
public class HelloClaudeHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** Default to the latest, most capable Claude model. Override with ANTHROPIC_MODEL
     *  (e.g. claude-haiku-4-5) for faster, cheaper responses. */
    private static final String MODEL = envOrDefault("ANTHROPIC_MODEL", "claude-opus-4-8");

    private static final long DEFAULT_MAX_TOKENS = 1024L;

    /**
     * Built once per Lambda execution environment (during the init phase) and reused
     * across warm invocations — this keeps per-request latency low. The client reads
     * ANTHROPIC_API_KEY from the environment via {@code fromEnv()}.
     */
    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        LambdaLogger log = context.getLogger();
        try {
            ClaudeRequest request = parseBody(event);
            if (request == null || request.prompt == null || request.prompt.isBlank()) {
                return json(400, Map.of(
                        "error", "Missing 'prompt' in request body.",
                        "usage", "POST a JSON body like {\"prompt\": \"Hello Claude\"}"));
            }

            long maxTokens = (request.maxTokens != null && request.maxTokens > 0)
                    ? request.maxTokens
                    : DEFAULT_MAX_TOKENS;

            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(maxTokens)
                    .addUserMessage(request.prompt);

            if (request.system != null && !request.system.isBlank()) {
                params.system(request.system);
            }

            Message message = client.messages().create(params.build());

            // A response can contain several content blocks; concatenate the text ones.
            String reply = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(textBlock -> textBlock.text())
                    .collect(Collectors.joining());

            ClaudeResponse out = new ClaudeResponse();
            out.reply = reply;
            out.model = MODEL;
            out.inputTokens = message.usage().inputTokens();
            out.outputTokens = message.usage().outputTokens();

            return json(200, out);

        } catch (Exception e) {
            log.log("Error handling request: " + e);
            return json(500, Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /** Extracts and parses the JSON body from a Function URL / HTTP API v2 event. */
    private ClaudeRequest parseBody(APIGatewayV2HTTPEvent event) throws Exception {
        if (event == null || event.getBody() == null) {
            return null;
        }
        String body = event.getBody();
        if (Boolean.TRUE.equals(event.getIsBase64Encoded())) {
            body = new String(Base64.getDecoder().decode(body), StandardCharsets.UTF_8);
        }
        if (body.isBlank()) {
            return null;
        }
        return MAPPER.readValue(body, ClaudeRequest.class);
    }

    /** Builds a JSON HTTP response in the Function URL / HTTP API v2 format. */
    private APIGatewayV2HTTPResponse json(int statusCode, Object payload) {
        String body;
        try {
            body = MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            statusCode = 500;
            body = "{\"error\":\"Failed to serialize response\"}";
        }
        return APIGatewayV2HTTPResponse.builder()
                .withStatusCode(statusCode)
                .withHeaders(Map.of("Content-Type", "application/json"))
                .withBody(body)
                .build();
    }

    private static String envOrDefault(String key, String fallback) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
