package com.cogniaix.claude;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.LambdaLogger;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Event-driven Claude agent on AWS Lambda.
 *
 * <p>Triggered by any Lambda event source — AWS Console test, SQS, EventBridge,
 * SNS, S3 notification, or a custom scheduler. No HTTP layer required; the prompt
 * arrives as a plain JSON event object.
 *
 * <p>Event format (paste this into the AWS Console "Test" tab, or inject it from
 * your event source):
 * <pre>
 * {
 *   "prompt":    "Your question here",          // required
 *   "system":    "You are a concise assistant.", // optional
 *   "maxTokens": 1024                            // optional
 * }
 * </pre>
 *
 * <p>Lambda handler string: {@code com.cogniaix.claude.ClaudeEventHandler::handleRequest}
 *
 * <p>Required environment variable: {@code ANTHROPIC_API_KEY}.
 * Optional: {@code ANTHROPIC_MODEL} (defaults to {@code claude-opus-4-8}).
 */
public class ClaudeEventHandler implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    private static final String MODEL = envOrDefault("ANTHROPIC_MODEL", "claude-opus-4-8");
    private static final long DEFAULT_MAX_TOKENS = 1024L;

    /**
     * Built once per execution environment (during the init phase) and reused
     * across warm invocations — connection pool and all. fromEnv() reads
     * ANTHROPIC_API_KEY from the environment; no secrets in source code.
     */
    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    @Override
    public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
        LambdaLogger log = context.getLogger();
        try {
            String prompt = (String) event.get("prompt");
            if (prompt == null || prompt.isBlank()) {
                return error("Missing 'prompt' in event. "
                        + "Expected: {\"prompt\": \"Your question here\"}");
            }

            String system = (String) event.get("system");
            long maxTokens = longFrom(event.get("maxTokens"), DEFAULT_MAX_TOKENS);

            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(maxTokens)
                    .addUserMessage(prompt);

            if (system != null && !system.isBlank()) {
                params.system(system);
            }

            Message message = client.messages().create(params.build());

            String reply = message.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(textBlock -> textBlock.text())
                    .collect(Collectors.joining());

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("reply", reply);
            out.put("model", MODEL);
            out.put("inputTokens", message.usage().inputTokens());
            out.put("outputTokens", message.usage().outputTokens());
            return out;

        } catch (Exception e) {
            log.log("Error: " + e);
            return error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

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
