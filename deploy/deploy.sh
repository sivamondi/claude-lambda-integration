#!/usr/bin/env bash
#
# One-command deploy for the Hello World Claude Agent Lambda.
#
# It is idempotent: run it once to create everything, run it again to ship code
# or config changes. It uses only the AWS CLI + Maven — no SAM, CDK, or Terraform.
#
# Required:
#   ANTHROPIC_API_KEY   Your Anthropic API key (sk-ant-...)
#
# Optional (sensible defaults shown):
#   FUNCTION_NAME=hello-claude
#   ROLE_NAME=claude-lambda-hello-role
#   ANTHROPIC_MODEL=claude-opus-4-8
#   MEMORY_SIZE=1024            # MB  (more memory = more CPU = faster cold start)
#   TIMEOUT=60                  # seconds
#   AWS_REGION=<your aws cli default>
#
# Usage:
#   export ANTHROPIC_API_KEY=sk-ant-...
#   ./deploy/deploy.sh

set -euo pipefail

# --- Config -----------------------------------------------------------------
FUNCTION_NAME="${FUNCTION_NAME:-hello-claude}"
ROLE_NAME="${ROLE_NAME:-claude-lambda-hello-role}"
ANTHROPIC_MODEL="${ANTHROPIC_MODEL:-claude-opus-4-8}"
MEMORY_SIZE="${MEMORY_SIZE:-1024}"
TIMEOUT="${TIMEOUT:-60}"
RUNTIME="java21"
HANDLER="com.cogniaix.claude.HelloClaudeHandler::handleRequest"

# Resolve paths relative to this script so it works from any directory.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JAR_PATH="$PROJECT_DIR/target/claude-lambda-hello.jar"

if [[ -z "${ANTHROPIC_API_KEY:-}" ]]; then
  echo "ERROR: ANTHROPIC_API_KEY is not set." >&2
  exit 1
fi

echo "==> Building the deployment JAR"
mvn -B -q -f "$PROJECT_DIR/pom.xml" clean package
[[ -f "$JAR_PATH" ]] || { echo "ERROR: build did not produce $JAR_PATH" >&2; exit 1; }

ACCOUNT_ID="$(aws sts get-caller-identity --query Account --output text)"
ROLE_ARN="arn:aws:iam::${ACCOUNT_ID}:role/${ROLE_NAME}"

# --- IAM execution role -----------------------------------------------------
if aws iam get-role --role-name "$ROLE_NAME" >/dev/null 2>&1; then
  echo "==> IAM role $ROLE_NAME already exists"
else
  echo "==> Creating IAM role $ROLE_NAME"
  aws iam create-role \
    --role-name "$ROLE_NAME" \
    --assume-role-policy-document "file://$SCRIPT_DIR/trust-policy.json" >/dev/null
  aws iam attach-role-policy \
    --role-name "$ROLE_NAME" \
    --policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
  echo "==> Waiting 10s for IAM role to propagate"
  sleep 10
fi

# --- Lambda function (create or update) -------------------------------------
ENV_VARS="Variables={ANTHROPIC_API_KEY=${ANTHROPIC_API_KEY},ANTHROPIC_MODEL=${ANTHROPIC_MODEL}}"

if aws lambda get-function --function-name "$FUNCTION_NAME" >/dev/null 2>&1; then
  echo "==> Updating function code: $FUNCTION_NAME"
  aws lambda update-function-code \
    --function-name "$FUNCTION_NAME" \
    --zip-file "fileb://$JAR_PATH" >/dev/null
  aws lambda wait function-updated-v2 --function-name "$FUNCTION_NAME"

  echo "==> Updating function configuration"
  aws lambda update-function-configuration \
    --function-name "$FUNCTION_NAME" \
    --runtime "$RUNTIME" \
    --handler "$HANDLER" \
    --timeout "$TIMEOUT" \
    --memory-size "$MEMORY_SIZE" \
    --environment "$ENV_VARS" >/dev/null
  aws lambda wait function-updated-v2 --function-name "$FUNCTION_NAME"
else
  echo "==> Creating function: $FUNCTION_NAME"
  aws lambda create-function \
    --function-name "$FUNCTION_NAME" \
    --runtime "$RUNTIME" \
    --handler "$HANDLER" \
    --role "$ROLE_ARN" \
    --zip-file "fileb://$JAR_PATH" \
    --timeout "$TIMEOUT" \
    --memory-size "$MEMORY_SIZE" \
    --environment "$ENV_VARS" >/dev/null
  aws lambda wait function-active-v2 --function-name "$FUNCTION_NAME"
fi

# --- Function URL (public, auth-type NONE) ----------------------------------
# WARNING: auth-type NONE makes the endpoint publicly invokable by anyone who
# has the URL. Fine for a demo; use AWS_IAM for anything real.
if aws lambda get-function-url-config --function-name "$FUNCTION_NAME" >/dev/null 2>&1; then
  echo "==> Function URL already configured"
else
  echo "==> Creating public Function URL"
  aws lambda create-function-url-config \
    --function-name "$FUNCTION_NAME" \
    --auth-type NONE >/dev/null
  aws lambda add-permission \
    --function-name "$FUNCTION_NAME" \
    --statement-id FunctionURLAllowPublicAccess \
    --action lambda:InvokeFunctionUrl \
    --principal "*" \
    --function-url-auth-type NONE >/dev/null
fi

FUNCTION_URL="$(aws lambda get-function-url-config \
  --function-name "$FUNCTION_NAME" \
  --query FunctionUrl --output text)"

echo ""
echo "============================================================"
echo " Deployed: $FUNCTION_NAME  (model: $ANTHROPIC_MODEL)"
echo " Function URL: $FUNCTION_URL"
echo "============================================================"
echo ""
echo "Try it:"
echo "  curl -sS -X POST \"$FUNCTION_URL\" \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{\"prompt\":\"In one sentence, what is AWS Lambda?\"}'"
