# Claude Agent on AWS Lambda in Java. Event-Driven, No Framework.

---

This post walks through building a Claude agent on AWS Lambda using the
[Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java). The handler
accepts a plain JSON event, calls Claude, and returns the result. No HTTP server, no
Spring, no Quarkus. The result is a small deployment JAR that fits comfortably inside
Lambda's 50 MB package limit, and a single handler that works with the AWS Console, SQS,
EventBridge, or any other Lambda event source.

Code [github.com/sivamondi/claude-lambda-integration](https://github.com/sivamondi/claude-lambda-integration)

---

## Why Lambda works for this

Lambda invokes a function when an event arrives and freezes the environment when it is
done. A Claude agent does the same thing: it receives input, calls the model, and returns
a result. There is no long-running process required.

| LLM agent | AWS Lambda |
|---|---|
| Triggered by a message or event | Invoked by SQS, EventBridge, S3, SNS, HTTP, schedules |
| Stateless per call, context in and answer out | Stateless compute with no in-process state between invocations |
| Runs in bursts, idle otherwise | Scales to zero; billed only when running |

---

## Why the [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) fits Lambda

Lambda's deployment package limit is 50 MB zipped (250 MB unzipped). A Spring Boot or
Quarkus application with an embedded web server typically exceeds that before any business
logic is added.

The [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) is a single
library with no embedded server.

```xml
<dependency>
    <groupId>com.anthropic</groupId>
    <artifactId>anthropic-java</artifactId>
    <version>2.34.0</version>
</dependency>
```

The full dependency set for this project, SDK, two AWS Lambda runtime jars, and Jackson,
produces a shaded uber JAR well under the Lambda size limit. Cold starts are faster too,
because there is no framework scan at startup.

---

## Event format

The handler accepts a plain `Map<String, Object>`, so the same JSON structure works from
the AWS Console, from an SQS message body, or from an EventBridge rule detail.

```json
{
  "prompt":    "Your question here",
  "system":    "You are a concise assistant.",
  "maxTokens": 1024
}
```

`system` and `maxTokens` are optional.

```
  Event source (Console / SQS / EventBridge / SNS / ...)
          │
          │  { "prompt": "...", "system": "...", "maxTokens": 1024 }
          ▼
  ┌──────────────────────────────┐
  │  Lambda handler              │
  │  reads event → calls Claude  │
  └──────────────┬───────────────┘
                 │
                 ▼
           Anthropic API
```

---

## The handler

### Initialise the client once

Lambda reuses the same handler instance across warm invocations. Create the SDK client as
a field so it is initialised once and the connection pool is reused on every warm call.

```java
public class ClaudeEventHandler
        implements RequestHandler<Map<String, Object>, Map<String, Object>> {

    // Initialised once per execution environment; reused on warm invocations.
    // Reads ANTHROPIC_API_KEY from the environment.
    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();
}
```

Creating it inside the handler method would re-establish the connection on every request.

### Read the event and call Claude

```java
@Override
public Map<String, Object> handleRequest(Map<String, Object> event, Context context) {
    String prompt = (String) event.get("prompt");
    if (prompt == null || prompt.isBlank()) {
        return error("Missing 'prompt' in event.");
    }

    String system    = (String) event.get("system");
    long   maxTokens = longFrom(event.get("maxTokens"), 1024L);

    MessageCreateParams.Builder params = MessageCreateParams.builder()
            .model(MODEL)          // default: claude-opus-4-8; override with ANTHROPIC_MODEL env var
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
    out.put("reply",        reply);
    out.put("model",        MODEL);
    out.put("inputTokens",  message.usage().inputTokens());
    out.put("outputTokens", message.usage().outputTokens());
    return out;
}
```

The response includes token counts so you can track cost per invocation.

---

## Testing from the AWS Console

After deploying, open **Lambda → Functions → claude-agent → Test**.
Paste `events/simple-event.json` as the test payload.

```json
{
  "prompt": "Explain in two sentences why AWS Lambda is a natural fit for AI agents.",
  "system": "You are a concise technical writer.",
  "maxTokens": 512
}
```

Click **Test**. The result panel shows the response.

```json
{
  "reply": "...",
  "model": "claude-opus-4-8",
  "inputTokens": 42,
  "outputTokens": 61
}
```

Or use the CLI.

```bash
aws lambda invoke \
  --function-name claude-agent \
  --payload file://events/simple-event.json \
  --cli-binary-format raw-in-base64-out \
  response.json && cat response.json
```

---

## Adding tools: the agent loop

A single `create()` call is one turn. To let Claude call external tools and reason over
the results before answering, the [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java)
provides `BetaToolRunner`, which drives the model tool loop automatically.

A tool is a class that implements `Supplier<String>`. Its `get()` method runs the tool
and returns the result. Annotations provide the JSON schema Claude uses to call it.

```java
@JsonClassDescription("Performs one arithmetic operation on two numbers.")
public static class Calculator implements Supplier<String> {

    @JsonPropertyDescription("The operation: add, subtract, multiply, or divide.")
    public String operation;

    @JsonPropertyDescription("The first operand.")
    public double a;

    @JsonPropertyDescription("The second operand.")
    public double b;

    @Override
    public String get() {
        switch (operation) {
            case "add":      return String.valueOf(a + b);
            case "subtract": return String.valueOf(a - b);
            case "multiply": return String.valueOf(a * b);
            case "divide":   return b == 0 ? "Error: division by zero" : String.valueOf(a / b);
            default:         return "Error: unknown operation " + operation;
        }
    }
}
```

Register the tools and iterate the runner. Each iteration is one model turn; the SDK
calls the tools and feeds results back to Claude between turns.

```java
BetaToolRunner runner = client.beta().messages().toolRunner(
        MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(maxTokens)
                .addUserMessage(prompt)
                .addTool(Calculator.class)
                .addTool(CurrentTime.class)
                .build());

BetaMessage last = null;
int turns = 0;
for (BetaMessage turn : runner) {
    last = turn;
    turns++;
}
```

Test with `events/agent-event.json`.

```json
{
  "prompt": "What is 1234 multiplied by 5678? Also, what is the current time in New York?",
  "system": "You are a helpful assistant with calculation and time-lookup tools.",
  "maxTokens": 1024
}
```

Sample response.

```json
{
  "reply": "1234 × 5678 = 7,006,652. The current time in New York is 10:45:33 EDT.",
  "model": "claude-opus-4-8",
  "turns": 3,
  "inputTokens": 824,
  "outputTokens": 148
}
```

`turns` shows how many model round-trips the agent made. The deployment does not change
when you add tools; it is still the same JAR, same handler, same Lambda function.

---

## Connecting to SQS or EventBridge

Because the handler takes a `Map<String, Object>`, connecting it to a real event source
requires no code changes.

For **SQS**, attach the queue as an event source mapping. Lambda parses each SQS record's
JSON body into the map automatically.

```bash
aws lambda create-event-source-mapping \
  --function-name claude-agent \
  --event-source-arn arn:aws:sqs:<region>:<account>:your-queue \
  --batch-size 1
```

Send a message to the queue.

```json
{"prompt": "Summarize this support ticket: user reports slow checkout on mobile."}
```

For **EventBridge**, create a rule targeting the Lambda. The `detail` object in your event
becomes the map the handler receives. A scheduled rule with a cron expression turns the
agent into a periodic job.

For **SNS, S3, or custom invokers** the same pattern applies. The event source adapter is
thin because the handler input is just a map.

---

## Handling state

Lambda does not preserve in-process state between invocations. For agents that need
memory across turns, keep state outside the function.

- Store **conversation history** in DynamoDB keyed by session ID; load at the start of
  each turn, append the new exchange, and write back.
- For **long-term knowledge**, query a vector store or external API as a tool.
- Retrieve **secrets** from AWS Secrets Manager or SSM Parameter Store at init time,
  once per warm environment and not per request.

---

## Configuration tips

- **Model selection** is controlled by the `ANTHROPIC_MODEL` environment variable, which
  switches models without a code change. Use `claude-haiku-4-5` for high-volume or
  latency-sensitive workloads; it is faster and cheaper than Opus.
- Set **`maxTokens`** as a cost and latency ceiling appropriate to each use case.
- Increasing Lambda **memory** also increases CPU, which speeds up cold starts and JSON
  processing. 1024 MB is a reasonable starting point for this workload.

---

## Summary

The [Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) is small enough
to package as a Lambda deployment JAR without hitting size limits. The handler is a single
class with no framework dependencies. The plain-map event format works from the AWS Console
for testing and connects to SQS, EventBridge, or any other Lambda trigger with no code
changes. Adding tools upgrades the handler from a single model call to a full agent loop,
with no change to the deployment.

**SDK** [github.com/anthropics/anthropic-sdk-java](https://github.com/anthropics/anthropic-sdk-java)

**Code** [github.com/sivamondi/claude-lambda-integration](https://github.com/sivamondi/claude-lambda-integration)
