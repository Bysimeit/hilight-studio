package com.hilight.studio

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

/** Direct root transport. The existing file bridge and AdbHelper remain the renderer. */
class RootBackend(private val ctx: Context) : Backend {

    enum class State { CHECKING, UNAVAILABLE, AVAILABLE, REQUESTING, STARTING, RUNNING, DENIED, ERROR }

    override val transport = Transport.ROOT
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(State.CHECKING)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var ownedPid = -1
    @Volatile private var starting = false
    @Volatile private var lastError: String? = null

    var onStateChanged: (() -> Unit)? = null

    init {
        refreshPresence()
    }

    override fun push(json: String) = Bridge.writeState(ctx, json)

    override fun status(): HelperStatus {
        val status = Bridge.readStatus(ctx)
        return if (status.alive && status.owner == "root" && status.uid == 0) status
        else HelperStatus(alive = false, owner = "root")
    }

    fun errorText(): String? = lastError

    fun refreshPresence() {
        if (starting || _state.value == State.RUNNING) return
        Thread({
            val available = runPlain("command -v su").code == 0
            update(if (available) State.AVAILABLE else State.UNAVAILABLE)
        }, "hilight-root-check").apply { isDaemon = true }.start()
    }

    /** Starts only after Store has staged an output-disabled state with [stagedRevision]. */
    fun ensureStarted(stagedRevision: Long, onComplete: (Boolean) -> Unit) {
        if (starting) return
        starting = true
        Thread({
            var ok = false
            try {
                update(State.REQUESTING)
                val identity = runSu("id", 60)
                if (identity.code != 0 || !identity.output.contains("uid=0")) {
                    lastError = "Root permission was not granted"
                    update(State.DENIED)
                    return@Thread
                }

                update(State.STARTING)
                // This command can kill its own shell because pkill matches the command line. It is
                // intentionally separate, and its exit code is therefore not used as success proof.
                runSu(RootCommand.reset(), 5)
                val launch = runSu(RootCommand.start(Bridge.DEVICE_DIR), 10)
                val pid = launch.output.lineSequence()
                    .map { it.trim() }
                    .lastOrNull { it.toIntOrNull()?.let { n -> n > 0 } == true }
                    ?.toIntOrNull()
                    ?: throw IllegalStateException("root helper returned no pid")
                ownedPid = pid

                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline) {
                    val status = Bridge.readStatus(ctx)
                    if (status.alive && status.owner == "root" && status.uid == 0 &&
                        status.pid == pid && status.appliedStateRevision == stagedRevision &&
                        !status.privacyObserverEnabled
                    ) {
                        ok = true
                        lastError = null
                        update(State.RUNNING)
                        return@Thread
                    }
                    Thread.sleep(100)
                }
                throw IllegalStateException("root helper did not become ready")
            } catch (t: Throwable) {
                lastError = t.message ?: t.javaClass.simpleName
                cleanupOwned()
                update(State.ERROR)
            } finally {
                starting = false
                val result = ok || _state.value == State.RUNNING
                main.post { onComplete(result) }
            }
        }, "hilight-root-start").apply { isDaemon = true }.start()
    }

    fun stop() {
        val pid = ownedPid.takeIf { it > 0 } ?: status().pid.takeIf { it > 0 } ?: return
        Thread({
            runCatching { runSu(RootCommand.stop(pid), 5) }
            ownedPid = -1
            update(State.AVAILABLE)
        }, "hilight-root-stop").apply { isDaemon = true }.start()
    }

    private fun cleanupOwned() {
        val pid = ownedPid
        if (pid > 0) runCatching { runSu(RootCommand.stop(pid), 5) }
        ownedPid = -1
    }

    private fun update(state: State) {
        _state.value = state
        main.post { onStateChanged?.invoke() }
    }

    private data class Result(val code: Int, val output: String)

    private fun runPlain(command: String, timeoutSeconds: Long = 3): Result =
        runProcess(listOf("sh", "-c", command), timeoutSeconds)

    private fun runSu(command: String, timeoutSeconds: Long): Result =
        runProcess(listOf("su", "-c", command), timeoutSeconds)

    private fun runProcess(args: List<String>, timeoutSeconds: Long): Result {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly()
            throw IllegalStateException("command timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText().take(4_096) }
        return Result(process.exitValue(), output)
    }
}
