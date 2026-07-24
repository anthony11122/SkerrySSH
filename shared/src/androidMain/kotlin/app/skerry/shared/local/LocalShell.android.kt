package app.skerry.shared.local

import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android local shell over a real controlling PTY. The child setup (forkpty + login_tty + execvp)
 * and the byte IO run in a small native helper ([LocalPty], `libskerrypty.so`, built by the
 * androidApp module) because a JVM cannot safely fork() and keep running managed code in the child.
 * A failure to start (no /dev/ptmx, forkpty error, missing library) becomes a
 * [LocalShellUnavailableException] the transport turns into a clear connect error.
 */
actual object LocalShell {
    actual fun start(config: LocalShellConfig): LocalShellHandle {
        val command = config.command.toTypedArray()
        // Android apps start with cwd `/`, which SELinux won't let an untrusted_app list. Default to
        // the app's private files dir (set at startup) so the shell opens in a readable, writable
        // place and `~`/HOME resolve there; an explicit workingDir/env from the caller still wins.
        val home = LocalShellEnvironment.homeDir
        val workingDir = config.workingDir ?: LocalShellEnvironment.startDir ?: home
        val envMap = if (home != null && "HOME" !in config.env) config.env + ("HOME" to home) else config.env
        val env = envMap.map { "${it.key}=${it.value}" }.toTypedArray()
        val fds = try {
            LocalPty.start(command, config.cols, config.rows, workingDir, env)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError (library missing) or any native failure — report unavailable.
            throw LocalShellUnavailableException("Failed to start the local shell", e)
        }
        if (fds.size < 4 || fds[0] < 0) {
            throw LocalShellUnavailableException("Could not open a pseudo-terminal for the local shell")
        }
        return NativePtyHandle(
            master = fds[0].toInt(),
            pid = fds[1].toInt(),
            wakeR = fds[2].toInt(),
            wakeW = fds[3].toInt(),
        )
    }
}

/**
 * Startup-supplied environment for the Android local shell. The shared [LocalShell] object has no
 * Android Context, so the app sets these once at launch: [homeDir] is the app's private files dir
 * (always writable → the shell's HOME and last-resort cwd); [startDir] is where the shell opens by
 * default — the shared external-storage root, so it lands like a file manager rather than in the
 * sandbox. Writing outside [homeDir] is still subject to scoped storage.
 */
object LocalShellEnvironment {
    @Volatile
    var homeDir: String? = null

    @Volatile
    var startDir: String? = null
}

/**
 * One open PTY session as fd-only state (nothing to free from two threads). Blocking [read] is
 * unblocked by [close] via the native wake pipe; writes/resize are no-ops once closed so a shell
 * that already exited can't surface a native error as a connection failure.
 */
private class NativePtyHandle(
    private val master: Int,
    private val pid: Int,
    private val wakeR: Int,
    private val wakeW: Int,
) : LocalShellHandle {

    private val closed = AtomicBoolean(false)

    override val isOpen: Boolean get() = !closed.get()

    // StreamShellChannel's contract: read<0 is a genuine shell EOF (drives cleanExit), an IOException
    // is our own close()/transport drop. close() unblocks a blocked native read via the wake pipe,
    // which returns -1 — so a -1 seen once we're closing is our teardown, not the shell exiting, and
    // must surface as IOException (else a user-initiated close is misreported as a clean shell exit).
    override fun read(buffer: ByteArray): Int {
        if (closed.get()) throw IOException("Local shell closed")
        val n = LocalPty.read(master, wakeR, buffer, buffer.size)
        if (n < 0 && closed.get()) throw IOException("Local shell closed")
        return n
    }

    override fun write(data: ByteArray) {
        if (closed.get()) return
        if (LocalPty.write(master, data, data.size) < 0) {
            throw IOException("Failed to write to the local shell")
        }
    }

    override fun resize(cols: Int, rows: Int) {
        if (!closed.get()) LocalPty.resize(master, cols, rows)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) LocalPty.close(master, wakeR, wakeW, pid)
    }
}

/**
 * JNI surface of `libskerrypty.so`. [start] returns `{masterFd, pid, wakeReadFd, wakeWriteFd}` (or a
 * single `-1` on failure); the rest operate on those fds. The library is loaded on first use; a load
 * failure throws from here and is caught in [LocalShell.start].
 */
internal object LocalPty {
    init {
        System.loadLibrary("skerrypty")
    }

    external fun start(command: Array<String>, cols: Int, rows: Int, workingDir: String?, env: Array<String>): LongArray

    external fun read(master: Int, wakeR: Int, buffer: ByteArray, len: Int): Int

    external fun write(master: Int, buffer: ByteArray, len: Int): Int

    external fun resize(master: Int, cols: Int, rows: Int)

    external fun close(master: Int, wakeR: Int, wakeW: Int, pid: Int)
}
