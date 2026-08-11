package app.skerry.shared.ai

/**
 * Agent loop for the terminal AI bar: turns a natural-language task into a sequence of shell
 * commands that run with the user's one-time authorization, under hard guardrails.
 *
 * Safety model (mirrors the single-command bar, extended for the loop):
 * - The model never runs anything by itself. Every command the model proposes goes through
 *   [CommandRiskClassifier]; a [CommandRisk.Danger] proposal (or any proposal when
 *   [AgentLoopController.forceConfirmAll] is on, e.g. prod hosts) lands in
 *   [AgentLoopState.AwaitingConfirm] and runs only after an explicit [AgentLoopController.confirm].
 * - The loop is bounded: [AgentLoopController.maxSteps] commands and [AgentLoopController.maxMinutes]
 *   wall time; [AgentLoopController.guard] reports when either is exceeded and the driver must stop.
 * - Terminal output fed back to the model is sanitized ([sanitizeOutputContext]) before it ever
 *   leaves the client: control/ANSI bytes stripped and capped, so a hostile host cannot inject
 *   instructions into the model's context through command output.
 *
 * This file is pure state — no network, no terminal, no UI. The driver (UI layer) owns the stream
 * (AiStreamRunner), the send channel and the output capture, and drives this state machine:
 *
 * ```
 * start() -> Thinking
 *   onModelReply(Execute) -> AwaitingExecution (safe) | AwaitingConfirm (Danger / forceConfirmAll)
 *     confirm() -> Executing (returns the command for the driver to send)
 *     reject()  -> Thinking (command skipped)
 *   onModelReply(Done)    -> Done
 *   onModelReply(Ask)     -> Asking (driver shows the question; user's answer -> start() again)
 *   onStepComplete(output) -> Evaluating (driver feeds sanitized output back, then resume())
 *     resume() -> Thinking
 *   interrupt() / fail()  -> Interrupted / Failed (from any state)
 * ```
 */

/** States of the agent loop; one at a time, transitions owned by [AgentLoopController]. */
enum class AgentLoopState {
    /** No task in flight. */
    Idle,

    /** Model is generating the next directive (streaming). */
    Thinking,

    /** A safe command is ready; the driver should send it. */
    AwaitingExecution,

    /** The model asked the user a question; the driver shows it and awaits the user's answer. */
    Asking,

    /** The command needs the user's explicit go-ahead (Danger, or forceConfirmAll). */
    AwaitingConfirm,

    /** The command was sent to the terminal; the driver captures output. */
    Executing,

    /** Output was captured; the driver feeds the sanitized summary back to the model. */
    Evaluating,

    /** The model replied DONE; [AgentLoopController.summary] holds the task summary. */
    Done,

    /** The loop failed (guard exceeded, transport error); [AgentLoopController.summary] has the reason. */
    Failed,

    /** The user interrupted; the driver should send SIGINT if a command is in flight. */
    Interrupted,
}

/** One proposed command from the model, with its risk assessment. */
data class AgentCommandProposal(
    val command: String,
    val info: String?,
    val assessment: CommandAssessment,
)

/** The model's directive parsed from one reply. */
sealed interface AgentDirective {
    /** Run [command] (single line, sanitized); [info] is the model's short description. */
    data class Execute(val command: String, val info: String?) : AgentDirective

    /** The task is complete; [summary] is what was done. */
    data class Done(val summary: String) : AgentDirective

    /** The model needs clarification; [question] is shown to the user. */
    data class Ask(val question: String) : AgentDirective

    /** The reply contained no usable directive; the driver should ask the model again or fail. */
    data object Unparsable : AgentDirective
}

/**
 * Pure agent-loop state machine. Not thread-safe by design — drive it from a single coroutine.
 *
 * @param maxSteps hard cap on executed commands per task (guard).
 * @param maxMinutes hard cap on wall time per task (guard).
 * @param outputContextLimit chars of output fed back to the model per step.
 * @param forceConfirmAll when true every proposal requires [confirm] (prod hosts).
 */
