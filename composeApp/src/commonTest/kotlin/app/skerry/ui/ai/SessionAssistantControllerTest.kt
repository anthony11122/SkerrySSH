package app.skerry.ui.ai

import app.skerry.shared.ai.AiChatRequest
import app.skerry.shared.ai.AiDelta
import app.skerry.shared.ai.AiException
import app.skerry.shared.ai.AiMessage
import app.skerry.shared.ai.AiPolicy
import app.skerry.shared.ai.AiProvider
import app.skerry.shared.ai.AiProviderKind
import app.skerry.shared.ai.AiRole
import app.skerry.shared.ai.AiRoute
import app.skerry.shared.ai.AiSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingProvider(
    private val deltas: List<String> = emptyList(),
    private val failWith: AiException? = null,
    private val hang: Boolean = false,
    /**
     * Runs after the last delta, while the stream is still inside `collect` — the window where a
     * cancellation request has been made but the coroutine has not yet reached a suspension point.
     */
    private val beforeCompleting: () -> Unit = {},
    /** Runs between the first delta and the rest — the window a mid-stream cancel lands in. */
    private val afterFirstDelta: () -> Unit = {},
) : AiProvider {
    var built = false
    var lastMessages: List<AiMessage> = emptyList()
    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        built = true
        lastMessages = request.messages
        failWith?.let { throw it }
        deltas.forEachIndexed { index, delta ->
            emit(AiDelta(delta))
            if (index == 0) afterFirstDelta()
        }
        beforeCompleting()
        if (hang) kotlinx.coroutines.awaitCancellation()
    }
    override suspend fun close() {}
}

/**
 * Session assistant: a conversation about the open session, under the host's [AiPolicy]. Commands it
 * proposes are never executed by the controller — the panel sends them only on an explicit click.
 */
class SessionAssistantControllerTest {

    private fun controller(
        policy: AiPolicy,
        settings: AiSettings,
        provider: AiProvider,
        scope: CoroutineScope,
    ) = SessionAssistantController(
        policy,
        settings = { settings },
        providerFactory = { provider },
        scope = scope,
    )

    @Test
    fun `off policy sends nothing and records no turn`() = runTest {
        val p = RecordingProvider(deltas = listOf("hi"))
        val c = controller(AiPolicy.Off, AiSettings(apiKey = "sk-x"), p, this)

        assertFalse(c.aiEnabled)
        c.ask("what is eating the disk?", outputs = emptyList())
        advanceUntilIdle()

        assertFalse(p.built)
        assertTrue(c.turns.isEmpty())
    }

