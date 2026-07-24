// Native PTY helper for the Android local shell (ConnectionType.LOCAL). A real controlling
// terminal needs forkpty + login_tty (setsid + TIOCSCTTY + wiring the slave to 0/1/2) done
// entirely in native code — a JVM cannot safely fork() and then keep running managed code in the
// child, so the whole child setup and execve happen here before any Kotlin runs again.
//
// The library exposes the LocalPty object (app.skerry.shared.local.LocalPty) declared in
// shared/androidMain; a session is four ints — the PTY master fd, the child pid, and a self-pipe
// used to wake a blocked read() from close() (the reader polls {master, wakePipe}). Ownership is by
// fd only (no heap state) so there is nothing to free from two threads: close() writes the wake
// pipe, hangs up + reaps the child, then closes the fds.
//
// fork()-safety: the child of a fork() in a multithreaded process may only call async-signal-safe
// functions (a background thread — ART GC/JIT, other JNI libs — can hold the malloc arena lock at
// the instant of fork, and the child would deadlock the first time it allocates). So argv, the
// environment, and the resolved executable path are all built in the PARENT; the child does only
// chdir() + execve().

#define _GNU_SOURCE 1 // expose pipe2/forkpty/login_tty in bionic's headers

#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <errno.h>
#include <poll.h>
#include <string.h>
#include <stdlib.h>
#include <sys/ioctl.h>
#include <sys/wait.h>
#include <termios.h>
#include <time.h>
#include <android/log.h>

#define LOG_TAG "SkerryPty"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern char** environ;

// Copies a Java String[] into a malloc'd, NULL-terminated char* array. Returns NULL on OOM.
static char** to_cstr_array(JNIEnv* env, jobjectArray arr, int* out_count) {
    jsize n = arr ? (*env)->GetArrayLength(env, arr) : 0;
    char** out = (char**) calloc((size_t) n + 1, sizeof(char*));
    if (!out) return NULL;
    for (jsize i = 0; i < n; i++) {
        jstring s = (jstring) (*env)->GetObjectArrayElement(env, arr, i);
        const char* cs = (*env)->GetStringUTFChars(env, s, NULL);
        out[i] = strdup(cs ? cs : "");
        if (cs) (*env)->ReleaseStringUTFChars(env, s, cs);
        else (*env)->ExceptionClear(env); // OOM getting the chars: clear it, treat as ""
        (*env)->DeleteLocalRef(env, s);
    }
    out[n] = NULL;
    if (out_count) *out_count = (int) n;
    return out;
}

static void free_cstr_array(char** a) {
    if (!a) return;
    for (char** p = a; *p; p++) free(*p);
    free(a);
}

static jlongArray fail_array(JNIEnv* env) {
    jlong fail = -1;
    jlongArray arr = (*env)->NewLongArray(env, 1);
    if (arr) (*env)->SetLongArrayRegion(env, arr, 0, 1, &fail);
    return arr;
}

// Is `key` (without '=') already present in the NULL-terminated env array?
static int env_has(char** e, const char* key) {
    size_t klen = strlen(key);
    for (int j = 0; e[j]; j++) if (strncmp(e[j], key, klen) == 0 && e[j][klen] == '=') return 1;
    return 0;
}

// Value of `key` in the env array, or NULL. Points into the borrowed entry string.
static const char* env_value(char** e, const char* key) {
    size_t klen = strlen(key);
    for (int j = 0; e[j]; j++) if (strncmp(e[j], key, klen) == 0 && e[j][klen] == '=') return e[j] + klen + 1;
    return NULL;
}

// Builds the child's environment in the parent: inherited environ + caller overrides (replacing a
// duplicate key) + TERM/PATH fallbacks. The returned array is malloc'd but its entries are BORROWED
// (environ strings, the caller's `overrides` strings, or literals) — free only the array.
static char** build_child_env(char** overrides, int overrideCount) {
    int baseCount = 0;
    while (environ[baseCount]) baseCount++;
    size_t cap = (size_t) baseCount + (size_t) overrideCount + 3; // + TERM + PATH + NULL
    char** e = (char**) calloc(cap, sizeof(char*));
    if (!e) return NULL;
    int n = 0;
    for (int i = 0; i < baseCount; i++) e[n++] = environ[i];
    for (int i = 0; i < overrideCount; i++) {
        char* kv = overrides[i];
        const char* eq = strchr(kv, '=');
        size_t klen = eq ? (size_t) (eq - kv) : strlen(kv);
        int replaced = 0;
        for (int j = 0; j < n; j++) {
            if (strncmp(e[j], kv, klen) == 0 && e[j][klen] == '=') { e[j] = kv; replaced = 1; break; }
        }
        if (!replaced) e[n++] = kv;
    }
    e[n] = NULL;
    if (!env_has(e, "TERM")) e[n++] = "TERM=xterm-256color";
    if (!env_has(e, "PATH")) e[n++] = "PATH=/system/bin:/system/xbin:/vendor/bin";
    e[n] = NULL;
    return e;
}

