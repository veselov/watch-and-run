import kotlin.time.TimeSource
import kotlinx.cinterop.*
import platform.posix.*
import kotlin.experimental.ExperimentalNativeApi

// Simple always-running app that maintains at most one background process and
// restarts it when a trigger file appears.
// Uses "sleep 10" as the background process for testing.


private data class BgProc(var pid: pid_t = 0)

@OptIn(ExperimentalForeignApi::class)
private fun startProcess(cmdLine: String): BgProc {
    // We'll fork and exec: child runs `/bin/sh -c "$cmdLine"`
    val pid = fork()
    if (pid < 0) {
        perror("fork")
        return BgProc(0)
    }
    if (pid == 0) {
        // Child process
        memScoped {
            val arg0 = "sh".cstr.getPointer(this)
            val arg1 = "-c".cstr.getPointer(this)
            val cmd = cmdLine.cstr.getPointer(this)
            val argv = cValuesOf(arg0, arg1, cmd, null)
            // Use execvp to search for 'sh' in PATH; more portable across environments
            execvp("sh", argv)
            // If execv returns, an error occurred
            perror("execv")
            _exit(127)
        }
    }
    // Parent: create a detached watcher pthread that blocks in waitpid and signals via a global flag
    startWatcherThread(pid)
    val proc = BgProc(pid)
    pthread_mutex_lock(bgMutex.ptr)
    sharedBg = proc
    pthread_mutex_unlock(bgMutex.ptr)
    return proc
}

@OptIn(ExperimentalForeignApi::class)
private fun stopProcess(proc: BgProc?) {
    if (proc == null) return
    val pid = proc.pid
    if (pid <= 0) return
    // Try to terminate gracefully first
    kill(pid, SIGTERM)
    var exited = false
    // Wait up to ~3 seconds for it to exit
    val mark = TimeSource.Monotonic.markNow()
    memScoped {
        val status = alloc<IntVar>()
        while (mark.elapsedNow().inWholeMilliseconds < 3000) {
            val w = waitpid(pid, status.ptr, WNOHANG)
            if (w == pid) { exited = true; break }
            // Sleep a little (100ms)
            usleep(100_000u)
        }
    }
    if (!exited) {
        // Force kill if still running
        kill(pid, SIGKILL)
        memScoped {
            val status = alloc<IntVar>()
            waitpid(pid, status.ptr, 0)
        }
    }
    // Ensure watcher thread exits and is joined before returning
    pthread_mutex_lock(bgMutex.ptr)
    val thr = watcherMap.remove(pid)
    pthread_mutex_unlock(bgMutex.ptr)
    if (thr != null) {
        pthread_join(thr, null)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fileExists(path: String): Boolean = access(path, F_OK) == 0

// Shared bg process and synchronization primitives
@OptIn(ExperimentalForeignApi::class)
private val bgMutex = nativeHeap.alloc<pthread_mutex_t>().apply { pthread_mutex_init(ptr, null) }
@OptIn(ExperimentalForeignApi::class)
private var sharedBg: BgProc? = null

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
@CName("bg_waiter")
private fun bgWaiter(arg: COpaquePointer?): COpaquePointer? {
    memScoped {
        val p = arg!!.reinterpret<pid_tVar>()
        val child = p.pointed.value
        val status = alloc<IntVar>()
        waitpid(child, status.ptr, 0)
        // On child exit, clear sharedBg if it matches
        pthread_mutex_lock(bgMutex.ptr)
        if (sharedBg != null && sharedBg!!.pid == child) {
            sharedBg = null
        }
        pthread_mutex_unlock(bgMutex.ptr)
        // Free the passed pid storage allocated on nativeHeap
        nativeHeap.free(p)
    }
    return null
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private val watcherMap = mutableMapOf<pid_t, pthread_t>()

@OptIn(ExperimentalForeignApi::class)
private fun joinAllWatchers() {
    pthread_mutex_lock(bgMutex.ptr)
    val threads = watcherMap.values.toList()
    watcherMap.clear()
    pthread_mutex_unlock(bgMutex.ptr)
    for (t in threads) {
        pthread_join(t, null)
    }
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
private fun startWatcherThread(pid: pid_t) {
    memScoped {
        val threadVar = alloc<pthread_tVar>()
        val arg = nativeHeap.alloc<pid_tVar>()
        arg.value = pid
        val argPtr: CPointer<pid_tVar> = arg.ptr
        val res = pthread_create(threadVar.ptr, null, staticCFunction(::bgWaiter), argPtr)
        if (res == 0) {
            // store joinable thread handle
            pthread_mutex_lock(bgMutex.ptr)
            watcherMap[pid] = threadVar.value
            pthread_mutex_unlock(bgMutex.ptr)
        } else {
            perror("pthread_create")
            // cleanup arg if thread not created
            nativeHeap.free(arg)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun deleteFile(path: String) {
    if (remove(path) != 0) {
        // ignore if it fails because file not exists
        if (errno != ENOENT) perror("remove")
    }
}

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {

    // Args: -f <file> -c <command> [-n <cycles>]
    var triggerFile = "./trigger"
    var cmdLine = "sleep 10"
    var cycles: Long = -1 // -1 means run forever
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "-f", "--file" -> {
                if (i + 1 >= args.size) {
                    fprintf(stderr, "Missing argument for -f/--file\n")
                    exit(2)
                }
                triggerFile = args[i + 1]
                i += 2
                continue
            }
            "-c", "--cmd", "--command" -> {
                if (i + 1 >= args.size) {
                    fprintf(stderr, "Missing argument for -c/--cmd\n")
                    exit(2)
                }
                cmdLine = args[i + 1]
                i += 2
                continue
            }
            "-n", "--cycles", "--test-cycles" -> {
                if (i + 1 >= args.size) {
                    fprintf(stderr, "Missing argument for -n/--cycles\n")
                    exit(2)
                }
                val v = args[i + 1]
                var parsed: Long = -1
                try {
                    parsed = v.toLong()
                } catch (_: Throwable) {
                    // fallthrough to error below
                }
                if (parsed < 0) {
                    fprintf(stderr, "Invalid cycles value: %s\n", v)
                    exit(2)
                }
                cycles = parsed
                i += 2
                continue
            }
            "-h", "--help" -> {
                fprintf(stderr, "Usage: [-f <file>] [-c <command>] [-n <cycles>]\n")
                return
            }
            else -> {
                fprintf(stderr, "Unknown option: %s\n", args[i])
                fprintf(stderr, "Usage: [-f <file>] [-c <command>] [-n <cycles>]\n")
                exit(2)
            }
        }
    }

    var tick: Long = 0
    while (true) {
        if (fileExists(triggerFile)) {
            deleteFile(triggerFile)
            pthread_mutex_lock(bgMutex.ptr)
            // stop and restart under mutex
            val toStop = sharedBg
            sharedBg = null
            pthread_mutex_unlock(bgMutex.ptr)
            stopProcess(toStop)
            val newBg = startProcess(cmdLine)
            pthread_mutex_lock(bgMutex.ptr)
            sharedBg = newBg
            pthread_mutex_unlock(bgMutex.ptr)
        }
        usleep(500_000u)
        tick++
        if (cycles >= 0 && tick >= cycles) {
            // Cleanup: stop any running process and join all watcher threads
            pthread_mutex_lock(bgMutex.ptr)
            val toStop = sharedBg
            sharedBg = null
            pthread_mutex_unlock(bgMutex.ptr)
            stopProcess(toStop)
            joinAllWatchers()
            return
        }
    }
}
