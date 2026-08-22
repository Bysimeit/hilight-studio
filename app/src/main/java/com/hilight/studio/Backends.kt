package com.hilight.studio

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.annotation.StringRes
import com.hilight.core.IHiLightService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** How the privileged renderer is reached. */
enum class Transport(@StringRes val labelRes: Int) {
    /** Prefer Shizuku, fall back to an adb-started helper. */
    AUTO(R.string.transport_auto),
    SHIZUKU(R.string.transport_shizuku),
    ADB(R.string.transport_adb),
}

interface Backend {
    val transport: Transport
    fun push(json: String)
    fun status(): HelperStatus
}

class AdbBackend(private val ctx: Context) : Backend {
    override val transport = Transport.ADB

    override fun push(json: String) = Bridge.writeState(ctx, json)

    override fun status(): HelperStatus = Bridge.readStatus(ctx)
}

class ShizukuBackend(private val ctx: Context) : Backend {
    enum class State { NOT_INSTALLED, NOT_RUNNING, NEEDS_PERMISSION, CONNECTING, CONNECTED, FAILED }

    override val transport = Transport.SHIZUKU

    private val _state = MutableStateFlow(State.NOT_RUNNING)
    val state: StateFlow<State> = _state.asStateFlow()

    private var service: IHiLightService? = null
    /** Raw text from a failure. Comes from the framework, so it is not translated. */
    private var lastError: String? = null

    /** Our own explanation of a failure, when we have one to give. */
    @StringRes
    private var lastErrorRes: Int? = null

    /** Latest state document, replayed when the service (re)connects. */
    private var pending: String? = null

    var onAvailabilityChanged: (() -> Unit)? = null

    private val args = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, HiLightUserService::class.java.name)
    )
        .daemon(true)
        .processNameSuffix("hilight")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null || !binder.pingBinder()) {
                _state.value = State.FAILED
                lastErrorRes = R.string.shizuku_error_dead_binder
                return
            }
            service = IHiLightService.Stub.asInterface(binder)
            _state.value = State.CONNECTED
            Log.i(TAG, "user service connected, ${runCatching { service?.ledCount() }.getOrNull()} LEDs")
            pending?.let { push(it) }
            onAvailabilityChanged?.invoke()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            if (_state.value == State.CONNECTED) _state.value = State.NOT_RUNNING
            onAvailabilityChanged?.invoke()
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { _, grantResult ->
            if (grantResult == PackageManager.PERMISSION_GRANTED) bind() else {
                _state.value = State.NEEDS_PERMISSION
            }
        }

    init {
        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky { refresh() }
        Shizuku.addBinderDeadListener {
            service = null
            _state.value = State.NOT_RUNNING
            onAvailabilityChanged?.invoke()
        }
        refresh()
    }

    fun isInstalled(): Boolean = runCatching {
        ctx.packageManager.getPackageInfo(SHIZUKU_PKG, 0)
        true
    }.getOrDefault(false)

    fun refresh() {
        if (_state.value == State.CONNECTED && service?.asBinder()?.pingBinder() == true) return
        if (!Shizuku.pingBinder()) {
            if (!isInstalled()) {
                _state.value = State.NOT_INSTALLED
                return
            }

            _state.value = State.NOT_RUNNING
            return
        }
        if (Shizuku.isPreV11()) {
            _state.value = State.FAILED
            lastErrorRes = R.string.shizuku_error_too_old
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            _state.value = State.NEEDS_PERMISSION
            return
        }
        bind()
    }

    fun requestPermission() {
        if (!Shizuku.pingBinder()) {
            refresh()
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) bind()
        else Shizuku.requestPermission(PERMISSION_REQUEST)
    }

    private fun bind() {
        if (_state.value == State.CONNECTING || _state.value == State.CONNECTED) return
        _state.value = State.CONNECTING
        runCatching { Shizuku.bindUserService(args, connection) }
            .onFailure {
                _state.value = State.FAILED
                lastError = it.message
                lastErrorRes = null
                Log.w(TAG, "bindUserService failed", it)
            }
    }

    fun unbind() {
        runCatching { Shizuku.unbindUserService(args, connection, true) }
        service = null
        _state.value = State.NOT_RUNNING
    }

    /** Our own explanation, as a resource id, or null when the failure came with its own text. */
    fun errorRes(): Int? = lastErrorRes

    /** Untranslated text straight from the failure, when there is any. */
    fun errorText(): String? = lastError

    override fun push(json: String) {
        pending = json
        val s = service ?: return
        runCatching { s.setState(json) }.onFailure {
            Log.w(TAG, "setState failed", it)
            service = null
            _state.value = State.NOT_RUNNING
            onAvailabilityChanged?.invoke()
        }
    }

    override fun status(): HelperStatus {
        val s = service ?: return HelperStatus(alive = false)
        val raw = runCatching { s.status() }.getOrNull() ?: return HelperStatus(alive = false)
        return runCatching {
            val o = JSONObject(raw)
            HelperStatus(
                alive = true,
                ageMs = 0,
                pid = o.optInt("pid", -1),
                ledCount = o.optInt("ledCount", 0),
                sessionOpen = o.optBoolean("session", false),
                mode = o.optString("mode", "-"),
                ambientRemainingMs = o.optLong("ambientRemainingMs", 0),
                ambientHeld = o.optBoolean("ambientHeld", false),
                resting = o.optBoolean("resting", false),
                dutyPct = o.optInt("dutyPct", 0),
            )
        }.getOrDefault(HelperStatus(alive = false))
    }

    companion object {
        const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
        const val PERMISSION_REQUEST = 4242
        private const val TAG = "HiLightShizuku"
    }
}