// Resolves argv0 to a concrete executable path (using the child env's PATH) so the child can
// execve() without the PATH-walking malloc that execvp/execvpe would do. Returns a malloc'd string.
static char* resolve_exec(const char* argv0, char** childEnv) {
    if (strchr(argv0, '/')) return strdup(argv0);
    const char* path = env_value(childEnv, "PATH");
    if (!path || !*path) path = "/system/bin:/system/xbin:/vendor/bin";
    size_t alen = strlen(argv0);
    const char* p = path;
    while (*p) {
        const char* colon = strchr(p, ':');
        size_t dlen = colon ? (size_t) (colon - p) : strlen(p);
        if (dlen > 0) {
            char* cand = (char*) malloc(dlen + 1 + alen + 1);
            if (cand) {
                memcpy(cand, p, dlen);
                cand[dlen] = '/';
                memcpy(cand + dlen + 1, argv0, alen);
                cand[dlen + 1 + alen] = '\0';
                if (access(cand, X_OK) == 0) return cand;
                free(cand);
            }
        }
        if (!colon) break;
        p = colon + 1;
    }
    return strdup(argv0); // not found: execve will fail and the child _exit(127)s (clean EOF)
}

// Starts a shell on a fresh PTY. Returns {masterFd, pid, wakeReadFd, wakeWriteFd} on success, or a
// single -1 on failure. command empty -> /system/bin/sh; env entries are "KEY=VALUE".
JNIEXPORT jlongArray JNICALL
Java_app_skerry_shared_local_LocalPty_start(JNIEnv* env, jobject thiz,
        jobjectArray command, jint cols, jint rows, jstring workingDir, jobjectArray envp) {
    (void) thiz;

    int cmdCount = 0;
    char** argv = to_cstr_array(env, command, &cmdCount);
    if (!argv) return fail_array(env);

    int envCount = 0;
    char** envArr = to_cstr_array(env, envp, &envCount);

    char* wd = NULL;
    if (workingDir) {
        const char* w = (*env)->GetStringUTFChars(env, workingDir, NULL);
        if (w) { wd = strdup(w); (*env)->ReleaseStringUTFChars(env, workingDir, w); }
        else (*env)->ExceptionClear(env);
    }

    // Everything the child needs is prepared here in the parent (see the fork()-safety note on top).
    static char* defShell[] = { (char*) "/system/bin/sh", NULL };
    char** useArgv = (cmdCount > 0) ? argv : defShell;
    char** childEnv = build_child_env(envArr, envCount);
    char* execPath = childEnv ? resolve_exec(useArgv[0], childEnv) : NULL;
    const char* homeFallback = childEnv ? env_value(childEnv, "HOME") : NULL;
    if (!childEnv || !execPath) {
        free_cstr_array(argv); free_cstr_array(envArr); free(wd); free(childEnv); free(execPath);
        return fail_array(env);
    }

    // Self-pipe to wake a blocked poll() in read() when close() is called from another thread.
    int wake[2];
    if (pipe2(wake, O_CLOEXEC | O_NONBLOCK) != 0) {
        LOGE("pipe2 failed: %s", strerror(errno));
        free_cstr_array(argv); free_cstr_array(envArr); free(wd); free(childEnv); free(execPath);
        return fail_array(env);
    }

    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);

    int master = -1;
    pid_t pid = forkpty(&master, NULL, NULL, &ws);
    if (pid < 0) {
        LOGE("forkpty failed: %s", strerror(errno));
        close(wake[0]); close(wake[1]);
        free_cstr_array(argv); free_cstr_array(envArr); free(wd); free(childEnv); free(execPath);
        return fail_array(env);
    }
    if (pid == 0) {
        // Child: async-signal-safe only. forkpty has already run login_tty (setsid + TIOCSCTTY + dup
        // slave to 0/1/2), so this is a new session leader with the PTY as its controlling terminal.
        if (wd && wd[0] && chdir(wd) != 0) {
            // Requested dir unreachable (e.g. scoped storage) — fall back to HOME, never the
            // inherited `/` (which an untrusted_app can't list at all).
            if (homeFallback && homeFallback[0]) { if (chdir(homeFallback) != 0) { /* keep cwd */ } }
        }
        execve(execPath, useArgv, childEnv);
        _exit(127); // exec failed: the master sees an immediate EOF, surfaced as a clean close
    }

    // Parent. The master fd briefly lacks close-on-exec (forkpty can't set it atomically); an
    // in-process fork+exec racing this window could inherit it, but the app spawns none.
    fcntl(master, F_SETFD, FD_CLOEXEC);
    free_cstr_array(argv);
    free_cstr_array(envArr);
    free(wd);
    free(childEnv); // entries are borrowed — free the array only
    free(execPath);

    jlong out[4] = { (jlong) master, (jlong) pid, (jlong) wake[0], (jlong) wake[1] };
    jlongArray res = (*env)->NewLongArray(env, 4);
    if (res) (*env)->SetLongArrayRegion(env, res, 0, 4, out);
    return res;
}

