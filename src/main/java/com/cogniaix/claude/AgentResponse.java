package com.cogniaix.claude;

/**
 * Response shape for the tool-using agent.
 *
 * <pre>
 * {
 *   "reply":        "It's currently 14:32 UTC, and 1234 × 5678 = 7006652.",
 *   "model":        "claude-opus-4-8",
 *   "turns":        3,
 *   "inputTokens":  812,
 *   "outputTokens": 144
 * }
 * </pre>
 *
 * {@code turns} is the number of model round-trips the agent made — one for the
 * initial reasoning, one after each tool result — so you can see the loop working.
 */
public class AgentResponse {
    /** The agent's final text answer. */
    public String reply;

    /** The model that drove the loop. */
    public String model;

    /** How many model turns the agent took before producing a final answer. */
    public int turns;

    /** Total prompt (input) tokens across every turn of the loop. */
    public long inputTokens;

    /** Total generated (output) tokens across every turn of the loop. */
    public long outputTokens;
}