class AgentLoopController(
    val maxSteps: Int = 20,
    val maxMinutes: Int = 10,
    val outputContextLimit: Int = 4000,
    val forceConfirmAll: Boolean = false,
) {
    /** Current state; start state is [AgentLoopState.Idle]. */
    var state: AgentLoopState = AgentLoopState.Idle
        private set

    /** Commands executed so far in this task (for the model's history). */
    val executed: MutableList<String> = mutableListOf()

    /** The current proposal awaiting execution or confirmation; `null` unless one is pending. */
    var proposal: AgentCommandProposal? = null
        private set

    /** The model's question while [AgentLoopState.Asking]. */
    var question: String? = null
        private set

    /** Final summary: task result (Done), reason (Failed), or interruption note. */
    var summary: String? = null
        private set

    /** The sanitized output of the last executed step (set on [onStepComplete]). */
    var lastOutput: String = ""
        private set

    /** Step index of the last executed command (1-based). */
    var lastStepIndex: Int = 0
        private set

    private var startedAtMillis: Long = 0L
    private var lastExecutedCommand: String? = null

    /** Begin a task: [AgentLoopState.Idle] or [AgentLoopState.Asking] -> [AgentLoopState.Thinking]. */
    fun start() {
        require(state == AgentLoopState.Idle || state == AgentLoopState.Asking) {
            "start() only from Idle/Asking, was $state"
        }
        if (state == AgentLoopState.Idle) {
            executed.clear()
            lastStepIndex = 0
            startedAtMillis = nowMillis()
            summary = null
        }
        question = null
        proposal = null
        state = AgentLoopState.Thinking
    }

    /**
     * Feed a model reply. Returns the parsed directive (the caller may also inspect [state]).
     * An [AgentDirective.Execute] proposal is stored and the state moves to AwaitingExecution
     * (safe) or AwaitingConfirm (Danger or [forceConfirmAll]).
     *
     * Terminal states are absorbing: after [AgentLoopState.Done], [AgentLoopState.Failed] or
     * [AgentLoopState.Interrupted] the loop ignores further replies (returns [AgentDirective.Unparsable]
     * without touching state), so a late-finishing stream cannot resurrect a finished task.
     */
    fun onModelReply(raw: String): AgentDirective {
        if (state == AgentLoopState.Done || state == AgentLoopState.Failed || state == AgentLoopState.Interrupted) {
            return AgentDirective.Unparsable
        }
        val directive = parseAgentDirective(raw)
        when (directive) {
            is AgentDirective.Execute -> {
                val assessment = CommandRiskClassifier.assess(directive.command)
                proposal = AgentCommandProposal(directive.command, directive.info, assessment)
                state = if (assessment.risk == CommandRisk.Danger || forceConfirmAll) {
                    AgentLoopState.AwaitingConfirm
                } else {
                    AgentLoopState.AwaitingExecution
                }
            }
            is AgentDirective.Done -> {
                summary = directive.summary
                proposal = null
                state = AgentLoopState.Done
            }
            is AgentDirective.Ask -> {
                question = directive.question
                proposal = null
                state = AgentLoopState.Asking
            }
            AgentDirective.Unparsable -> {
                state = AgentLoopState.Thinking
            }
        }
        return directive
    }

    /**
     * The user confirmed the pending proposal (from [AgentLoopState.AwaitingConfirm] or
     * [AgentLoopState.AwaitingExecution]). Returns the command for the driver to send, or `null`
     * if there is nothing pending. Moves to [AgentLoopState.Executing].
     */
    fun confirm(): String? {
        val p = proposal ?: return null
        if (state != AgentLoopState.AwaitingConfirm && state != AgentLoopState.AwaitingExecution) return null
        lastExecutedCommand = p.command
        executed += p.command
        proposal = null
        state = AgentLoopState.Executing
        return p.command
    }

    /** The user rejected the pending confirmation; the command is skipped and the model continues. */
    fun reject() {
        if (state != AgentLoopState.AwaitingConfirm) return
        proposal = null
        state = AgentLoopState.Thinking
    }

    /**
     * The driver captured the step's output. [output] is sanitized here (control bytes stripped,
     * capped) and stored in [lastOutput] for the driver to feed back to the model. Moves to
     * [AgentLoopState.Evaluating]. Returns the sanitized output for convenience.
     */
    fun onStepComplete(output: String): String {
        val clean = sanitizeOutputContext(output, outputContextLimit)
        lastOutput = clean
        lastStepIndex = executed.size
        state = AgentLoopState.Evaluating
        return clean
    }

    /** The driver fed [lastOutput] back to the model; the model is generating the next directive. */
    fun resume() {
        if (state != AgentLoopState.Evaluating) return
        state = AgentLoopState.Thinking
    }

    /** User pressed interrupt: -> [AgentLoopState.Interrupted]; the driver sends SIGINT if executing. */
    fun interrupt() {
        if (state == AgentLoopState.Done || state == AgentLoopState.Failed || state == AgentLoopState.Interrupted) return
        state = AgentLoopState.Interrupted
        summary = "Interrupted by user"
        proposal = null
    }

    /** Hard failure (transport error, unparsable loop): -> [AgentLoopState.Failed]. */
    fun fail(reason: String) {
        if (state == AgentLoopState.Done || state == AgentLoopState.Interrupted) return
        state = AgentLoopState.Failed
        summary = reason
        proposal = null
    }

    /**
     * Guard check: whether the loop must stop. Returns a non-null reason when [maxSteps] commands
     * executed or [maxMinutes] wall time elapsed since [start]; the driver calls this before
     * feeding another model round (and after [onStepComplete]).
     */
    fun guard(): String? {
        if (executed.size >= maxSteps) return "Step limit reached ($maxSteps)"
        // startedAtMillis is a Monotonic mark (relative, can be 0 right after start()), so the
        // elapsed comparison is relative too: elapsed > maxMinutes*60_000 is exact either way.
        if (nowMillis() - startedAtMillis > maxMinutes * 60_000L) {
            return "Time limit reached ($maxMinutes min)"
        }
        return null
    }

    /** The command currently in flight (set by [confirm]), for the driver's interrupt path. */
    fun inFlightCommand(): String? = if (state == AgentLoopState.Executing) lastExecutedCommand else null

    private fun nowMillis(): Long = kotlin.time.TimeSource.Monotonic.markNow().elapsedNow().inWholeMilliseconds
}
