package app.skerry.shared.ai

import app.skerry.shared.terminal.isSafeTerminalInputChar

/**
 * Parses the agent-loop model protocol and sanitizes terminal output for model context.
 *
 * Protocol (extended from the single-command bar's `CMD:`/`ASK:`):
 * ```
 * ACTION: CMD <command>     # run one step
 * ACTION: DONE <summary>    # task finished
 * ACTION: ASK <question>    # need clarification
 * ```
 * Bare `CMD:`/`ASK:` lines stay accepted (single-command bar compatibility). A reply without
 * markers falls back to treating the first safe line as a command — the same behavior as the
 * single-command bar.
 *
 * Security-critical: [parseAgentDirective] routes model output to execution, so every command is
 * run through the same single-line, control-free sanitization as the bar ([sanitizeCommand]).
 */

/** The model's directive parsed from one reply. See [parseAgentDirective]. */
fun parseAgentDirective(raw: String): AgentDirective {
    val actionCmd = actionValue(raw, "CMD") ?: lineValue(raw, "CMD:")
    if (actionCmd != null) {
        val command = sanitizeCommand(actionCmd)
        if (command != null && !command.startsWith("#") && !looksLikeProse(command)) {
            return AgentDirective.Execute(command, lineValue(raw, "INFO:")?.let { cleanLine(it) })
        }
    }
    val actionDone = actionValue(raw, "DONE")
    if (actionDone != null) {
        val summary = cleanLine(actionDone)
        if (!summary.isNullOrBlank()) return AgentDirective.Done(summary)
    }
    val actionAsk = actionValue(raw, "ASK") ?: lineValue(raw, "ASK:")
    if (actionAsk != null) {
        val question = cleanLine(actionAsk)
        if (!question.isNullOrBlank()) return AgentDirective.Ask(question)
    }
    return parseFallback(raw)
}

/** No `ACTION:`/bare marker matched: first safe line as a command, else ask/unparsable. */
private fun parseFallback(raw: String): AgentDirective {
    val first = sanitizeCommand(raw)
    return when {
        first == null -> AgentDirective.Unparsable
        first.startsWith("#") || looksLikeProse(first) -> AgentDirective.Ask(
            first.trimStart('#').trim().ifEmpty { "Please clarify the task." },
        )
        else -> AgentDirective.Execute(first, null)
    }
}

/** Value after `ACTION: <kind> ` on any line, or `null`. */
private fun actionValue(raw: String, kind: String): String? {
    for (line in raw.lineSequence()) {
        val t = line.trim()
        if (!t.startsWith("ACTION:", ignoreCase = true)) continue
        val rest = t.substring("ACTION:".length).trim()
        if (!rest.startsWith(kind, ignoreCase = true)) continue
        val value = rest.substring(kind.length).trim()
        if (value.isNotEmpty()) return value
    }
    return null
}

/** First line starting with [prefix] (case-insensitive); the remainder, or `null`. */
private fun lineValue(raw: String, prefix: String): String? {
    raw.lineSequence().forEach { line ->
        val t = line.trim()
        if (t.startsWith(prefix, ignoreCase = true)) {
            return t.substring(prefix.length).trim().ifEmpty { null }
        }
    }
    return null
}

/** Cleans an INFO/ASK/summary line: backticks, list markers, control bytes; capped. */
private fun cleanLine(s: String): String? {
    val c = s.trim().trim('`').trimStart('#', '-', '*', '•', '>').trim()
        .filter { isSafeInputChar(it) }.trim()
    return c.ifEmpty { null }?.take(160)
}

/**
 * Reduces raw model output to a single input line with no control characters or markdown
 * fences — same contract as the UI bar's sanitizer, mirrored here so the shared agent loop
 * never depends on a UI-layer parser. Returns `null` if there is no usable command.
 */
fun sanitizeCommand(raw: String): String? {
    var text = raw.trim()
    if (text.startsWith("```") && text.endsWith("```") && text.length > 6) {
        text = text.substring(3, text.length - 3)
        val firstTok = text.substringBefore('\n').trim()
        // A ```bash / ```sh language tag on the fence's first line, dropped.
        val isLangTag = firstTok.isNotEmpty() && firstTok.none { it.isWhitespace() } &&
            firstTok.all { it.isLetterOrDigit() || it == '-' }
        if (isLangTag) {
            text = text.substringAfter('\n', "")
        }
    }
    val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() } ?: return null
    val cleaned = firstLine.filter { isSafeInputChar(it) }.trim().trim('`').trim()
    return cleaned.ifEmpty { null }
}

/**
 * Whether a string reads like natural language rather than a shell command (mirrors the bar's
 * heuristic): trailing question mark, Cyrillic, or common clarifying starters.
 */
fun looksLikeProse(s: String): Boolean {
    if (s.endsWith("?")) return true
    if (s.any { it in 'Ѐ'..'ӿ' }) return true
    val lower = s.lowercase()
    return PROSE_STARTERS.any { lower.startsWith(it) }
}

/** Allowed command character — the shared single-line terminal predicate. */
private fun isSafeInputChar(c: Char): Boolean = isSafeTerminalInputChar(c)

private val PROSE_STARTERS = listOf(
    "please", "sorry", "could you", "can you", "which ", "what ", "i cannot", "i can't",
    "i'm ", "i am ", "unable", "clarify", "specify", "you need", "the request", "to run this",
)

/** ANSI CSI sequences (`ESC [ ... letter`) and OSC (`ESC ] ... BEL/ST`). */
private val ANSI_ESCAPE = Regex("\u001b\\[[0-9;?]*[ -/]*[@-~]|\u001b\\][^\u0007\u001b]*(\u0007|\u001b\\\\)")

/** ISO control bytes, keeping \n and \t. */
private val CONTROL_CHARS = Regex("[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]")

/** Runs of 3+ blank lines. */
private val BLANK_RUNS = Regex("\n[ \t]*\n(?:[ \t]*\n)+")

/**
 * Sanitizes terminal output before it is fed back to the model: strips ANSI/control bytes,
 * collapses blank runs, and caps the length (head + tail kept, middle elided). A hostile host
 * must not be able to inject control sequences or prompt text through command output.
 *
 * @param limit max chars of the returned text (head and tail share it).
 */
fun sanitizeOutputContext(raw: String, limit: Int = 4000): String {
    if (raw.isEmpty()) return ""
    val cleaned = BLANK_RUNS.replace(
        CONTROL_CHARS.replace(ANSI_ESCAPE.replace(raw, ""), ""),
        "\n",
    ).trim()
    if (cleaned.length <= limit) return cleaned
    val half = limit / 2
    val head = cleaned.take(half)
    val tail = cleaned.takeLast(limit - half)
    return "$head\n…[output truncated]…\n$tail"
}
