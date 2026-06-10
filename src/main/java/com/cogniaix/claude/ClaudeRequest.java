package com.cogniaix.claude;

/**
 * Shape of the JSON body POSTed to the Lambda Function URL.
 *
 * <pre>
 * {
 *   "prompt":    "Say hello from AWS Lambda in one short sentence.",  // required
 *   "system":    "You are a concise assistant.",                     // optional
 *   "maxTokens": 1024                                                // optional
 * }
 * </pre>
 *
 * Plain public fields keep this a zero-ceremony POJO that Jackson can read
 * without getters/setters or annotations.
 */
public class ClaudeRequest {
    /** The user prompt to send to Claude. Required. */
    public String prompt;

    /** Optional system prompt that steers Claude's behavior/persona. */
    public String system;

    /** Optional max output tokens. Falls back to a server-side default when null/<=0. */
    public Integer maxTokens;
}