// Blocking read of the next chunk. Polls {master, wakeR}: a wake signal (close) or the child's exit
// returns -1 (EOF/shutdown); otherwise the bytes read.
JNIEXPORT jint JNICALL
Java_app_skerry_shared_local_LocalPty_read(JNIEnv* env, jobject thiz,
        jint master, jint wakeR, jbyteArray buffer, jint len) {
    (void) thiz;
    jsize cap = (*env)->GetArrayLength(env, buffer);
    if (len < 0 || len > cap) len = cap; // never read past the JNI buffer, whatever the caller passed
    if (len == 0) return -1;
    struct pollfd fds[2];
    for (;;) {
        fds[0].fd = master; fds[0].events = POLLIN; fds[0].revents = 0;
        fds[1].fd = wakeR;  fds[1].events = POLLIN; fds[1].revents = 0;
        int p = poll(fds, 2, -1);
        if (p < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (fds[1].revents != 0) return -1;        // close() signalled shutdown (or the pipe went away)
        short r = fds[0].revents;
        if (r & (POLLERR | POLLNVAL)) {
            // A slave-side close reports POLLERR here; try one read to drain any trailing bytes.
            if (!(r & (POLLIN | POLLHUP))) return -1;
        }
        if (r & (POLLIN | POLLHUP)) {
            jbyte* c = (*env)->GetByteArrayElements(env, buffer, NULL);
            if (!c) return -1;
            ssize_t n = read(master, c, (size_t) len);
            (*env)->ReleaseByteArrayElements(env, buffer, c, (n > 0) ? 0 : JNI_ABORT);
            if (n > 0) return (jint) n;
            if (n < 0 && errno == EINTR) continue;
            return -1;                              // 0 = EOF, EIO = slave closed after the child exited
        }
    }
}

// Writes the whole buffer, handling partial writes. Returns the count or -1 on error (dead shell).
JNIEXPORT jint JNICALL
Java_app_skerry_shared_local_LocalPty_write(JNIEnv* env, jobject thiz,
        jint master, jbyteArray buffer, jint len) {
    (void) thiz;
    jsize cap = (*env)->GetArrayLength(env, buffer);
    if (len < 0 || len > cap) len = cap;
    jbyte* c = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (!c) return -1;
    jint total = 0;
    while (total < len) {
        ssize_t w = write(master, c + total, (size_t) (len - total));
        if (w < 0) {
            if (errno == EINTR) continue;
            (*env)->ReleaseByteArrayElements(env, buffer, c, JNI_ABORT);
            return -1;
        }
        total += (jint) w;
    }
    (*env)->ReleaseByteArrayElements(env, buffer, c, JNI_ABORT);
    return total;
}

// Applies a new terminal window size (TIOCSWINSZ) so full-screen programs redraw.
JNIEXPORT void JNICALL
Java_app_skerry_shared_local_LocalPty_resize(JNIEnv* env, jobject thiz,
        jint master, jint cols, jint rows) {
    (void) env; (void) thiz;
    struct winsize ws;
    memset(&ws, 0, sizeof(ws));
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ioctl(master, TIOCSWINSZ, &ws);
}

// Tears the session down: wake a blocked read(), hang up the child's process group, reap it (with a
// forcible escalation if it ignores SIGHUP), then close the fds. Safe to call once per session.
JNIEXPORT void JNICALL
Java_app_skerry_shared_local_LocalPty_close(JNIEnv* env, jobject thiz,
        jint master, jint wakeR, jint wakeW, jint pid) {
    (void) env; (void) thiz;
    char b = 'x';
    ssize_t ignored = write(wakeW, &b, 1); (void) ignored;

    // The child is a session/process-group leader (setsid); the negative pid signals the whole
    // group, so anything it launched (vi, less) is hung up too.
    kill((pid_t) -pid, SIGHUP);
    int status;
    int reaped = 0;
    for (int i = 0; i < 100; i++) {
        pid_t r = waitpid((pid_t) pid, &status, WNOHANG);
        if (r == (pid_t) pid || (r < 0 && errno == ECHILD)) { reaped = 1; break; }
        struct timespec ts = { 0, 10 * 1000 * 1000 }; // 10ms
        nanosleep(&ts, NULL);
    }
    if (!reaped) {
        kill((pid_t) -pid, SIGKILL);
        waitpid((pid_t) pid, &status, 0);
    }
    close(master);
    close(wakeR);
    close(wakeW);
}
