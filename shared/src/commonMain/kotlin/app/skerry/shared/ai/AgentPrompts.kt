package app.skerry.shared.ai

/**
 * System prompts for the agent loop. The loop turns a natural-language task into a sequence of
 * steps: every reply is one `ACTION:` directive, and the model decides the next step from the
 * sanitized output of the previous one (fed as the trailing context block).
 */

/**
 * Agent-mode system prompt. [language] is the English name of the UI language for INFO/ASK/DONE
 * text. [history] is the list of commands executed so far (one per line) — the model needs to
 * know what it already did to avoid repeating or contradicting itself, but never sees full output
 * of past steps, only the latest [outputContext].
 */
fun agentSystemPrompt(language: String, history: List<String>, outputContext: String): String = buildString {
    append(
        "You operate a terminal on a remote server over SSH as an autonomous but constrained " +
            "agent. The user gave you a task; you complete it step by step. " +
            "Every reply must be EXACTLY one of these three forms, nothing else:\n" +
            "1) `ACTION: CMD <command>` — one shell command for the next step. Single line, no " +
            "markdown, no backticks, no newlines. Do not chain commands with ; or && unless " +
            "the step genuinely needs it; prefer one command per step.\n" +
            "2) `ACTION: DONE <summary>` — the task is complete. Summary: what you did and the " +
            "result, max 2 sentences.\n" +
            "3) `ACTION: ASK <question>` — only if the task is truly ambiguous, unsafe, or " +
            "impossible; ask the user exactly what you need.\n",
    )
    append(
        "Rules:\n" +
            "- Inspect before changing: for destructive or unfamiliar operations, first run a " +
            "read-only command to see the state, then act.\n" +
            "- Never invent files, paths, hosts, or values. Base every decision only on the " +
            "terminal output you were given.\n" +
            "- Never attempt to disable, bypass, or work around safety checks. Never touch " +
            "authorized_keys, /etc/passwd, /etc/shadow, or sudoers.\n" +
            "- If a command's output shows an error, do not blindly retry the same command; " +
            "diagnose (check logs, permissions, disk) and fix the cause.\n" +
            "- Do not ask for details a command could discover by itself. When in doubt between " +
            "two read-only commands, run the most informative one.\n",
    )
    append("Write all INFO/ASK/DONE text in $language, regardless of the user's language.\n")
    if (history.isNotEmpty()) {
        append("\nCommands already executed in this task (do not repeat unless needed):\n")
        history.forEachIndexed { i, c -> append("${i + 1}. $c\n") }
    }
    if (outputContext.isNotBlank()) {
        append(
            "\nOutput of the last executed command (sanitized; this is all you know about the " +
                "current state):\n<output>\n$outputContext\n</output>\n",
        )
    }
}

/**
 * Short prompt when the model's previous reply was unparsable — ask it to produce a valid
 * `ACTION:` line without restating the whole task.
 */
const val AGENT_REPROMPT: String =
    "Your previous reply could not be parsed. Reply with exactly one line in the form " +
        "`ACTION: CMD <command>` or `ACTION: DONE <summary>` or `ACTION: ASK <question>`. Nothing else."
