package com.cogniaix.claude;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Event-driven tool-using Claude agent on AWS Lambda.
 *
 * <p>Same event format as {@link ClaudeEventHandler}, but this handler gives Claude
 * tools and lets the SDK's {@link BetaToolRunner} drive the observe → act → observe
 * loop until Claude produces a final answer.
 *
 * <p>Triggered by any Lambda event source — AWS Console test, SQS, EventBridge,
 * SNS, or a custom scheduler. No HTTP layer required.
 *
 * <p>Event format:
 * <pre>
 * {
 *   "prompt":    "What is 1234 × 5678, and what time is it in New York?",
 *   "system":    "You are a helpful assistant.",  // optional
 *   "maxTokens": 1024                             // optional
 * }
 * </pre>
 *
 * <p>A tool is just a class that implements {@link Supplier}&lt;String&gt; — its
 * {@code get()} runs the tool — with annotations the SDK turns into JSON schema:
 * <ul>
 *   <li>{@code @JsonClassDescription} — what the tool does (shown to Claude)</li>
 *   <li>{@code @JsonPropertyDescription} — each parameter's meaning</li>
 * </ul>
 *
 * <p>Lambda handler string: {@code com.cogniaix.claude.ToolAgentHandler::handleRequest}
 */
public class ToolAgentHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

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
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        LambdaLogger log = context.getLogger();
        try {
            String prompt = (String) event.get("prompt");
            if (prompt == null || prompt.isBlank()) {
                return error("Missing 'prompt' in event. "
                        + "Expected: {\"prompt\": \"What is 99 × 99 and what time is it in Tokyo?\"}");
            }

            String system = (String) event.get("system");
            long maxTokens = longFrom(event.get("maxTokens"), DEFAULT_MAX_TOKENS);

            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(maxTokens)
                    .putAdditionalHeader("anthropic-beta", "structured-outputs-2025-11-13")
                    .addUserMessage(prompt)
                    .addTool(CurrentTime.class)
                    .addTool(Calculator.class);

            if (system != null && !system.isBlank()) {
                params.system(system);
            }

            // The tool runner IS the agent loop: each iteration is one model turn, with the
            // SDK executing any requested tools and feeding results back to Claude.
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
                return error("The agent produced no response.");
            }

            String reply = last.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(textBlock -> textBlock.text())
                    .collect(Collectors.joining());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("reply", reply);
            out.put("model", MODEL);
            out.put("turns", turns);
            out.put("inputTokens", inputTokens);
            out.put("outputTokens", outputTokens);
            return out;

        } catch (Exception e) {
            log.log("Error: " + e);
            return error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ---- Helpers -----------------------------------------------------------------------

    private static long longFrom(Object val, long fallback) {
        if (val instanceof Number) {
            long v = ((Number) val).longValue();
            return v > 0 ? v : fallback;
        }
        return fallback;
    }

    private static Map<String, Object> error(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    private static String envOrDefault(String key, String fallback) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