    @Test
    fun `strict policy without a local model blocks before building a provider`() = runTest {
        val p = RecordingProvider(deltas = listOf("hi"))
        val c = controller(AiPolicy.Strict, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("what is eating the disk?", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(AiNotice.Blocked(AiRoute.Reason.STRICT_NEEDS_DEVICE), c.notice)
        assertFalse(p.built)
        assertTrue(c.turns.isEmpty())
    }

    @Test
    fun `globally disabled provider blocks even under a permissive policy`() = runTest {
        val p = RecordingProvider(deltas = listOf("hi"))
        val c = controller(AiPolicy.Permissive, AiSettings(apiKey = "sk-x", provider = AiProviderKind.OFF), p, this)

        c.ask("hello", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(AiNotice.Blocked(AiRoute.Reason.AI_DISABLED), c.notice)
        assertFalse(p.built)
    }

    @Test
    fun `a full round trip appends the user turn and the reply`() = runTest {
        val p = RecordingProvider(deltas = listOf("The journal", " is the largest."))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("what is eating the disk?", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(2, c.turns.size)
        assertEquals(AiRole.USER, c.turns[0].role)
        assertEquals("what is eating the disk?", c.turns[0].text)
        assertEquals(AiRole.ASSISTANT, c.turns[1].role)
        assertEquals("The journal is the largest.", c.turns[1].text)
        assertFalse(c.busy)
        assertNull(c.streaming)
        assertNull(c.notice)
    }

    @Test
    fun `the request carries a system prompt and the prior conversation`() = runTest {
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("first question", outputs = emptyList())
        advanceUntilIdle()
        c.ask("and the docker layers?", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(AiRole.SYSTEM, p.lastMessages.first().role)
        assertEquals(
            listOf("first question", "ok", "and the docker layers?"),
            p.lastMessages.drop(1).map { it.content },
        )
    }

    @Test
    fun `the counter decides how many recent outputs are attached`() = runTest {
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)
        val outputs = listOf("# df -h\n42G", "# free -h\n7.8Gi", "# uptime\nload 0.42")

        c.selectContextOutputs(2)
        c.ask("what is eating the disk?", outputs = outputs)
        advanceUntilIdle()

        val sent = p.lastMessages.last().content
        assertTrue(sent.contains("free -h"), sent)
        assertTrue(sent.contains("uptime"), sent)
        assertFalse(sent.contains("df -h"), sent)
        assertTrue(sent.contains("what is eating the disk?"), sent)
        // What was attached is visible in the feed, so the user sees what left the machine.
        assertEquals(2, c.turns[0].outputs)
    }

    @Test
    fun `a zero counter attaches nothing`() = runTest {
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.selectContextOutputs(0)
        c.ask("hello", outputs = listOf("# df -h\n42G"))
        advanceUntilIdle()

        assertEquals("hello", p.lastMessages.last().content)
        assertEquals(0, c.turns[0].outputs)
    }

    @Test
    fun `explain attaches its output even when the context counter is off`() = runTest {
        // The Explain button is about one specific chunk the user is looking at; the counter governs
        // what rides along with a typed question, not this.
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.selectContextOutputs(0)
        c.explain("Explain this output", output = "# df -h\n/dev/sda1  87% /")
        advanceUntilIdle()

        val sent = p.lastMessages.last().content
        assertTrue(sent.contains("87%"), sent)
        assertEquals("Explain this output", c.turns[0].text)
        assertEquals(1, c.turns[0].outputs)
    }

    @Test
    fun `explain with nothing on screen does not send a bare question`() = runTest {
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.explain("Explain this output", output = "   ")
        advanceUntilIdle()

        assertFalse(p.built)
        assertTrue(c.turns.isEmpty())
    }

    @Test
    fun `the reply language is stated in the system prompt`() = runTest {
        // A small local model mirrors the prompt's language; the UI locale has to reach it or the
        // answer comes back in English next to a Russian interface.
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = SessionAssistantController(
            AiPolicy.Balanced,
            settings = { AiSettings(apiKey = "sk-x") },
            providerFactory = { p },
            scope = this,
            responseLanguage = { "Russian" },
        )

        c.ask("hello", outputs = emptyList())
        advanceUntilIdle()

        assertTrue(p.lastMessages.first().content.contains("Russian"))
    }

    @Test
    fun `secrets are stripped from the prompt and from the attached output`() = runTest {
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.selectContextOutputs(1)
        // Terminal output is the risky half: it carries whatever scrolled past, including an
        // exported password. The prompt goes through the same redactor.
        c.ask("why does token=abc-secret-value fail?", outputs = listOf("# env | grep PG\nPGPASSWORD=hunter2"))
        advanceUntilIdle()

        val sent = p.lastMessages.last().content
        assertFalse(sent.contains("hunter2"), sent)
        assertFalse(sent.contains("abc-secret-value"), sent)
        assertTrue(sent.contains("PGPASSWORD="), "the key stays visible, only the value is masked")
        // The feed shows the redacted text, so history and display agree with what was sent.
        assertFalse(c.turns[0].text.contains("abc-secret-value"))
    }

    @Test
    fun `cancelling mid-flight clears busy and keeps the conversation`() = runTest {
        val p = RecordingProvider(deltas = listOf("partial"), hang = true)
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        runCurrent()
        assertTrue(c.busy)
        c.cancel()
        advanceUntilIdle()

        assertFalse(c.busy)
        assertNull(c.streaming)
        assertEquals(1, c.turns.size)
        assertEquals(AiRole.USER, c.turns.single().role)
    }

    @Test
    fun `a cancelled request cannot clear the state of the next one`() = runTest {
        val hanging = RecordingProvider(deltas = listOf("old"), hang = true)
        val fresh = RecordingProvider(deltas = listOf("new answer"))
        var current: AiProvider = hanging
        val c = SessionAssistantController(
            AiPolicy.Balanced,
            settings = { AiSettings(apiKey = "sk-x") },
            providerFactory = { current },
            scope = this,
        )

        c.ask("first", outputs = emptyList())
        runCurrent()
        c.cancel()
        current = fresh
        c.ask("second", outputs = emptyList())
        advanceUntilIdle()

        assertFalse(c.busy)
        assertEquals("new answer", c.turns.last().text)
        assertEquals(AiRole.ASSISTANT, c.turns.last().role)
    }

    @Test
    fun `a provider failure becomes a notice and releases busy`() = runTest {
        val p = RecordingProvider(failWith = AiException(AiException.Kind.UNAUTHORIZED, "no"))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(AiNotice.Error(AiFailure.UNAUTHORIZED), c.notice)
        assertFalse(c.busy)
        assertNull(c.streaming)
    }

    @Test
    fun `asking while busy is ignored`() = runTest {
        val p = RecordingProvider(deltas = listOf("partial"), hang = true)
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("first", outputs = emptyList())
        runCurrent()
        c.ask("second", outputs = emptyList())
        runCurrent()

        assertEquals(1, c.turns.size)
        c.cancel()
    }

    @Test
    fun `a reply that lands after the stop button does not enter the conversation`() = runTest {
        // cancel() only requests cancellation: a stream that already finished collecting reaches its
        // completion callback without crossing a suspension point, so the guard has to be on the
        // callback itself — otherwise the answer the user just stopped appears in the feed.
        var c: SessionAssistantController? = null
        val p = RecordingProvider(deltas = listOf("stale answer"), beforeCompleting = { c?.cancel() })
        c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(1, c.turns.size, "only the user turn survives a cancelled request")
        assertEquals(AiRole.USER, c.turns.single().role)
        assertFalse(c.busy)
    }

    @Test
    fun `a failure that lands after the stop button raises no notice`() = runTest {
        var c: SessionAssistantController? = null
        val p = RecordingProvider(
            deltas = listOf("partial"),
            failWith = null,
            beforeCompleting = { c?.cancel(); throw AiException(AiException.Kind.NETWORK, "late") },
        )
        c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        advanceUntilIdle()

        assertNull(c.notice, "a cancelled request must not report its failure over the next state")
    }

    @Test
    fun `a delta that lands after the stop button does not resurrect the streaming text`() = runTest {
        // The deltas keep arriving until the cancellation reaches a suspension point; an unguarded
        // onDelta would put the stopped answer back on screen after the panel cleared it.
        var c: SessionAssistantController? = null
        val p = RecordingProvider(
            deltas = listOf("first", " second"),
            afterFirstDelta = { c?.cancel() },
        )
        c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        advanceUntilIdle()

        assertNull(c.streaming, "a cancelled request must not keep writing into the panel")
        assertFalse(c.busy)
    }

    @Test
    fun `an empty reply says the model answered nothing, not that it was not a command`() = runTest {
        // Rejected is the one-shot bar's "that is not a command"; here the question was free-form
        // prose and the model simply returned nothing.
        val p = RecordingProvider(deltas = listOf("   "))
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("what is eating the disk?", outputs = emptyList())
        advanceUntilIdle()

        assertEquals(AiNotice.NoAnswer, c.notice)
        assertEquals(1, c.turns.size)
        assertFalse(c.busy)
    }

    @Test
    fun `a permissive policy sends the question unredacted`() = runTest {
        // Permissive is the documented "non-sensitive systems only" escape hatch: the redactor is
        // deliberately off there, and the false branch has to stay reachable.
        val p = RecordingProvider(deltas = listOf("ok"))
        val c = controller(AiPolicy.Permissive, AiSettings(apiKey = "sk-x"), p, this)

        c.selectContextOutputs(1)
        c.ask("why does token=abc-secret-value fail?", outputs = listOf("PGPASSWORD=hunter2"))
        advanceUntilIdle()

        val sent = p.lastMessages.last().content
        assertTrue(sent.contains("abc-secret-value"), sent)
        assertTrue(sent.contains("hunter2"), sent)
    }

    @Test
    fun `a downloaded local model answers under the strict policy`() = runTest {
        // Strict routes to the device endpoint; without this the panel is blocked on every host that
        // opted out of the cloud, model downloaded or not.
        val p = RecordingProvider(deltas = listOf("local answer"))
        val c = SessionAssistantController(
            AiPolicy.Strict,
            settings = { AiSettings(apiKey = "sk-x") },
            providerFactory = { p },
            scope = this,
            localInstalled = { true },
        )

        c.ask("what is eating the disk?", outputs = emptyList())
        advanceUntilIdle()

        assertNull(c.notice)
        assertTrue(p.built)
        assertEquals("local answer", c.turns.last().text)
    }

    @Test
    fun `clear drops the conversation and cancels the request`() = runTest {
        val p = RecordingProvider(deltas = listOf("partial"), hang = true)
        val c = controller(AiPolicy.Balanced, AiSettings(apiKey = "sk-x"), p, this)

        c.ask("question", outputs = emptyList())
        runCurrent()
        c.clear()
        advanceUntilIdle()

        assertTrue(c.turns.isEmpty())
        assertFalse(c.busy)
        assertNull(c.notice)
    }
}

// ---------- Agent mode (v0.4.0) ----------

/** A provider that answers each chat call from a queue, so a multi-round agent loop is scriptable. */
private class QueueProvider(private val replies: List<List<String>>) : AiProvider {
    var calls = 0
    var lastMessages: List<AiMessage> = emptyList()
    override fun chat(request: AiChatRequest): Flow<AiDelta> = flow {
        lastMessages = request.messages
        val reply = replies.getOrNull(calls++) ?: emptyList()
        reply.forEach { emit(AiDelta(it)) }
    }
    override suspend fun close() {}
}

private fun agentSettings() = AiSettings(apiKey = "sk-x", agentEnabled = true)

private fun agentController(
    provider: AiProvider,
    scope: CoroutineScope,
    settings: AiSettings = agentSettings(),
) = SessionAssistantController(
    AiPolicy.Permissive,
    settings = { settings },
    providerFactory = { provider },
    scope = scope,
)

@Test
fun `agent executes a safe command automatically and completes on DONE`() = runTest {
    val p = QueueProvider(
        listOf(
            listOf("ACTION: CMD df -h\nINFO: disk usage"),
            listOf("ACTION: DONE 磁盘使用正常，剩余 50G"),
        ),
    )
    val c = agentController(p, this)
    val sent = mutableListOf<String>()
    c.agentExecutor = { sent += it }
    c.agentOutputSource = { "Filesystem 50G used / 100G total" }

    c.startAgent("check disk usage")
    advanceUntilIdle()

    assertEquals(listOf("df -h\r"), sent, "safe command runs without confirmation")
    assertEquals(1, c.agentStepCount)
    assertEquals(app.skerry.shared.ai.AgentLoopState.Done, c.agentState)
    assertEquals("磁盘使用正常，剩余 50G", c.agentSummary)
    assertEquals("磁盘使用正常，剩余 50G", c.turns.last().text)
}

@Test
fun `agent danger command waits for confirmation and runs on confirm`() = runTest {
    val p = QueueProvider(listOf(listOf("ACTION: CMD rm -rf /tmp/old")))
    val c = agentController(p, this)
    val sent = mutableListOf<String>()
    c.agentExecutor = { sent += it }

    c.startAgent("clean up /tmp/old")
    advanceUntilIdle()

    assertEquals(app.skerry.shared.ai.AgentLoopState.AwaitingConfirm, c.agentState)
    assertTrue(sent.isEmpty(), "danger command must not auto-run")
    assertEquals("rm -rf /tmp/old", c.agentProposal?.command)

    c.agentConfirm()
    advanceUntilIdle()

    assertEquals(listOf("rm -rf /tmp/old\r"), sent)
}

@Test
fun `agent reject skips the command and lets the model continue`() = runTest {
    val p = QueueProvider(
        listOf(
            listOf("ACTION: CMD rm -rf /tmp/old"),
            listOf("ACTION: DONE 跳过删除，任务结束"),
        ),
    )
    val c = agentController(p, this)
    val sent = mutableListOf<String>()
    c.agentExecutor = { sent += it }

    c.startAgent("clean up /tmp/old")
    advanceUntilIdle()
    assertEquals(app.skerry.shared.ai.AgentLoopState.AwaitingConfirm, c.agentState)

    c.agentReject()
    advanceUntilIdle()

    assertTrue(sent.isEmpty(), "rejected command must never reach the shell")
    assertEquals(app.skerry.shared.ai.AgentLoopState.Done, c.agentState)
}

@Test
fun `agent stop interrupts and sends SIGINT to an in-flight command`() = runTest {
    val p = QueueProvider(listOf(listOf("ACTION: CMD sleep 30")))
    val c = agentController(p, this)
    val sent = mutableListOf<String>()
    c.agentExecutor = { sent += it }

    c.startAgent("wait a bit")
    advanceUntilIdle()
    assertEquals(listOf("sleep 30\r"), sent)

    c.agentStop()
    advanceUntilIdle()

    assertEquals(listOf("sleep 30\r", "\u0003"), sent, "SIGINT must follow the in-flight command")
    assertEquals(app.skerry.shared.ai.AgentLoopState.Interrupted, c.agentState)
}

@Test
fun `agent ask pauses for the user and re-enters with the answer`() = runTest {
    val p = QueueProvider(
        listOf(
            listOf("ACTION: ASK 要清理哪个目录？"),
            listOf("ACTION: DONE 已清理 /tmp"),
        ),
    )
    val c = agentController(p, this)

    c.startAgent("clean up some directory")
    advanceUntilIdle()

    assertEquals(app.skerry.shared.ai.AgentLoopState.Asking, c.agentState)
    assertEquals("要清理哪个目录？", c.agentQuestion)
    assertEquals("要清理哪个目录？", c.turns.last().text)

    c.startAgent("/tmp") // user's answer continues the same loop
    advanceUntilIdle()

    assertEquals(app.skerry.shared.ai.AgentLoopState.Done, c.agentState)
    assertEquals("已清理 /tmp", c.agentSummary)
}

@Test
fun `agent output fed back to the model is sanitized`() = runTest {
    val p = QueueProvider(
        listOf(
            listOf("ACTION: CMD df -h"),
            listOf("ACTION: DONE done"),
        ),
    )
    val c = agentController(p, this)
    c.agentExecutor = {}
    c.agentOutputSource = { "\u001b[31mred\u001b[0m\n\n\n  text" }

    c.startAgent("check disk")
    advanceUntilIdle()

    // The second call's system prompt must carry the sanitized output (no ANSI, blank runs collapsed).
    val system = p.lastMessages.first { it.role == AiRole.SYSTEM }.content
    assertTrue(system.contains("red\n  text"), "output must be ANSI-stripped: $system")
    assertFalse(system.contains("\u001b"), "control bytes must not reach the model")
}

@Test
fun `agent runs several safe commands automatically before DONE`() = runTest {
    // The core Agent-mode promise: one question authorizes a whole multi-step task, with every
    // safe command executed automatically and its output fed back before the next round.
    val p = QueueProvider(
        listOf(
            listOf("ACTION: CMD df -h\nINFO: step 1"),
            listOf("ACTION: CMD du -sh /tmp\nINFO: step 2"),
            listOf("ACTION: DONE 全部完成"),
        ),
    )
    val c = agentController(p, this)
    val sent = mutableListOf<String>()
    c.agentExecutor = { sent += it }
    c.agentOutputSource = { "Filesystem 50G used / 100G total" }

    c.startAgent("analyze disk usage")
    advanceUntilIdle()

    assertEquals(
        listOf("df -h\r", "du -sh /tmp\r"),
        sent,
        "every safe command in the chain must auto-run without confirmation",
    )
    assertEquals(2, c.agentStepCount)
    assertEquals(app.skerry.shared.ai.AgentLoopState.Done, c.agentState)
    assertEquals("全部完成", c.agentSummary)
    // The model's second round must have seen the first command's output in its system prompt.
    val system = p.lastMessages.first { it.role == AiRole.SYSTEM }.content
    assertTrue(system.contains("50G used"), "step output must be fed back to the model: $system")
}

@Test
fun `agent forceConfirmAll pauses even for safe commands`() = runTest {
    val p = QueueProvider(listOf(listOf("ACTION: CMD uptime")))
    val c = agentController(p, this, agentSettings().copy(agentForceConfirm = true))
    val sent = mutableListOf<String>()
    c.agentExecutor = { sent += it }

    c.startAgent("check uptime")
    advanceUntilIdle()

    assertEquals(app.skerry.shared.ai.AgentLoopState.AwaitingConfirm, c.agentState)
    assertTrue(sent.isEmpty(), "a safe command must still wait under forceConfirmAll")

    c.agentConfirm()
    advanceUntilIdle()

    assertEquals(listOf("uptime\r"), sent)
}

@Test
fun `agent stops with a summary at the step limit`() = runTest {
    val p = QueueProvider(
        listOf(
            listOf("ACTION: CMD echo 1"),
            listOf("ACTION: CMD echo 2"),
            listOf("ACTION: CMD echo 3"),
        ),
    )
    val c = agentController(p, this, agentSettings().copy(agentMaxSteps = 2))
    val sent = mutableListOf<String>()
    c.agentExecutor = { sent += it }
    c.agentOutputSource = { "ok" }

    c.startAgent("echo a few things")
    advanceUntilIdle()

    assertEquals(listOf("echo 1\r", "echo 2\r"), sent, "the step after the limit must not run")
    assertEquals(app.skerry.shared.ai.AgentLoopState.Failed, c.agentState)
    assertNotNull(c.agentSummary, "the guard reason must surface as the summary")
    assertFalse(c.busy)
}
