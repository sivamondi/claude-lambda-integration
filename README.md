# Hello World Claude Agent on AWS Lambda (Pure Java)

A minimal, lightweight AWS Lambda — written in **pure Java**, no frameworks — that
calls **Claude** through the official
[Anthropic Java SDK](https://github.com/anthropics/anthropic-sdk-java) and returns
the reply over HTTP.

It exists to show one thing clearly: **how little it takes to run a Claude-powered
agent on Lambda.** One handler class, one SDK call, a single uber-JAR, and a
Lambda Function URL you can `curl`.

---

## What it demonstrates

- Calling the Claude **Messages API** from Java with `com.anthropic:anthropic-java`.
- Packaging a Java Lambda as a self-contained **uber-JAR** with the Maven Shade plugin.
- Exposing it over HTTP with a **Lambda Function URL** — no API Gateway, no extra infra.
- A clean **request → Claude → response** path, with token usage returned to the caller.

```
   client (curl/Postman/browser)
        │  POST { "prompt": "..." }
        ▼
   ┌─────────────────────────────┐
   │   Lambda Function URL        │   (public HTTPS endpoint)
   └──────────────┬──────────────┘
                  ▼
   ┌─────────────────────────────┐
   │   HelloClaudeHandler (Java)  │
   │   • parse JSON body          │
   │   • build MessageCreateParams│
   │   • client.messages().create │────────►  Anthropic API  (Claude)
   │   • return reply + tokens    │◄────────
   └──────────────┬──────────────┘
                  ▼
        { "reply": "...", "model": "...", "inputTokens": N, "outputTokens": N }
```

---

## Project layout

```
.
├── pom.xml                              # Maven build (deps + shade plugin)
├── src/main/java/com/cogniaix/claude/
│   ├── HelloClaudeHandler.java          # simple agent — a single Claude Messages call
│   ├── ToolAgentHandler.java            # tool-using agent — Claude + tools via BetaToolRunner
│   ├── ClaudeRequest.java               # request body POJO (shared)
│   ├── ClaudeResponse.java              # response body POJO (simple agent)
│   └── AgentResponse.java               # response body POJO (tool agent)
├── deploy/
│   ├── deploy.sh                        # one-command idempotent deploy (AWS CLI)
│   └── trust-policy.json                # IAM trust policy for the Lambda role
├── events/
│   └── function-url-event.json          # sample event for `aws lambda invoke`
└── README.md
```

---

## Prerequisites

| Tool | Why | Notes |
|------|-----|-------|
| **JDK 21+** | Build the code | Compiled to Java 21 bytecode for the `java21` runtime. A newer JDK (e.g. 25) is fine — it targets 21 via `maven.compiler.release`. |
| **Maven 3.9+** | Build the JAR | |
| **AWS CLI v2** | Deploy | Run `aws configure` first so it has credentials + a default region. |
| **An Anthropic API key** | Call Claude | Get one at <https://console.anthropic.com>. Format: `sk-ant-...` |

---

## Configuration

The Lambda reads two environment variables at runtime:

| Variable | Required | Default | Purpose |
|----------|----------|---------|---------|
| `ANTHROPIC_API_KEY` | ✅ | — | Your Anthropic API key. `AnthropicOkHttpClient.fromEnv()` reads it. |
| `ANTHROPIC_MODEL` | ❌ | `claude-opus-4-8` | Which Claude model to use. Set to `claude-haiku-4-5` for faster, cheaper "hello world" responses. |

> For a real deployment, store the key in **AWS Secrets Manager** or **SSM Parameter
> Store** rather than a plain Lambda environment variable. Plain env var is used here
> only to keep the demo lightweight.

---

## Build

```bash
mvn clean package
```

Produces a ~30 MB uber-JAR at `target/claude-lambda-hello.jar` containing your code
plus the Anthropic SDK and all dependencies. That single JAR **is** the Lambda
deployment package (Lambda accepts a `.jar` directly for Java).

---

## Deploy

### Option A — one command (recommended)

```bash
export ANTHROPIC_API_KEY=sk-ant-...
./deploy/deploy.sh
```

The script builds the JAR, creates the IAM role (first run only), creates **or**
updates the function, wires up a public Function URL, and prints the URL plus a
ready-to-run `curl`. Re-run it any time to ship changes.

Override defaults with env vars, e.g.:

```bash
FUNCTION_NAME=my-claude MEMORY_SIZE=512 ANTHROPIC_MODEL=claude-haiku-4-5 \
  ANTHROPIC_API_KEY=sk-ant-... ./deploy/deploy.sh
```

### Option B — manual AWS CLI steps

Useful if you want to see every command (e.g. for the article walkthrough).

```bash
# 0) Build
mvn clean package

# 1) Create an execution role (one time)
aws iam create-role \
  --role-name claude-lambda-hello-role \
  --assume-role-policy-document file://deploy/trust-policy.json

aws iam attach-role-policy \
  --role-name claude-lambda-hello-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole

# Wait ~10s for the new role to propagate, then grab your account id:
ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

# 2) Create the function
aws lambda create-function \
  --function-name hello-claude \
  --runtime java21 \
  --handler com.cogniaix.claude.HelloClaudeHandler::handleRequest \
  --role arn:aws:iam::${ACCOUNT_ID}:role/claude-lambda-hello-role \
  --zip-file fileb://target/claude-lambda-hello.jar \
  --timeout 60 \
  --memory-size 1024 \
  --environment "Variables={ANTHROPIC_API_KEY=sk-ant-...,ANTHROPIC_MODEL=claude-opus-4-8}"

# 3) Add a public Function URL
aws lambda create-function-url-config \
  --function-name hello-claude \
  --auth-type NONE

aws lambda add-permission \
  --function-name hello-claude \
  --statement-id FunctionURLAllowPublicAccess \
  --action lambda:InvokeFunctionUrl \
  --principal "*" \
  --function-url-auth-type NONE

# 4) Print the URL
aws lambda get-function-url-config \
  --function-name hello-claude \
  --query FunctionUrl --output text
```

> ⚠️ **`--auth-type NONE` makes the endpoint public.** Anyone with the URL can invoke
> it and spend your Anthropic credits. For anything beyond a quick demo, use
> `--auth-type AWS_IAM` and sign requests with SigV4 (or front it with API Gateway +
> an authorizer). Delete the function when you're done (see [Cleanup](#cleanup)).

---

## Invoke it

### Over HTTP (the real path)

```bash
curl -sS -X POST "<YOUR_FUNCTION_URL>" \
  -H "Content-Type: application/json" \
  -d '{"prompt":"In one sentence, what is AWS Lambda?"}'
```

Example response:

```json
{
  "reply": "AWS Lambda is a serverless compute service that runs your code in response to events without requiring you to provision or manage servers.",
  "model": "claude-opus-4-8",
  "inputTokens": 16,
  "outputTokens": 31
}
```

Optional fields in the request body:

```json
{
  "prompt": "Write a haiku about serverless functions.",
  "system": "You are a poet who loves cloud computing.",
  "maxTokens": 256
}
```

### Without HTTP (direct invoke)

```bash
aws lambda invoke \
  --function-name hello-claude \
  --cli-binary-format raw-in-base64-out \
  --payload file://events/function-url-event.json \
  out.json && cat out.json
```

---

## How it works (code walkthrough)

The entire agent is one handler. The pieces that matter:

```java
// Created once per execution environment and reused across warm invocations.
// fromEnv() reads ANTHROPIC_API_KEY from the environment.
private final AnthropicClient client = AnthropicOkHttpClient.fromEnv();

// Per request:
MessageCreateParams params = MessageCreateParams.builder()
        .model(MODEL)                  // "claude-opus-4-8" (or $ANTHROPIC_MODEL)
        .maxTokens(maxTokens)
        .addUserMessage(request.prompt)
        // .system(request.system)     // optional
        .build();

Message message = client.messages().create(params);

String reply = message.content().stream()
        .flatMap(block -> block.text().stream())  // keep only text blocks
        .map(textBlock -> textBlock.text())
        .collect(Collectors.joining());
```

Three details worth calling out:

1. **The client is a field, not a local.** Lambda reuses the handler instance across
   warm invocations, so building the SDK client once (during init) keeps per-request
   latency down.
2. **The event type is `APIGatewayV2HTTPEvent`.** Lambda Function URLs use the same
   "payload format 2.0" as API Gateway HTTP APIs, so the prompt arrives in
   `event.getBody()` and the reply goes back as an `APIGatewayV2HTTPResponse`.
3. **Content is a list of blocks.** A Claude response can contain multiple content
   blocks; we stream them and concatenate the text ones.

---

## Cost & performance notes

- **Cold start:** A Java Lambda with a 30 MB JAR takes a couple of seconds to cold
  start while the JVM and SDK initialize; warm invocations are fast. More memory means
  more CPU, which speeds up both cold start and JSON work — `1024 MB` is a good
  starting point. To cut cold starts further, enable
  [Lambda SnapStart for Java](https://docs.aws.amazon.com/lambda/latest/dg/snapstart.html)
  (supported on `java21`).
- **Timeout:** Set to 60s here. `claude-opus-4-8` is the most capable model but not the
  fastest — if you want snappy hello-world latency, set `ANTHROPIC_MODEL=claude-haiku-4-5`.
- **Token usage** is returned in every response so you can see exactly what each call costs.
- **Package size:** 30 MB uploads directly. If you add dependencies and cross Lambda's
  50 MB zipped direct-upload limit, upload via S3 (`--code S3Bucket=...,S3Key=...`) instead.

---

## Cleanup

```bash
aws lambda delete-function-url-config --function-name hello-claude
aws lambda delete-function            --function-name hello-claude
aws iam detach-role-policy --role-name claude-lambda-hello-role \
  --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
aws iam delete-role --role-name claude-lambda-hello-role
```

---

## Going further

The repo ships **two agents**, both behind the same lightweight Function URL pattern:

- **`HelloClaudeHandler`** — a single, stateless Claude Messages call.
- **`ToolAgentHandler`** — a tool-using agent: Claude calls tools (a calculator and a
  current-time lookup) and the SDK's `BetaToolRunner` drives the observe → act → observe
  loop until it has an answer. Deploy it by pointing the Lambda handler at
  `com.cogniaix.claude.ToolAgentHandler::handleRequest`.

Natural next steps from here:

- **Streaming** — stream tokens back with `client.messages().createStreaming(...)`
  (pairs well with a Function URL using response streaming).
- **Conversation memory** — persist message history in DynamoDB for multi-turn chats.
- **Secrets Manager** — pull the API key at runtime instead of an env var.
- **SnapStart** — enable it to cut Java cold starts dramatically.

---

## License / use

Built as a reference for a Medium article on deploying lightweight Claude agents to
AWS Lambda. Use it freely.
