package com.cogniaix.claude;

/**
 * Shape of the JSON returned to the caller.
 *
 * <pre>
 * {
 *   "reply":        "Hello from AWS Lambda!",
 *   "model":        "claude-opus-4-8",
 *   "inputTokens":  14,
 *   "outputTokens": 9
 * }
 * </pre>
 */
public class ClaudeResponse {
    /** Claude's text answer. */
    public String reply;

    /** The model that produced the answer. */
    public String model;

    /** Prompt (input) tokens billed for this request. */
    public long inputTokens;

    /** Generated (output) tokens billed for this request. */
    public long outputTokens;
}
