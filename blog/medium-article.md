# Lightweight Claude Agents on AWS Lambda — with the Anthropic Java SDK

### Why serverless is the natural home for LLM agents, and how to build one in pure Java.

---

**TL;DR** — An LLM agent is mostly *stateless reasoning triggered by an event*. AWS Lambda
is *stateless compute triggered by an event*. The shapes match almost perfectly. With
Anthropic's official Java SDK, the entire agent collapses to a single handler class and one
API call — no framework, no server, no long-running process. This post is about that
design: the anatomy of a lightweight Claude agent on Lambda, and why the serverless model
fits agents so well. Code:
[github.com/sivamondi/claude-lambda-integration](https://github.com/sivamondi/claude-lambda-integration).

---

## Agents want to be serverless

Strip an LLM agent down to its essence and you get a simple loop:

> something happens → the agent reasons about it → it responds (and maybe acts) → it goes quiet.

Now look at AWS Lambda's execution model:

> an event arrives → your code runs → it returns a result → the environment freezes.

These are the same shape. An agent doesn't *want* to be a server that sits idle holding a
connection open at 3 a.m.; it wants to wake up when there's something to think about. That
is precisely what serverless gives you:

| An LLM agent is… | …and Lambda is… |
|---|---|
| **Event-driven** — it reacts to a message, a ticket, a file upload | Invoked by events: HTTP, SQS, S3, EventBridge, schedules |
| **Stateless per turn** — each turn is "context in, decision out" | Stateless compute — no durable in-process state between invocations |
| **Bursty** — quiet, then a flurry of requests | Scales from zero to thousands of concurrent executions automatically |
| **Pay-per-thought** — you only care about cost when it actually runs | Billed per invocation and per millisecond; **zero cost at rest** |

So a "lightweight agent on Lambda" isn't a compromise or a hack — it's the model the agent
was already shaped like. You're just not paying to keep a brain switched on when nobody's
asking it anything.

---

## What "lightweight" actually means here

No Spring. No Quarkus. No web framework. No container image to maintain. The agent is:

- **One class** that implements Lambda's `RequestHandler` interface.
- **One dependency that matters** — `com.anthropic:anthropic-java`, the official SDK.
- **One reasoning call** to Claude per turn.

That's the whole thing. Everything below is just a closer look at those three pieces and
the design decisions inside them.

```
   an event (HTTP request, queue message, file upload, schedule…)
        │   { "prompt": "..." }
        ▼
   ┌───────────────────────────────────┐
   │   The agent (one Lambda handler)   │
   │                                    │
   │   observe  →  reason (call Claude) →  respond
   │                                    │
   └──────────────┬─────────────────────┘
                  │  reasons with
                  ▼
            Anthropic API  (Claude)
```

---

## Anatomy of the agent

### 1. A long-lived client — the single most important pattern

This is the one detail that separates a sluggish Lambda agent from a snappy one. Lambda
keeps your execution environment **warm** between invocations and reuses the same handler
instance. So you build the SDK client **once**, as a field — during the init phase — and
every warm invocation reuses it, connection pool and all:

```java
public class HelloClaudeHandler
        implements RequestHandler<APIGatewayV2HTTPEvent, APIGatewayV2HTTPResponse> {

    // Built ONCE per execution environment, reused across every warm invocation.
    // fromEnv() reads ANTHROPIC_API_KEY from the environment — no secrets in code.
    private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

    // ...
}
```

If you instead created the client *inside* the handler method, you'd pay to re-establish it
on every single request. As a field, the cost is paid once and amortized across the life of
the warm environment. This is the serverless equivalent of "open the connection at startup,
not per request."

### 2. The reasoning step — one call

A turn of the agent is a single, declarative call. You describe what you want; the SDK
handles transport, retries, and parsing:

```java
MessageCreateParams params = MessageCreateParams.builder()
        .model(MODEL)                 // "claude-opus-4-8" by default
        .maxTokens(1024L)
        .addUserMessage(request.prompt)
        // .system(request.system)    // optional — steer the agent's behavior/persona
        .build();

Message message = client.messages().create(params);
```

The `system` prompt is where a lot of an agent's "personality" and guardrails live — it's
how you tell the agent *what it is* and *how to behave* before it ever sees the user's
input. Keeping it configurable (here, per request) keeps the same deployment reusable for
many roles.

### 3. Reading Claude's answer

A Claude response isn't a bare string — it's a list of **content blocks**. For a
text-only agent we stream them and keep the text:

```java
String reply = message.content().stream()
        .flatMap(block -> block.text().stream())   // keep only the text blocks
        .map(textBlock -> textBlock.text())
        .collect(Collectors.joining());
```

That "list of blocks" shape matters more than it looks: it's the same structure that later
carries **tool-use** blocks when you make the agent multi-step. Designing around it now
means you don't rewrite your response handling when you add tools.

### 4. Typed in, typed out — with built-in observability

The agent's contract is two plain POJOs. Notice the response carries **token usage** — on a
pay-per-token model, an agent that doesn't report what it spent is an agent you can't reason
about in production:

```java
public class ClaudeRequest {
    public String prompt;      // what to think about
    public String system;      // optional: who the agent is
    public Integer maxTokens;  // optional: a cost/length ceiling
}

public class ClaudeResponse {
    public String reply;
    public String model;
    public long inputTokens;   // ← every turn reports its own cost
    public long outputTokens;
}
```

Returning `inputTokens`/`outputTokens` on every turn means each invocation is
self-accounting. That's a small thing that pays off enormously once real traffic hits.

---

## Statelessness is a feature, not a limitation

The most common objection to agents-on-Lambda is "but agents need memory, and Lambda is
stateless." That objection has it backwards.

A robust agent shouldn't keep its memory *inside the process* anyway — that memory dies with
the instance and can't be shared across concurrent executions. Lambda simply **forces the
good design**: keep the compute stateless and push state to where it belongs.

- **Conversation history** → a row in DynamoDB, keyed by session. Load it at the start of a
  turn, append the new turn, save it back.
- **Long-term knowledge** → a vector store or an external API the agent queries.
- **Secrets** → Secrets Manager / SSM, fetched at init.

Each invocation becomes "load context → reason → persist context." The agent's *brain* is
stateless and horizontally scalable; its *memory* is durable and shared. That's exactly the
architecture you'd want even if Lambda didn't require it.

---

## Earning the word "agent": closing the loop

What we have so far is one turn — observe, reason, respond. A true agent runs that as a
**loop** and can *act* in the middle of it: call a tool, read the result, and reason again
before answering. That's the difference between a chatbot and an agent.

Conceptually the loop is simple: call Claude; if it asked to use a tool, run the tool and
feed the result back; call again; repeat until it's done. The good news is **you rarely
write that loop by hand** — the Anthropic Java SDK ships a *tool runner* that drives the
whole observe → act → observe cycle for you.

First, define a tool. A tool is just a class that implements `Supplier<String>` — its
`get()` does the work — with annotations the SDK turns into the tool's JSON schema:

```java
@JsonClassDescription("Performs one exact arithmetic operation on two numbers.")
public static class Calculator implements Supplier<String> {

    @JsonPropertyDescription("The operation: add, subtract, multiply, or divide.")
    public String operation;

    @JsonPropertyDescription("The first operand.")
    public double a;

    @JsonPropertyDescription("The second operand.")
    public double b;

    @Override
    public String get() {                  // the SDK calls this when Claude uses the tool
        switch (operation) {
            case "add":      return String.valueOf(a + b);
            case "subtract": return String.valueOf(a - b);
            case "multiply": return String.valueOf(a * b);
            case "divide":   return String.valueOf(a / b);
            default:         return "Error: unknown operation " + operation;
        }
    }
}
```

Then register the tool and let the runner drive. **Iterating the runner *is* the agent
loop** — each `BetaMessage` is one model turn, with the SDK executing any requested tools
in between and feeding the results back to Claude:

```java
import com.anthropic.helpers.BetaToolRunner;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.MessageCreateParams;

BetaToolRunner runner = client.beta().messages().toolRunner(
        MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(1024L)
                .addUserMessage(prompt)
                .addTool(Calculator.class)   // tool name + JSON schema derived from the class
                .build());

BetaMessage last = null;
for (BetaMessage turn : runner) {            // each turn advances the loop
    last = turn;
}

String reply = last.content().stream()
        .flatMap(block -> block.text().stream())
        .map(textBlock -> textBlock.text())
        .collect(Collectors.joining());
```

That's a complete, working tool-using agent. The `ToolAgentHandler` in the repo wires
exactly this into the same Function URL handler, with a second tool (a current-time lookup)
alongside the calculator — so you can ask it *"what is 1234 × 5678, and what time is it
right now?"* and watch it call both tools before answering.

And here's the part that ties back to the whole article: **adding tools doesn't change the
deployment model at all.** It's still one stateless handler, still triggered by an event,
still scaling to zero. The agent got smarter; its serverless footprint didn't get heavier.
That's the payoff of building lightweight from the start.

---

## Staying lightweight as it grows

A few design dials that keep the agent fast and cheap as you add capability:

- **Match the model to the job.** `claude-opus-4-8` is the most capable; for high-volume,
  latency-sensitive turns, `claude-haiku-4-5` is dramatically faster and cheaper — and on a
  well-designed agent it's a one-line (or one-env-var) switch, no code change.
- **Cap the thinking.** `maxTokens` is both a latency and a cost ceiling. Right-size it per
  use case instead of leaving it wide open.
- **Respect the warm/cold cycle.** Keep heavy initialization (the client, config, any
  warmup) in fields/static init so it happens once. More allocated memory also means more
  CPU on Lambda — which speeds up both cold starts and JSON work.
- **Externalize everything stateful.** As above — memory and secrets live outside the
  function, so any instance can serve any request.
- **Stream when the UX needs it.** Swap the single `create(...)` for the SDK's streaming
  call to push tokens as they're generated, for a typewriter-style response.

None of these add a server or a framework. The agent stays a single handler with one
dependency — it just gets more capable and more economical.

---

## Wrapping up

A lightweight Claude agent on AWS Lambda isn't a downsized version of a "real" agent — it's
the version that matches what agents actually are: stateless reasoning that wakes on an
event, thinks, responds, and gets out of the way. The Anthropic Java SDK reduces the
reasoning step to a single typed call, and Lambda handles the rest of the lifecycle for
free.

Start with one handler and one `create()` call. Add tools when you need the agent to *act*.
Push memory to DynamoDB when you need it to *remember*. At no point do you take on a server,
a framework, or a process that bills you while it sits idle.

**The complete, runnable code is here:**
👉 [github.com/sivamondi/claude-lambda-integration](https://github.com/sivamondi/claude-lambda-integration)

If you build something on top of it — a tool-using agent, a queue worker that triages
tickets, a scheduled summarizer — I'd love to hear what you make.

*Happy (serverless) building.* 🚀
