package app.skerry.ui.metrics

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.skerry.shared.ssh.ExecResult
import app.skerry.ui.sync.nowMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Periodically polls host resources via [exec] (one exec channel per cycle) and publishes a fresh
 * [HostMetrics] to [metrics], a rolling [history] for the sparklines, and network rates derived
 * from the counter deltas. Polling runs on the session's [scope] (like
 * [app.skerry.ui.sftp.SftpController]) — survives tab/panel switches and stops with the session
 * ([stop] from [app.skerry.ui.connection.ConnectionController.disconnect]).
 *
 * A single poll failure (dropped channel) doesn't kill the loop or clear the last snapshot:
 * [metrics] simply doesn't update until the next successful cycle. A host that *can't* serve
 * metrics is a different case: no exec channel (telnet/serial) or output that never parses
 * (non-Linux) ends as [MetricsAvailability.Unsupported] and stops the loop, so the panel says so
 * instead of showing "…" forever over a stream of pointless round-trips.
 */
@Stable
class HostMetricsController(
    private val exec: suspend (String) -> ExecResult,
    private val scope: CoroutineScope,
    // Delay between polls AFTER the round-trip (excludes exec time, which includes a ~0.4s sleep
    // for the CPU sample) — the real period is approximately intervalMs + exec duration.
    intervalMs: Long = 3_000,
    // Wall clock for the network rates: rates divide the counter delta by the *actual* elapsed
    // time, which is intervalMs plus a variable round-trip. Injectable for tests.
    private val timeSource: TimeSource = TimeSource.Monotonic,
    // Wall-clock stamp for the alert feed — a monotonic mark can't say "2 h ago" after the machine
    // slept. Injectable for tests.
    private val now: () -> Long = { nowMillis() },
) {
    var metrics: HostMetrics? by mutableStateOf(null)
        private set

    /** Rolling window for the sparklines, oldest sample first, capped at [METRICS_HISTORY_SIZE]. */
    var history: List<MetricsSample> by mutableStateOf(emptyList())
        private set

    /** Network throughput of the host (all interfaces but loopback), bytes per second. */
    var netRxRate: Long by mutableStateOf(0)
        private set
    var netTxRate: Long by mutableStateOf(0)
        private set

    var availability: MetricsAvailability by mutableStateOf(MetricsAvailability.Probing)
        private set

    /** Thresholds crossed (and recovered from) while this session has been connected. */
    val alerts: HostAlertLog = HostAlertLog()

    /** How long the loop waits between polls; the monitor screen offers a few values. */
    var intervalMs: Long by mutableStateOf(intervalMs)
        private set

    /** Wall-clock stamp of the last published snapshot — what "refreshed N s ago" counts from. */
    var lastUpdateMillis: Long? by mutableStateOf(null)
        private set

    // Conflated: a burst of refresh clicks is one extra poll, not a queue of them.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var job: Job? = null
    private var lastMark: TimeMark? = null
    private var lastRx: Long? = null
    private var lastTx: Long? = null
    private var unparsablePolls = 0
    private var execFailures = 0
    private var polls = 0

    /**
     * Host OS family, locked in on the first answer that parses. `null` while unknown: the Linux
     * command is tried first, and one parse failure falls back to the Windows PowerShell probe
     * (a Windows OpenSSH shell can't run the /proc chain, so its stdout won't parse). Once locked,
     * every poll uses the matching command — no repeated probing.
     */
    private var platform: MetricsPlatform? = null

    /** Starts periodic polling (idempotent: a repeat call does not start a second loop). */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val full = polls++ % FULL_POLL_EVERY == 0
                // False stops the loop: an exec-less transport or output that can never parse is
                // a verdict, not a hiccup, and retrying would burn round-trips on nothing.
                if (!pollOnce(full)) return@launch
                // Leaves early when the screen asks for a poll now ([refreshNow]); otherwise this is
                // the plain interval wait.
                withTimeoutOrNull(intervalMs) { wake.receive() }
            }
        }
    }

    /**
     * One poll cycle: run the command for the current platform (probing Windows while unknown),
     * parse the answer and publish it. Returns `false` when polling must stop for good — either
     * no exec channel at all, or enough consecutive failures/unparsable answers to call the host
     * unable to serve metrics.
     */
    private suspend fun pollOnce(full: Boolean): Boolean {
        val result = runCatching { exec(commandFor(full)) }
            .onFailure {
                if (it is CancellationException) throw it
                // A transport with no exec channel at all (telnet/serial/Mosh) can never
                // answer — a verdict, not a hiccup.
                if (it is UnsupportedOperationException) {
                    availability = MetricsAvailability.Unsupported
                    return false
                }
            }
            .getOrNull()
        return if (result == null) handleExecFailure() else handleResult(result, full)
    }

    /** The metrics command for the current platform; while unknown, the Linux chain is tried. */
    private fun commandFor(full: Boolean): String = when {
        platform == MetricsPlatform.Windows && full -> FULL_WINDOWS_COMMAND
        platform == MetricsPlatform.Windows -> WINDOWS_COMMAND
        full -> FULL_METRICS_COMMAND
        else -> METRICS_COMMAND
    }

    /**
     * A dropped channel is a hiccup while the host has already answered once; a channel that has
     * never answered is a host that cannot serve metrics at all (a restricted shell rejecting the
     * command). `false` means the verdict was reached and polling must stop.
     */
    private fun handleExecFailure(): Boolean {
        if (++execFailures >= EXEC_FAILURES_BEFORE_VERDICT && metrics == null) {
            availability = MetricsAvailability.Unsupported
            return false
        }
        return true
    }

    /** Parses and publishes one answer; a non-parsing answer probes Windows while unknown. */
    private suspend fun handleResult(result: ExecResult, full: Boolean): Boolean {
        execFailures = 0
        val parsed = parseHostMetrics(result.stdout)
        if (parsed != null) {
            // First parseable answer locks the platform family for this session.
            platform = platform ?: MetricsPlatform.Linux
            unparsablePolls = 0
            publish(parsed, full)
            return true
        }
        // The Linux /proc output doesn't parse — this may be a Windows host whose shell rejected
        // the whole chain. Try the PowerShell probe once; success locks Windows.
        if (platform == null) {
            val winParsed = runCatching { exec(WINDOWS_COMMAND) }
                .getOrNull()?.stdout?.let { parseHostMetrics(it) }
            if (winParsed != null) {
                platform = MetricsPlatform.Windows
                unparsablePolls = 0
                publish(winParsed, full)
                return true
            }
        }
        // Output that never parses (or stopped parsing on a locked platform) is a verdict rather
        // than an endless "…" over pointless round-trips.
        return handleUnparsable()
    }

    /** Counts one unparsable answer; `false` when the three-strike verdict is reached. */
    private fun handleUnparsable(): Boolean {
        if (++unparsablePolls >= UNPARSABLE_POLLS_BEFORE_VERDICT) {
            availability = MetricsAvailability.Unsupported
            return false
        }
        return true
    }

    /** Polls now instead of waiting out the rest of the interval (the screen's refresh button). */
    fun refreshNow() {
        wake.trySend(Unit)
    }

    /** Changes the poll period. The wait already running keeps its old length. */
    fun setInterval(ms: Long) {
        intervalMs = ms
    }

    /** Stops polling. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Publishes [parsed], carrying the unit and container lists of the previous snapshot over a
     * cheap poll — those sections are only asked for every [FULL_POLL_EVERY]-th round-trip, and
     * without this their cards would blink out in between. A [full] poll did ask, so its answer
     * stands as given: an empty list there means the container really is gone.
     */
    private fun publish(parsed: HostMetrics, full: Boolean) {
        updateNetRates(parsed)
        val previous = metrics
        val merged = if (full) {
            parsed
        } else {
            parsed.copy(
                services = previous?.services.orEmpty(),
                containers = previous?.containers.orEmpty(),
            )
        }
        metrics = merged
        val stamp = now()
        lastUpdateMillis = stamp
        alerts.update(merged, stamp)
        availability = MetricsAvailability.Live
        history = history.appendCapped(
            MetricsSample(
                cpuPercent = parsed.cpuPercent,
                memPercent = (parsed.memFraction * 100).toInt(),
                rxBytesPerSec = netRxRate,
                txBytesPerSec = netTxRate,
            ),
        )
    }

    /**
     * Rates from the counter delta over the time actually elapsed since the previous poll. The
     * first poll has nothing to compare against; a counter that went backwards means a reboot or
     * an interface reset, and reports zero rather than a nonsense spike.
     */
    private fun updateNetRates(parsed: HostMetrics) {
        val mark = timeSource.markNow()
        val elapsedMs = lastMark?.elapsedNow()?.inWholeMilliseconds ?: 0
        val rx = parsed.netRxBytes
        val tx = parsed.netTxBytes
        if (rx != null && tx != null) {
            val prevRx = lastRx
            val prevTx = lastTx
            if (prevRx != null && prevTx != null && elapsedMs > 0) {
                netRxRate = rate(rx - prevRx, elapsedMs)
                netTxRate = rate(tx - prevTx, elapsedMs)
            }
            lastRx = rx
            lastTx = tx
        }
        lastMark = mark
    }

    private fun rate(deltaBytes: Long, elapsedMs: Long): Long =
        if (deltaBytes < 0) 0 else deltaBytes * 1000 / elapsedMs

    companion object {
        /** Consecutive unparsable answers before a host is declared unable to serve metrics. */
        private const val UNPARSABLE_POLLS_BEFORE_VERDICT = 3

        /** Consecutive exec failures, with no snapshot ever published, before the same verdict. */
        private const val EXEC_FAILURES_BEFORE_VERDICT = 3

        /**
         * How often the poll also asks for systemd units and containers ([FULL_METRICS_COMMAND]).
         * Those two are the only parts of the round-trip that cost real work on the host —
         * `systemctl` talks to the manager and `docker stats` samples every container — while
         * neither changes between seconds. Every fifth poll is roughly a quarter-minute at the
         * default interval.
         */
        const val FULL_POLL_EVERY = 5

        /** Rows a list section is trimmed to on the host, so a busy server doesn't ship a novel. */
        private const val LIST_ROWS = 8

        /**
         * The expensive tail of the poll: systemd units worth showing (running, plus the ones that
         * are starting or broken) and the container list with its CPU share. Everything here is
         * optional — no systemd, no docker, or no permission to talk to either simply yields an
         * empty section, and the corresponding card isn't drawn. `docker stats` runs under
         * `timeout` because it blocks on an unresponsive daemon, which would otherwise stall the
         * whole cycle; podman is tried when docker isn't there.
         */
        private const val UNITS_TAIL: String =
            "echo '@SERVICES'; systemctl list-units --type=service --no-legend --no-pager --plain " +
                "--state=running,activating,failed 2>/dev/null | head -$LIST_ROWS; " +
                "echo '@CONTAINERS'; { docker ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}' 2>/dev/null || " +
                "podman ps --format '{{.Names}}\t{{.Image}}\t{{.Status}}' 2>/dev/null; } | head -$LIST_ROWS; " +
                "echo '@CSTATS'; { timeout 3 docker stats --no-stream --format '{{.Name}}\t{{.CPUPerc}}' 2>/dev/null || " +
                "timeout 3 podman stats --no-stream --format '{{.Name}}\t{{.CPU}}' 2>/dev/null; } | head -$LIST_ROWS"

        /**
         * One command, one round-trip: two /proc/stat samples for CPU delta, then memory, disks,
         * network counters, the top processes by CPU, and host facts (uptime, load average, OS,
         * kernel, CPU count). Markers `@MEM`/`@DISK`/`@NET`/`@PROC`/`@UPTIME`/`@LOAD`/`@OS`/
         * `@KERNEL`/`@CPU` separate sections for [parseHostMetrics]. Everything is cheap (/proc
         * plus df/ps/uname), so it all rides the same cycle; the parser just re-reads the static
         * facts (OS/kernel/CPU). Assumes a POSIX shell (`;`-chained commands) and Linux (/proc,
         * free -b, df -Pk); on other systems the missing sections simply yield `null`/empty fields
         * (see [parseHostMetrics]), and output that never parses ends as
         * [MetricsAvailability.Unsupported].
         */
        const val METRICS_COMMAND: String =
            "grep '^cpu ' /proc/stat; sleep 0.4; grep '^cpu ' /proc/stat; " +
                "echo '@MEM'; free -b; echo '@DISK'; df -Pk; " +
                "echo '@NET'; cat /proc/net/dev; " +
                "echo '@PROC'; ps -eo pid=,pcpu=,pmem=,rss=,comm= --sort=-pcpu 2>/dev/null | head -$LIST_ROWS; " +
                "echo '@UPTIME'; cat /proc/uptime; echo '@LOAD'; cat /proc/loadavg; " +
                "echo '@OS'; grep '^PRETTY_NAME=' /etc/os-release 2>/dev/null; " +
                "echo '@KERNEL'; uname -s -r -m; echo '@CPU'; nproc"

        /** [METRICS_COMMAND] plus [UNITS_TAIL] — what every [FULL_POLL_EVERY]-th poll sends. */
        val FULL_METRICS_COMMAND: String = "$METRICS_COMMAND; $UNITS_TAIL"

        /**
         * Windows host metrics, collected by a single PowerShell script passed via
         * `-EncodedCommand` (UTF-16LE base64) so no cmd.exe quoting can mangle it. The script
         * prints the same @MARKER sections as [METRICS_COMMAND]: CPU is sampled twice on the host
         * and averaged into one `cpu <percent>` line; memory/disk in KiB-shaped rows; the network
         * counters are cumulative interface bytes (raw perf counters), matching the Linux
         * /proc/net/dev semantics; processes carry cumulative CPU seconds as the `pcpu` column
         * (Windows has no instantaneous %-CPU per process without a second sample). Load average
         * has no Windows equivalent and prints a placeholder. The script body lives at
         * tools/win-metrics/collect.ps1 (source) — the base64 below is generated with:
         * `python3 -c "import base64;print(base64.b64encode(open('collect.ps1','rb').read().decode('utf-8').encode('utf-16-le')).decode())"`
         */
        const val WINDOWS_COMMAND: String =
            "powershell.exe -NoProfile -NonInteractive -EncodedCommand CgAkAEUAcgByAG8AcgBBAGMAdABpAG8AbgBQAHIAZQBmAGUAcgBlAG4AYwBlAD0AJwBTAGkAbABlAG4AdABsAHkAQwBvAG4AdABpAG4AdQBlACcACgAkAG8AcwA9AEcAZQB0AC0AQwBpAG0ASQBuAHMAdABhAG4AYwBlACAAVwBpAG4AMwAyAF8ATwBwAGUAcgBhAHQAaQBuAGcAUwB5AHMAdABlAG0ACgAkAGMAPQBHAGUAdAAtAEMAbwB1AG4AdABlAHIAIAAnAFwAUAByAG8AYwBlAHMAcwBvAHIAKABfAFQAbwB0AGEAbAApAFwAJQAgAFAAcgBvAGMAZQBzAHMAbwByACAAVABpAG0AZQAnAAoAUwB0AGEAcgB0AC0AUwBsAGUAZQBwACAALQBNAGkAbABsAGkAcwBlAGMAbwBuAGQAcwAgADQAMAAwAAoAJABjADIAPQBHAGUAdAAtAEMAbwB1AG4AdABlAHIAIAAnAFwAUAByAG8AYwBlAHMAcwBvAHIAKABfAFQAbwB0AGEAbAApAFwAJQAgAFAAcgBvAGMAZQBzAHMAbwByACAAVABpAG0AZQAnAAoAZQBjAGgAbwAgACgAJwBjAHAAdQAgACcAKwBbAG0AYQB0AGgAXQA6ADoAUgBvAHUAbgBkACgAKAAkAGMALgBDAG8AdQBuAHQAZQByAFMAYQBtAHAAbABlAHMAWwAwAF0ALgBDAG8AbwBrAGUAZABWAGEAbAB1AGUAKwAkAGMAMgAuAEMAbwB1AG4AdABlAHIAUwBhAG0AcABsAGUAcwBbADAAXQAuAEMAbwBvAGsAZQBkAFYAYQBsAHUAZQApAC8AMgAsADEAKQApAAoAZQBjAGgAbwAgACcAQABNAEUATQAnAAoAJAB0AG0APQBbAGwAbwBuAGcAXQAkAG8AcwAuAFQAbwB0AGEAbABWAGkAcwBpAGIAbABlAE0AZQBtAG8AcgB5AFMAaQB6AGUAKgAxADAAMgA0AAoAJABmAG0APQBbAGwAbwBuAGcAXQAkAG8AcwAuAEYAcgBlAGUAUABoAHkAcwBpAGMAYQBsAE0AZQBtAG8AcgB5ACoAMQAwADIANAAKAGUAYwBoAG8AIAAoACcATQBlAG0AOgAgACcAKwAkAHQAbQArACcAIAAnACsAKAAkAHQAbQAtACQAZgBtACkAKQAKAGUAYwBoAG8AIAAnAEAARABJAFMASwAnAAoARwBlAHQALQBDAGkAbQBJAG4AcwB0AGEAbgBjAGUAIABXAGkAbgAzADIAXwBMAG8AZwBpAGMAYQBsAEQAaQBzAGsAIAAtAEYAaQBsAHQAZQByACAAJwBEAHIAaQB2AGUAVAB5AHAAZQA9ADMAJwAgAHwAIABGAG8AcgBFAGEAYwBoAC0ATwBiAGoAZQBjAHQAIAB7ACAAJAB0AD0AWwBsAG8AbgBnAF0AJABfAC4AUwBpAHoAZQA7ACAAJABmAD0AWwBsAG8AbgBnAF0AJABfAC4ARgByAGUAZQBTAHAAYQBjAGUAOwAgAGkAZgAoACQAdAAgAC0AZwB0ACAAMAApAHsAIAAkAHUAPQAkAHQALQAkAGYAOwAgAGUAYwBoAG8AIAAoACQAXwAuAEQAZQB2AGkAYwBlAEkARAArACcAIAAnACsAWwBsAG8AbgBnAF0AKAAkAHQALwAxADAAMgA0ACkAKwAnACAAJwArAFsAbABvAG4AZwBdACgAJAB1AC8AMQAwADIANAApACsAJwAgACcAKwBbAGwAbwBuAGcAXQAoACQAZgAvADEAMAAyADQAKQArACcAIAAnACsAWwBpAG4AdABdACgAMQAwADAAKgAkAHUALwAkAHQAKQArACcAJQAgACcAKwAkAF8ALgBEAGUAdgBpAGMAZQBJAEQAKQAgAH0AIAB9AAoAZQBjAGgAbwAgACcAQABOAEUAVAAnAAoARwBlAHQALQBDAGkAbQBJAG4AcwB0AGEAbgBjAGUAIABXAGkAbgAzADIAXwBQAGUAcgBmAFIAYQB3AEQAYQB0AGEAXwBUAGMAcABpAHAAXwBOAGUAdAB3AG8AcgBrAEkAbgB0AGUAcgBmAGEAYwBlACAAfAAgAFcAaABlAHIAZQAtAE8AYgBqAGUAYwB0ACAAewAgACQAXwAuAE4AYQBtAGUAIAAtAG4AZQAgACcATABvAG8AcABiAGEAYwBrACcAIAB9ACAAfAAgAEYAbwByAEUAYQBjAGgALQBPAGIAagBlAGMAdAAgAHsAIABlAGMAaABvACAAKAAkAF8ALgBOAGEAbQBlACsAJwA6ACAAJwArACQAXwAuAEIAeQB0AGUAcwBSAGUAYwBlAGkAdgBlAGQAUABlAHIAcwBlAGMAKwAnACAAMAAgADAAIAAwACAAMAAgADAAIAAwACAAMAAgACcAKwAkAF8ALgBCAHkAdABlAHMAUwBlAG4AdABQAGUAcgBzAGUAYwArACcAIAAwACAAMAAgADAAIAAwACAAMAAgADAAIAAwACcAKQAgAH0ACgBlAGMAaABvACAAJwBAAFAAUgBPAEMAJwAKAEcAZQB0AC0AUAByAG8AYwBlAHMAcwAgAHwAIABTAG8AcgB0AC0ATwBiAGoAZQBjAHQAIABDAFAAVQAgAC0ARABlAHMAYwBlAG4AZABpAG4AZwAgAHwAIABTAGUAbABlAGMAdAAtAE8AYgBqAGUAYwB0ACAALQBGAGkAcgBzAHQAIAA4ACAAfAAgAEYAbwByAEUAYQBjAGgALQBPAGIAagBlAGMAdAAgAHsAIAAkAGMAdgA9ADAALgAwADsAIABpAGYAKAAkAF8ALgBDAFAAVQApAHsAJABjAHYAPQBbAG0AYQB0AGgAXQA6ADoAUgBvAHUAbgBkACgAJABfAC4AQwBQAFUALAAxACkAfQA7ACAAJAB3AHMAPQBbAGwAbwBuAGcAXQAkAF8ALgBXAG8AcgBrAGkAbgBnAFMAZQB0ADYANAA7ACAAJABwAG0APQAwAC4AMAA7ACAAaQBmACgAJAB0AG0AIAAtAGcAdAAgADAAKQB7ACQAcABtAD0AWwBtAGEAdABoAF0AOgA6AFIAbwB1AG4AZAAoADEAMAAwAC4AMAAqACQAdwBzAC8AJAB0AG0ALAAxACkAfQA7ACAAZQBjAGgAbwAgACgAJABfAC4ASQBkAC4AVABvAFMAdAByAGkAbgBnACgAKQArACcAIAAnACsAJABjAHYAKwAnACAAJwArACQAcABtACsAJwAgACcAKwBbAGwAbwBuAGcAXQAoACQAdwBzAC8AMQAwADIANAApACsAJwAgACcAKwAkAF8ALgBQAHIAbwBjAGUAcwBzAE4AYQBtAGUAKQAgAH0ACgBlAGMAaABvACAAJwBAAFUAUABUAEkATQBFACcACgBlAGMAaABvACAAKABbAGkAbgB0ADYANABdACgAKABHAGUAdAAtAEQAYQB0AGUAKQAtACQAbwBzAC4ATABhAHMAdABCAG8AbwB0AFUAcABUAGkAbQBlACkALgBUAG8AdABhAGwAUwBlAGMAbwBuAGQAcwApAAoAZQBjAGgAbwAgACcAQABMAE8AQQBEACcACgBlAGMAaABvACAAJwAwACAAMAAgADAAJwAKAGUAYwBoAG8AIAAnAEAATwBTACcACgBlAGMAaABvACAAKAAnAFAAUgBFAFQAVABZAF8ATgBBAE0ARQA9ACcAKwAkAG8AcwAuAEMAYQBwAHQAaQBvAG4AKQAKAGUAYwBoAG8AIAAnAEAASwBFAFIATgBFAEwAJwAKAGUAYwBoAG8AIAAoACQAbwBzAC4AVgBlAHIAcwBpAG8AbgArACcAIABiAHUAaQBsAGQAIAAnACsAJABvAHMALgBCAHUAaQBsAGQATgB1AG0AYgBlAHIAKQAKAGUAYwBoAG8AIAAnAEAAQwBQAFUAJwAKAGUAYwBoAG8AIAAkAGUAbgB2ADoATgBVAE0AQgBFAFIAXwBPAEYAXwBQAFIATwBDAEUAUwBTAE8AUgBTAAoA"

        /** [WINDOWS_COMMAND] plus the services section (a cheap query on Windows). */
        const val FULL_WINDOWS_COMMAND: String =
            "powershell.exe -NoProfile -NonInteractive -EncodedCommand CgAkAEUAcgByAG8AcgBBAGMAdABpAG8AbgBQAHIAZQBmAGUAcgBlAG4AYwBlAD0AJwBTAGkAbABlAG4AdABsAHkAQwBvAG4AdABpAG4AdQBlACcACgAkAG8AcwA9AEcAZQB0AC0AQwBpAG0ASQBuAHMAdABhAG4AYwBlACAAVwBpAG4AMwAyAF8ATwBwAGUAcgBhAHQAaQBuAGcAUwB5AHMAdABlAG0ACgAkAGMAPQBHAGUAdAAtAEMAbwB1AG4AdABlAHIAIAAnAFwAUAByAG8AYwBlAHMAcwBvAHIAKABfAFQAbwB0AGEAbAApAFwAJQAgAFAAcgBvAGMAZQBzAHMAbwByACAAVABpAG0AZQAnAAoAUwB0AGEAcgB0AC0AUwBsAGUAZQBwACAALQBNAGkAbABsAGkAcwBlAGMAbwBuAGQAcwAgADQAMAAwAAoAJABjADIAPQBHAGUAdAAtAEMAbwB1AG4AdABlAHIAIAAnAFwAUAByAG8AYwBlAHMAcwBvAHIAKABfAFQAbwB0AGEAbAApAFwAJQAgAFAAcgBvAGMAZQBzAHMAbwByACAAVABpAG0AZQAnAAoAZQBjAGgAbwAgACgAJwBjAHAAdQAgACcAKwBbAG0AYQB0AGgAXQA6ADoAUgBvAHUAbgBkACgAKAAkAGMALgBDAG8AdQBuAHQAZQByAFMAYQBtAHAAbABlAHMAWwAwAF0ALgBDAG8AbwBrAGUAZABWAGEAbAB1AGUAKwAkAGMAMgAuAEMAbwB1AG4AdABlAHIAUwBhAG0AcABsAGUAcwBbADAAXQAuAEMAbwBvAGsAZQBkAFYAYQBsAHUAZQApAC8AMgAsADEAKQApAAoAZQBjAGgAbwAgACcAQABNAEUATQAnAAoAJAB0AG0APQBbAGwAbwBuAGcAXQAkAG8AcwAuAFQAbwB0AGEAbABWAGkAcwBpAGIAbABlAE0AZQBtAG8AcgB5AFMAaQB6AGUAKgAxADAAMgA0AAoAJABmAG0APQBbAGwAbwBuAGcAXQAkAG8AcwAuAEYAcgBlAGUAUABoAHkAcwBpAGMAYQBsAE0AZQBtAG8AcgB5ACoAMQAwADIANAAKAGUAYwBoAG8AIAAoACcATQBlAG0AOgAgACcAKwAkAHQAbQArACcAIAAnACsAKAAkAHQAbQAtACQAZgBtACkAKQAKAGUAYwBoAG8AIAAnAEAARABJAFMASwAnAAoARwBlAHQALQBDAGkAbQBJAG4AcwB0AGEAbgBjAGUAIABXAGkAbgAzADIAXwBMAG8AZwBpAGMAYQBsAEQAaQBzAGsAIAAtAEYAaQBsAHQAZQByACAAJwBEAHIAaQB2AGUAVAB5AHAAZQA9ADMAJwAgAHwAIABGAG8AcgBFAGEAYwBoAC0ATwBiAGoAZQBjAHQAIAB7ACAAJAB0AD0AWwBsAG8AbgBnAF0AJABfAC4AUwBpAHoAZQA7ACAAJABmAD0AWwBsAG8AbgBnAF0AJABfAC4ARgByAGUAZQBTAHAAYQBjAGUAOwAgAGkAZgAoACQAdAAgAC0AZwB0ACAAMAApAHsAIAAkAHUAPQAkAHQALQAkAGYAOwAgAGUAYwBoAG8AIAAoACQAXwAuAEQAZQB2AGkAYwBlAEkARAArACcAIAAnACsAWwBsAG8AbgBnAF0AKAAkAHQALwAxADAAMgA0ACkAKwAnACAAJwArAFsAbABvAG4AZwBdACgAJAB1AC8AMQAwADIANAApACsAJwAgACcAKwBbAGwAbwBuAGcAXQAoACQAZgAvADEAMAAyADQAKQArACcAIAAnACsAWwBpAG4AdABdACgAMQAwADAAKgAkAHUALwAkAHQAKQArACcAJQAgACcAKwAkAF8ALgBEAGUAdgBpAGMAZQBJAEQAKQAgAH0AIAB9AAoAZQBjAGgAbwAgACcAQABOAEUAVAAnAAoARwBlAHQALQBDAGkAbQBJAG4AcwB0AGEAbgBjAGUAIABXAGkAbgAzADIAXwBQAGUAcgBmAFIAYQB3AEQAYQB0AGEAXwBUAGMAcABpAHAAXwBOAGUAdAB3AG8AcgBrAEkAbgB0AGUAcgBmAGEAYwBlACAAfAAgAFcAaABlAHIAZQAtAE8AYgBqAGUAYwB0ACAAewAgACQAXwAuAE4AYQBtAGUAIAAtAG4AZQAgACcATABvAG8AcABiAGEAYwBrACcAIAB9ACAAfAAgAEYAbwByAEUAYQBjAGgALQBPAGIAagBlAGMAdAAgAHsAIABlAGMAaABvACAAKAAkAF8ALgBOAGEAbQBlACsAJwA6ACAAJwArACQAXwAuAEIAeQB0AGUAcwBSAGUAYwBlAGkAdgBlAGQAUABlAHIAcwBlAGMAKwAnACAAMAAgADAAIAAwACAAMAAgADAAIAAwACAAMAAgACcAKwAkAF8ALgBCAHkAdABlAHMAUwBlAG4AdABQAGUAcgBzAGUAYwArACcAIAAwACAAMAAgADAAIAAwACAAMAAgADAAIAAwACcAKQAgAH0ACgBlAGMAaABvACAAJwBAAFAAUgBPAEMAJwAKAEcAZQB0AC0AUAByAG8AYwBlAHMAcwAgAHwAIABTAG8AcgB0AC0ATwBiAGoAZQBjAHQAIABDAFAAVQAgAC0ARABlAHMAYwBlAG4AZABpAG4AZwAgAHwAIABTAGUAbABlAGMAdAAtAE8AYgBqAGUAYwB0ACAALQBGAGkAcgBzAHQAIAA4ACAAfAAgAEYAbwByAEUAYQBjAGgALQBPAGIAagBlAGMAdAAgAHsAIAAkAGMAdgA9ADAALgAwADsAIABpAGYAKAAkAF8ALgBDAFAAVQApAHsAJABjAHYAPQBbAG0AYQB0AGgAXQA6ADoAUgBvAHUAbgBkACgAJABfAC4AQwBQAFUALAAxACkAfQA7ACAAJAB3AHMAPQBbAGwAbwBuAGcAXQAkAF8ALgBXAG8AcgBrAGkAbgBnAFMAZQB0ADYANAA7ACAAJABwAG0APQAwAC4AMAA7ACAAaQBmACgAJAB0AG0AIAAtAGcAdAAgADAAKQB7ACQAcABtAD0AWwBtAGEAdABoAF0AOgA6AFIAbwB1AG4AZAAoADEAMAAwAC4AMAAqACQAdwBzAC8AJAB0AG0ALAAxACkAfQA7ACAAZQBjAGgAbwAgACgAJABfAC4ASQBkAC4AVABvAFMAdAByAGkAbgBnACgAKQArACcAIAAnACsAJABjAHYAKwAnACAAJwArACQAcABtACsAJwAgACcAKwBbAGwAbwBuAGcAXQAoACQAdwBzAC8AMQAwADIANAApACsAJwAgACcAKwAkAF8ALgBQAHIAbwBjAGUAcwBzAE4AYQBtAGUAKQAgAH0ACgBlAGMAaABvACAAJwBAAFUAUABUAEkATQBFACcACgBlAGMAaABvACAAKABbAGkAbgB0ADYANABdACgAKABHAGUAdAAtAEQAYQB0AGUAKQAtACQAbwBzAC4ATABhAHMAdABCAG8AbwB0AFUAcABUAGkAbQBlACkALgBUAG8AdABhAGwAUwBlAGMAbwBuAGQAcwApAAoAZQBjAGgAbwAgACcAQABMAE8AQQBEACcACgBlAGMAaABvACAAJwAwACAAMAAgADAAJwAKAGUAYwBoAG8AIAAnAEAATwBTACcACgBlAGMAaABvACAAKAAnAFAAUgBFAFQAVABZAF8ATgBBAE0ARQA9ACcAKwAkAG8AcwAuAEMAYQBwAHQAaQBvAG4AKQAKAGUAYwBoAG8AIAAnAEAASwBFAFIATgBFAEwAJwAKAGUAYwBoAG8AIAAoACQAbwBzAC4AVgBlAHIAcwBpAG8AbgArACcAIABiAHUAaQBsAGQAIAAnACsAJABvAHMALgBCAHUAaQBsAGQATgB1AG0AYgBlAHIAKQAKAGUAYwBoAG8AIAAnAEAAQwBQAFUAJwAKAGUAYwBoAG8AIAAkAGUAbgB2ADoATgBVAE0AQgBFAFIAXwBPAEYAXwBQAFIATwBDAEUAUwBTAE8AUgBTAAoACgBlAGMAaABvACAAJwBAAFMARQBSAFYASQBDAEUAUwAnAAoARwBlAHQALQBTAGUAcgB2AGkAYwBlACAAfAAgAFcAaABlAHIAZQAtAE8AYgBqAGUAYwB0ACAAewAgACQAXwAuAFMAdABhAHQAdQBzACAALQBuAGUAIAAnAFMAdABvAHAAcABlAGQAJwAgAH0AIAB8ACAAUwBlAGwAZQBjAHQALQBPAGIAagBlAGMAdAAgAC0ARgBpAHIAcwB0ACAAOAAgAHwAIABGAG8AcgBFAGEAYwBoAC0ATwBiAGoAZQBjAHQAIAB7ACAAJABzAD0AJwBhAGMAdABpAHYAZQAnADsAIABpAGYAKAAkAF8ALgBTAHQAYQB0AHUAcwAgAC0AbgBlACAAJwBSAHUAbgBuAGkAbgBnACcAKQB7ACQAcwA9ACcAbwB0AGgAZQByACcAfQA7ACAAZQBjAGgAbwAgACgAJABfAC4ATgBhAG0AZQArACcAIAAtACAAJwArACQAcwArACcAIAAnACsAJABzACkAIAB9AAoA"
    }
}

/** Host OS family the metrics poller talks to; locked in by the first parseable answer. */
enum class MetricsPlatform { Linux, Windows }
