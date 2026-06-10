package com.cogniaix.claude;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPEvent;
import com.amazonaws.services.lambda.runtime.events.APIGatewayV2HTTPResponse;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A lightweight <em>tool-using</em> Claude agent on AWS Lambda.
 *
 * <p>This is the same shape as {@link HelloClaudeHandler} — one stateless handler behind a
 * Function URL — but instead of a single Messages call, it gives Claude tools and lets the
 * SDK's {@link BetaToolRunner} drive the observe → reason → act loop until Claude has an
 * answer. That loop is what turns a single call into an agent.
 *
 * <p>The tools below are deliberately trivial and dependency-free (current time + a
 * calculator) so the example stays lightweight. A tool is just a class that:
 * <ul>
 *   <li>implements {@link Supplier}&lt;String&gt; — its {@code get()} runs the tool and
 *       returns the result the SDK feeds back to Claude,</li>
 *   <li>is annotated with {@code @JsonClassDescription} (what the tool does),</li>
 *   <li>has fields annotated with {@code @JsonPropertyDescription} (the tool's parameters).</li>
 * </ul>
 * The SDK derives the tool's JSON schema from the class and its name from the class name
 * (e.g. {@code CurrentTime} → {@code current_time}).
 *
 * <p>Lambda handler string: {@code com.cogniaix.claude.ToolAgentHandler::handleRequest}
 */
public class ToolAgentHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String MODEL = envOrDefault("ANTHROPIC_MODEL", "claude-opus-4-8");
    private static final long DEFAULT_MAX_TOKENS = 1024L;

    /** Built once per execution environment and reused across warm invocations. */
    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    // ---- Tools -------------------------------------------------------------------------

    @JsonClassDescription("Returns the current date and time. Use whenever the user asks "
            + "what the time or date is right now.")
    public static class CurrentTime implements Supplier<String> {
        @JsonPropertyDescription("IANA timezone id such as 'America/New_York' or 'UTC'. "
                + "Defaults to UTC when omitted.")
        public String timezone;

        @Override
        public String get() {
            ZoneId zone;
            try {
                zone = (timezone == null || timezone.isBlank())
                        ? ZoneOffset.UTC : ZoneId.of(timezone);
            } catch (Exception e) {
                zone = ZoneOffset.UTC;
            }
            return ZonedDateTime.now(zone)
                    .format(DateTimeFormatter.ofPattern("EEEE, yyyy-MM-dd HH:mm:ss zzz"));
        }
    }

    @JsonClassDescription("Performs one exact arithmetic operation on two numbers. "
            + "Use this for any precise calculation instead of doing the math yourself.")
    public static class Calculator implements Supplier<String> {
        @JsonPropertyDescription("The operation: one of add, subtract, multiply, divide.")
        public String operation;

        @JsonPropertyDescription("The first operand.")
        public double a;

        @JsonPropertyDescription("The second operand.")
        public double b;

        @Override
        public String get() {
            double result;
            switch (operation == null ? "" : operation.toLowerCase()) {
                case "add":      result = a + b; break;
                case "subtract": result = a - b; break;
                case "multiply": result = a * b; break;
                case "divide":
                    if (b == 0) return "Error: division by zero";
                    result = a / b; break;
                default:
                    return "Error: unknown operation '" + operation + "'";
            }
            return String.valueOf(result);
        }
    }

    // ---- Handler -----------------------------------------------------------------------

    @Override
    public APIGatewayV2HTTPResponse handleRequest(APIGatewayV2HTTPEvent event, Context context) {
        LambdaLogger log = context.getLogger();
        try {
            ClaudeRequest request = parseBody(event);
            if (request == null || request.prompt == null || request.prompt.isBlank()) {
                return json(400, Map.of(
                        "error", "Missing 'prompt' in request body.",
                        "usage", "POST a JSON body like "
                                + "{\"prompt\": \"What is 1234 * 5678, and what time is it?\"}"));
            }

            long maxTokens = (request.maxTokens != null && request.maxTokens > 0)
                    ? request.maxTokens
                    : DEFAULT_MAX_TOKENS;

            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(maxTokens)
                    // Strict, schema-validated tool inputs (matches the SDK's tool-runner example).
                    .putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13")
                    .addUserMessage(request.prompt)
                    .addTool(CurrentTime.class)
                    .addTool(Calculator.class);

            if (request.system != null && !request.system.isBlank()) {
                params.system(request.system);
            }

            // The tool runner IS the agent loop: each iteration is one model turn, with the
            // SDK executing any requested tools in between and feeding results back to Claude.
            BetaToolRunner runner = client.beta().messages().toolRunner(params.build());

            BetaMessage last = null;
            int turns = 0;
            long inputTokens = 0;
            long outputTokens = 0;
            for (BetaMessage message : runner) {
                last = message;
                turns++;
                inputTokens += message.usage().inputTokens();
                outputTokens += message.usage().outputTokens();
            }

            if (last == null) {
                return json(502, Map.of("error", "The agent produced no response."));
            }

            String reply = last.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(textBlock -> textBlock.text())
                    .collect(Collectors.joining());

            AgentResponse out = new AgentResponse();
            out.reply = reply;
            out.model = MODEL;
            out.turns = turns;
            out.inputTokens = inputTokens;
            out.outputTokens = outputTokens;

            return json(200, out);

        } catch (Exception e) {
            log.log("Error handling request: " + e);
            return json(500, Map.of("error", e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    // ---- Helpers -----------------------------------------------------------------------

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
