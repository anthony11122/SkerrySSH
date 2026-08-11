package app.skerry.ui.metrics

import app.skerry.shared.ssh.ExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HostMetricsControllerTest {

    private val sample = """
        cpu  100 0 100 800 0 0 0 0
        cpu  150 0 150 900 0 0 0 0
        @MEM
        Mem:     4000000000  2100000000  1000000000
        @DISK
        /dev/sda1  51475068 42000000 6900000 87% /
    """.trimIndent()

    @Test
    fun polls_and_publishes_parsed_metrics() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = HostMetricsController(exec = { ExecResult(0, sample, "") }, scope = scope)

        assertNull(controller.metrics)
        controller.start()

        val m = controller.metrics!!
        assertEquals(50, m.cpuPercent)
        assertEquals(87, m.diskPercent)
        assertEquals(2_100_000_000L, m.memUsedBytes)

        controller.stop()
        scope.cancel()
    }

    @Test
    fun exec_failure_keeps_metrics_null_and_does_not_crash() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = HostMetricsController(exec = { throw RuntimeException("boom") }, scope = scope)

        controller.start()

        assertNull(controller.metrics)
        controller.stop()
        scope.cancel()
    }

    @Test
    fun start_is_idempotent() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        var calls = 0
        val controller = HostMetricsController(
            exec = { calls++; ExecResult(0, sample, "") },
            scope = scope,
        )

        controller.start()
        val afterFirst = calls
        controller.start() // a repeated start must not spawn a second polling loop

        assertEquals(afterFirst, calls)
        controller.stop()
        scope.cancel()
    }

    // --- Platform detection (Linux output that never parses → Windows probe) ----------

    private val windowsSample = """
        cpu 23.5
        @MEM
        Mem: 8589934592 4294967296
        @DISK
        C: 51200000 30000000 21200000 59% C:
    """.trimIndent()

    private val linuxCommands = mutableListOf<String>()
    private val winCommands = mutableListOf<String>()

    @Test
    fun windows_host_is_detected_and_locked_after_first_round_trip() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = HostMetricsController(
            exec = { cmd ->
                if (cmd.contains("powershell.exe")) {
                    winCommands += "win"
                    ExecResult(0, windowsSample, "")
                } else {
                    linuxCommands += "linux"
                    // A Windows OpenSSH shell rejects the /proc chain: stdout empty, exit 1.
                    ExecResult(1, "", "grep: /proc/stat: No such file or directory")
                }
            },
            scope = scope,
        )

        controller.start()

        val m = controller.metrics
        assertNotNull(m)
        assertEquals(59, m.diskPercent)
        assertEquals(4_294_967_296L, m.memUsedBytes)
        // The first round-trip already tried the Linux chain, failed, and answered with the
        // Windows probe (platform locked). Advance one interval to prove the next poll goes
        // straight to the Windows command too — no re-probing.
        assertEquals("linux", linuxCommands.first())
        assertEquals(1, winCommands.size, "first round: probe only")
        advanceTimeBy(3_001)
        runCurrent()
        assertEquals(2, winCommands.size, "second round must reuse the Windows command, not re-probe")
        assertTrue(linuxCommands.size == 1, "Linux chain must not be retried after the platform is locked")

        controller.stop()
        scope.cancel()
    }

    @Test
    fun linux_host_never_touches_the_windows_probe() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = HostMetricsController(
            exec = { cmd ->
                if (cmd.contains("powershell.exe")) winCommands += "win"
                ExecResult(0, sample, "")
            },
            scope = scope,
        )

        controller.start()

        assertNotNull(controller.metrics)
        assertTrue(winCommands.isEmpty(), "a Linux host must never run the PowerShell probe")

        controller.stop()
        scope.cancel()
    }

    @Test
    fun host_that_never_parses_ends_unsupported() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val controller = HostMetricsController(
            exec = { ExecResult(0, "some router banner", "") },
            scope = scope,
        )

        controller.start()
        // Each round tries the Linux chain and the Windows probe once; three rounds of
        // unparsable output end the loop with the verdict (the first round already ran
        // synchronously, so two more intervals complete it).
        advanceTimeBy(3_001)
        runCurrent()
        advanceTimeBy(3_001)
        runCurrent()

        assertEquals(MetricsAvailability.Unsupported, controller.availability)
        assertNull(controller.metrics)
        controller.stop()
        scope.cancel()
    }
}
