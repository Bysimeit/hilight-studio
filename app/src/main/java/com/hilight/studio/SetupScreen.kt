package com.hilight.studio

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val ADB_RESET =
    "adb shell \"pkill -f 'com.hilight.(core.AdbHelper|studio:hilight)'\""

const val ADB_COMMAND = ADB_RESET + "\n" +
    "adb shell 'S=${'$'}(command -v setsid); " +
        "CLASSPATH=${'$'}(pm path com.hilight.studio | head -1 | cut -d: -f2) " +
        "nohup ${'$'}S app_process / com.hilight.core.AdbHelper " +
        "> /data/local/tmp/hilight.log 2>&1 < /dev/null &'"

const val ADB_COMMAND_CMD = ADB_RESET + "\n" +
    "adb shell \"S=${'$'}(command -v setsid); " +
        "CLASSPATH=${'$'}(pm path com.hilight.studio | head -1 | cut -d: -f2) " +
        "nohup ${'$'}S app_process / com.hilight.core.AdbHelper " +
        "> /data/local/tmp/hilight.log 2>&1 < /dev/null &\""

@Composable
fun SetupScreen(store: Store) {
    val ctx = LocalContext.current
    val status by store.status.collectAsStateWithLifecycle()
    val transport by store.transport.collectAsStateWithLifecycle()
    val active by store.activeTransport.collectAsStateWithLifecycle()
    val shizukuState by store.shizuku.state.collectAsStateWithLifecycle()
    val rootState by store.root.state.collectAsStateWithLifecycle()
    val priority by store.priority.collectAsStateWithLifecycle()
    val dynamicColor by store.dynamicColor.collectAsStateWithLifecycle()
    val timeoutMs by store.ambientTimeoutMs.collectAsStateWithLifecycle()
    val quietEnabled by store.quietEnabled.collectAsStateWithLifecycle()
    val quietStart by store.quietStart.collectAsStateWithLifecycle()
    val quietEnd by store.quietEnd.collectAsStateWithLifecycle()
    val batteryGuard by store.batteryGuard.collectAsStateWithLifecycle()
    val batteryMinPct by store.batteryMinPct.collectAsStateWithLifecycle()
    val saverGuard by store.saverGuard.collectAsStateWithLifecycle()
    val suppression by store.suppression.collectAsStateWithLifecycle()
    val respectDnd by store.respectDnd.collectAsStateWithLifecycle()
    val quietDim by store.quietDim.collectAsStateWithLifecycle()
    val quietDimPct by store.quietDimPct.collectAsStateWithLifecycle()
    val screenOffOnly by store.screenOffOnly.collectAsStateWithLifecycle()

    val rules by store.rules.collectAsStateWithLifecycle()
    var notifAccess by remember { mutableStateOf(NotificationTrigger.hasAccess(ctx)) }
    var usageAccess by remember { mutableStateOf(LiveTriggers.hasUsageAccess(ctx)) }
    val usageNeeded = rules.any { it.enabled && it.trigger == Trigger.FOREGROUND }
    val notifNeeded = rules.any { it.enabled && it.trigger == Trigger.NOTIFICATION }
    var inspecting by remember { mutableStateOf(false) }
    var forgetting by remember { mutableStateOf(false) }
    var checkingForUpdates by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateCheckResult?>(null) }
    val updateScope = rememberCoroutineScope()
    val conversations by store.conversations.collectAsStateWithLifecycle()

    val checkForUpdates: () -> Unit = {
        checkingForUpdates = true
        updateScope.launch {
            updateResult = withContext(Dispatchers.IO) {
                GitHubUpdateChecker.check(BuildConfig.VERSION_NAME)
            }
            checkingForUpdates = false
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            val notif = NotificationTrigger.hasAccess(ctx)
            if (notif && !notifAccess) NotificationTrigger.nudge(ctx)
            notifAccess = notif
            val access = LiveTriggers.hasUsageAccess(ctx)
            if (access && !usageAccess) store.syncWatcher()
            usageAccess = access
            store.shizuku.refresh()
            AdbAccess.refresh(ctx)
            delay(1500)
        }
    }

    PixelCard(tone = 2) {
        SectionTitle(
            stringResource(R.string.setup_auto_off_title),
            trailing = { Caption(formatDuration(timeoutMs)) },
        )
        Caption(stringResource(R.string.setup_auto_off_body))
        Caption(stringResource(R.string.setup_auto_off_protection))
        GatedDurationSlider(
            label = stringResource(R.string.setup_stay_on_for),
            valueMs = timeoutMs,
            minMs = 5_000,
            safeMaxMs = Limits.WARN_ABOVE_MS,
            extendedMaxMs = Limits.AMBIENT_MAX_MS,
            unlockLabel = stringResource(R.string.setup_allow_five_minutes),
            warnFirst = stringResource(R.string.setup_warn_long_title) to
                stringResource(R.string.setup_warn_long_body),
            warnSecond = stringResource(R.string.setup_warn_long_confirm_title) to
                stringResource(R.string.setup_warn_long_confirm_body),
            onChange = { store.setAmbientTimeoutMs(it) },
        )
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_dark_title),
            trailing = { suppression?.let { LivePill(stringResource(it.shortRes), ok = false) } },
        )
        ToggleRow(stringResource(R.string.setup_screen_off_only), screenOffOnly) {
            store.setScreenOffOnly(it)
        }
        // The toggle and the suppression pill above say the same two words about the same thing, so
        // they share the one string.
        ToggleRow(stringResource(R.string.suppression_quiet_hours), quietEnabled) {
            store.setQuietHours(it)
        }
        if (quietEnabled) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(
                    onClick = { pickTime(ctx, quietStart) { store.setQuietHours(true, startMin = it) } },
                    modifier = Modifier.weight(1f),
                ) { ButtonLabel(stringResource(R.string.setup_quiet_from, clock(quietStart))) }
                FilledTonalButton(
                    onClick = { pickTime(ctx, quietEnd) { store.setQuietHours(true, endMin = it) } },
                    modifier = Modifier.weight(1f),
                ) { ButtonLabel(stringResource(R.string.setup_quiet_until, clock(quietEnd))) }
            }
            ToggleRow(stringResource(R.string.setup_quiet_dim), quietDim) { store.setQuietDim(it) }
            if (quietDim) {
                PixelSlider(
                    stringResource(R.string.setup_dim_to),
                    quietDimPct.toFloat(),
                    2f..40f,
                    { store.setQuietDim(true, it.toInt()) },
                ) { stringResource(R.string.setup_percent, it.toInt()) }
            }
        }
        ToggleRow(stringResource(R.string.setup_respect_dnd), respectDnd) { store.setRespectDnd(it) }
        ToggleRow(stringResource(R.string.setup_pause_saver), saverGuard) { store.setSaverGuard(it) }
        ToggleRow(stringResource(R.string.setup_pause_low_battery), batteryGuard) {
            store.setBatteryGuard(it)
        }
        if (batteryGuard) {
            PixelSlider(
                stringResource(R.string.setup_pause_below),
                batteryMinPct.toFloat(),
                Limits.BATTERY_MIN_PCT.toFloat()..Limits.BATTERY_MAX_PCT.toFloat(),
                { store.setBatteryGuard(true, it.toInt()) },
            ) { stringResource(R.string.setup_percent, it.toInt()) }
            Caption(stringResource(R.string.setup_battery_note))
        }
    }

    val rootPresent = rootState in setOf(
        RootBackend.State.AVAILABLE,
        RootBackend.State.REQUESTING,
        RootBackend.State.STARTING,
        RootBackend.State.RUNNING,
    )
    if (rootPresent) {
        PixelCard(tone = 2) {
            SectionTitle(
                stringResource(R.string.setup_root_title),
                trailing = {
                    LivePill(
                        stringResource(
                            if (rootState == RootBackend.State.RUNNING)
                                R.string.setup_root_active else R.string.setup_root_available
                        ),
                        ok = true,
                    )
                },
            )
            Caption(
                stringResource(
                    when (rootState) {
                        RootBackend.State.AVAILABLE -> R.string.setup_root_available_body
                        RootBackend.State.REQUESTING -> R.string.setup_root_requesting_body
                        RootBackend.State.STARTING -> R.string.setup_root_starting_body
                        else -> R.string.setup_root_active_body
                    }
                )
            )
        }
    } else {
        PixelCard(tone = 2) {
            SectionTitle(stringResource(R.string.setup_privileged_title))
            Caption(stringResource(R.string.setup_privileged_body))
            val selectable = listOf(Transport.AUTO, Transport.SHIZUKU, Transport.ADB)
            val transportLabels = selectable.associateWith { stringResource(it.labelRes) }
            SegmentedSelector(
                options = selectable,
                selected = transport.takeIf { it in selectable } ?: Transport.AUTO,
                label = { transportLabels.getValue(it) },
                onSelect = { store.setTransport(it) },
            )
            if (transport == Transport.AUTO) Caption(stringResource(R.string.setup_transport_auto_note))
            if (rootState == RootBackend.State.DENIED || rootState == RootBackend.State.ERROR) {
                Caption(
                    store.root.errorText()
                        ?: stringResource(R.string.setup_root_error_body)
                )
                TextButton(onClick = store::retryRoot) {
                    ButtonLabel(stringResource(R.string.setup_root_retry))
                }
            }
        }

        AnimatedContent(
            targetState = transport,
            transitionSpec = { fadeIn(tween(180)).togetherWith(fadeOut(tween(120))) },
            label = "transportCards",
        ) { t ->
            Column {
                if (t != Transport.SHIZUKU) AdbCard(ctx, store)
                if (t != Transport.ADB) ShizukuCard(store, shizukuState)
            }
        }
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_notif_title),
            trailing = {
                LivePill(
                    stringResource(
                        when {
                            notifAccess -> R.string.setup_state_granted
                            notifNeeded -> R.string.setup_state_needed
                            else -> R.string.setup_state_optional
                        }
                    ),
                    notifAccess,
                )
            },
        )
        Caption(stringResource(R.string.setup_notif_body))
        FilledTonalButton(
            onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
        ) { ButtonLabel(stringResource(R.string.setup_open_notif_access)) }
        Caption(stringResource(R.string.setup_inspector_body))
        TextButton(onClick = { inspecting = true }) {
            ButtonLabel(stringResource(R.string.setup_inspector_button))
        }
        // The chat picker's convenience comes from a list of real contact names held on the device,
        // so there has to be a way to be rid of it without uninstalling. Rules keep their own copy of
        // the name they match on, so clearing this list leaves working rules working.
        Caption(
            if (conversations.isEmpty()) {
                stringResource(R.string.setup_chats_none)
            } else {
                stringResource(R.string.setup_chats_remembered, conversations.size)
            }
        )
        if (conversations.isNotEmpty()) {
            TextButton(onClick = { forgetting = true }) {
                ButtonLabel(stringResource(R.string.setup_forget_chats_button))
            }
        }
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_usage_title),
            trailing = {
                LivePill(
                    stringResource(
                        when {
                            usageAccess -> R.string.setup_state_granted
                            usageNeeded -> R.string.setup_state_needed
                            else -> R.string.setup_state_optional
                        }
                    ),
                    usageAccess,
                )
            },
        )
        Caption(stringResource(R.string.setup_usage_body))
        FilledTonalButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
            ButtonLabel(stringResource(R.string.setup_open_usage_access))
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.setup_appearance_title))
        ToggleRow(stringResource(R.string.setup_wallpaper_colours), dynamicColor) {
            store.setDynamicColor(it)
        }
    }

    PixelCard {
        SectionTitle(
            stringResource(R.string.setup_updates_title),
            trailing = {
                Caption(
                    stringResource(
                        R.string.setup_updates_installed,
                        BuildConfig.VERSION_NAME,
                    )
                )
            },
        )
        when {
            checkingForUpdates -> Caption(stringResource(R.string.setup_updates_checking))
            updateResult == null -> Caption(stringResource(R.string.setup_updates_body))
            updateResult is UpdateCheckResult.Available -> Caption(
                stringResource(
                    R.string.setup_updates_available,
                    (updateResult as UpdateCheckResult.Available).release.versionName,
                )
            )
            updateResult is UpdateCheckResult.Current ->
                Caption(stringResource(R.string.setup_updates_current))
            updateResult is UpdateCheckResult.NoPublishedRelease ->
                Caption(stringResource(R.string.setup_updates_none))
            else -> Caption(stringResource(R.string.setup_updates_failed))
        }

        val available = updateResult as? UpdateCheckResult.Available
        if (available != null && !checkingForUpdates) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { openRelease(ctx, available.release.pageUrl) }) {
                    ButtonLabel(stringResource(R.string.setup_updates_view_release))
                }
                TextButton(onClick = checkForUpdates) {
                    ButtonLabel(stringResource(R.string.setup_updates_check_again))
                }
            }
        } else {
            FilledTonalButton(
                onClick = checkForUpdates,
                enabled = !checkingForUpdates,
            ) {
                ButtonLabel(
                    stringResource(
                        if (checkingForUpdates) R.string.setup_updates_checking
                        else R.string.setup_updates_check,
                    )
                )
            }
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.setup_test_title))
        Caption(stringResource(R.string.setup_test_body))
        FilledTonalButton(onClick = { postSelfTestNotification(ctx) }) {
            ButtonLabel(stringResource(R.string.setup_test_button))
        }
    }

    PixelCard {
        SectionTitle(stringResource(R.string.setup_priority_title))
        Caption(stringResource(R.string.setup_priority_body))
        Caption(stringResource(R.string.setup_priority_calls))
        PixelSlider(
            stringResource(R.string.setup_priority_label),
            priority.toFloat(),
            -10f..10f,
            { store.setPriority(it.toInt()) },
        ) { it.toInt().toString() }
    }

    if (inspecting) {
        NotificationInspectorDialog(store) { inspecting = false }
    }

    if (forgetting) {
        AlertDialog(
            onDismissRequest = { forgetting = false },
            shape = MaterialTheme.shapes.extraLarge,
            title = { Text(stringResource(R.string.setup_forget_chats_title)) },
            text = {
                Text(
                    stringResource(R.string.setup_forget_chats_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.forgetConversations()
                        forgetting = false
                    },
                ) { ButtonLabel(stringResource(R.string.setup_forget_chats_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { forgetting = false }) {
                    ButtonLabel(stringResource(R.string.setup_forget_chats_dismiss))
                }
            },
        )
    }
}

@Composable
private fun ShizukuCard(store: Store, state: ShizukuBackend.State) {
    val ctx = LocalContext.current
    PixelCard {
        // The card is named after the transport it is about, so it uses that same name.
        SectionTitle(
            stringResource(R.string.transport_shizuku),
            trailing = {
                val pill = when (state) {
                    ShizukuBackend.State.CONNECTED -> R.string.shizuku_state_connected
                    ShizukuBackend.State.CONNECTING -> R.string.shizuku_state_connecting
                    ShizukuBackend.State.NEEDS_PERMISSION -> R.string.shizuku_state_needs_permission
                    ShizukuBackend.State.NOT_RUNNING -> R.string.shizuku_state_not_running
                    ShizukuBackend.State.NOT_INSTALLED -> R.string.shizuku_state_not_installed
                    ShizukuBackend.State.FAILED -> R.string.shizuku_state_failed
                }
                LivePill(stringResource(pill), state == ShizukuBackend.State.CONNECTED)
            },
        )

        Caption(stringResource(R.string.shizuku_reattach_note))

        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(100))) },
            label = "shizukuState",
        ) { s ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (s) {
                    ShizukuBackend.State.NOT_INSTALLED -> {
                        Caption(stringResource(R.string.shizuku_not_installed_body))
                        Button(onClick = { openShizukuListing(ctx) }) {
                            ButtonLabel(stringResource(R.string.shizuku_get))
                        }
                    }

                    ShizukuBackend.State.NOT_RUNNING -> {
                        Caption(stringResource(R.string.shizuku_not_running_body))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { openShizuku(ctx) }) {
                                ButtonLabel(stringResource(R.string.shizuku_open))
                            }
                            TextButton(onClick = { store.shizuku.refresh() }) {
                                ButtonLabel(stringResource(R.string.shizuku_check_again))
                            }
                        }
                    }

                    ShizukuBackend.State.NEEDS_PERMISSION -> {
                        Caption(stringResource(R.string.shizuku_needs_permission_body))
                        Button(onClick = { store.shizuku.requestPermission() }) {
                            ButtonLabel(stringResource(R.string.shizuku_request_access))
                        }
                    }

                    ShizukuBackend.State.CONNECTED -> {
                        Caption(stringResource(R.string.shizuku_connected_body))
                        TextButton(onClick = { store.shizuku.unbind() }) {
                            ButtonLabel(stringResource(R.string.shizuku_disconnect))
                        }
                    }

                    else -> {
                        // Two kinds of failure text: the ones HiLight diagnoses itself, which are
                        // translated, and whatever the framework handed back, which is not ours to
                        // translate and is shown as it came.
                        Caption(
                            store.shizuku.errorRes()?.let { stringResource(it) }
                                ?: store.shizuku.errorText()
                                ?: stringResource(R.string.shizuku_unreachable)
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { store.shizuku.refresh() }) {
                                ButtonLabel(stringResource(R.string.shizuku_retry))
                            }
                            TextButton(onClick = { openShizuku(ctx) }) {
                                ButtonLabel(stringResource(R.string.shizuku_open))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AdbCard(ctx: Context, store: Store) {
    val scope = rememberCoroutineScope()
    val state by AdbAccess.state.collectAsStateWithLifecycle()
    val detail by AdbAccess.detail.collectAsStateWithLifecycle()
    val phase by AdbPairingService.phase.collectAsStateWithLifecycle()
    val phaseDetail by AdbPairingService.detail.collectAsStateWithLifecycle()
    var manual by remember { mutableStateOf(false) }
    val localNetwork = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { AdbAccess.refresh(ctx) }

    PixelCard {
        SectionTitle(
            stringResource(R.string.builtin_title),
            trailing = {
                LivePill(stringResource(state.labelRes), state == AdbAccessState.READY)
            },
        )

        AnimatedContent(
            targetState = state,
            transitionSpec = { fadeIn(tween(160)).togetherWith(fadeOut(tween(100))) },
            label = "adbState",
        ) { s ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (s) {
                    AdbAccessState.LOCAL_NETWORK_OFF -> {
                        Caption(stringResource(R.string.builtin_local_network_body))
                        Button(onClick = { localNetwork.launch(AdbAccess.LOCAL_NETWORK_PERMISSION) }) {
                            ButtonLabel(stringResource(R.string.builtin_allow_local_network))
                        }
                    }

                    AdbAccessState.DEVELOPER_OFF -> {
                        Caption(stringResource(R.string.builtin_developer_body))
                        Button(onClick = { openAboutPhone(ctx) }) {
                            ButtonLabel(stringResource(R.string.builtin_open_about_phone))
                        }
                    }

                    AdbAccessState.WIRELESS_OFF -> {
                        Caption(stringResource(R.string.builtin_wireless_body))
                        Caption(stringResource(R.string.builtin_wireless_keep_on))
                        Button(onClick = { AdbAccess.openWirelessDebugging(ctx) }) {
                            ButtonLabel(stringResource(R.string.builtin_open_wireless))
                        }
                    }

                    AdbAccessState.NEEDS_PAIRING -> {
                        Caption(stringResource(R.string.builtin_pairing_body))
                        if (notificationsEnabled(ctx)) {
                            Button(
                                onClick = {
                                    AdbPairingService.start(ctx)
                                    AdbAccess.openWirelessDebugging(ctx)
                                },
                            ) { ButtonLabel(stringResource(R.string.builtin_pair)) }
                        } else {
                            Caption(stringResource(R.string.builtin_no_notifications_body))
                            Button(onClick = { openAppNotificationSettings(ctx) }) {
                                ButtonLabel(stringResource(R.string.builtin_turn_on_notifications))
                            }
                        }
                        PairingProgress(phase, phaseDetail)
                    }

                    AdbAccessState.WORKING -> {
                        Caption(stringResource(R.string.builtin_working_body))
                        PairingProgress(phase, phaseDetail)
                    }

                    AdbAccessState.READY -> {
                        Caption(stringResource(R.string.builtin_ready_body))
                        Caption(stringResource(R.string.builtin_ready_keep_on))
                        AutoStartSetting(ctx, store)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilledTonalButton(onClick = { scope.launch { AdbAccess.retry(ctx) } }) {
                                ButtonLabel(stringResource(R.string.builtin_restart_renderer))
                            }
                            TextButton(onClick = { AdbAccess.forget(ctx) }) {
                                ButtonLabel(stringResource(R.string.builtin_forget_pairing))
                            }
                        }
                    }

                    AdbAccessState.FAILED -> {
                        Caption(detail ?: stringResource(R.string.builtin_failed_body))
                        Caption(stringResource(R.string.builtin_failed_hint))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { scope.launch { AdbAccess.retry(ctx) } }) {
                                ButtonLabel(stringResource(R.string.builtin_try_again))
                            }
                            TextButton(onClick = { AdbAccess.openWirelessDebugging(ctx) }) {
                                ButtonLabel(stringResource(R.string.builtin_wireless_debugging))
                            }
                        }
                    }

                    else -> {
                        Caption(stringResource(R.string.builtin_unknown_body))
                        Button(onClick = { scope.launch { AdbAccess.retry(ctx) } }) {
                            ButtonLabel(stringResource(R.string.builtin_connect))
                        }
                    }
                }
            }
        }

        TextButton(onClick = { manual = !manual }) {
            ButtonLabel(
                stringResource(
                    if (manual) R.string.builtin_hide_manual else R.string.builtin_show_manual
                )
            )
        }
        if (manual) ManualCommands(ctx)
    }
}

@Composable
private fun AutoStartSetting(ctx: Context, store: Store) {
    val autoStart by store.autoStart.collectAsStateWithLifecycle()
    var canWrite by remember { mutableStateOf(AdbAccess.canWriteSecureSettings(ctx)) }
    var usbOn by remember { mutableStateOf(AdbAccess.usbDebuggingEnabled(ctx)) }

    LaunchedEffect(Unit) {
        while (true) {
            canWrite = AdbAccess.canWriteSecureSettings(ctx)
            usbOn = AdbAccess.usbDebuggingEnabled(ctx)
            delay(2000)
        }
    }

    PixelToggleRow(
        title = stringResource(R.string.builtin_autostart_title),
        subtitle = stringResource(R.string.builtin_autostart_body),
        checked = autoStart && canWrite && usbOn,
        onChange = { store.setAutoStart(it) },
    )
    if (!canWrite) {
        Caption(stringResource(R.string.builtin_autostart_needs_permission))
    } else if (!usbOn) {
        Caption(stringResource(R.string.builtin_autostart_needs_usb))
        Button(onClick = { AdbAccess.openWirelessDebugging(ctx) }) {
            ButtonLabel(stringResource(R.string.builtin_open_developer_options))
        }
    }
}

@Composable
private fun PairingProgress(phase: PairingPhase, detail: String?) {
    if (phase == PairingPhase.OFF) return
    val text = detail ?: stringResource(
        when (phase) {
            PairingPhase.SEARCHING -> R.string.builtin_pairing_searching
            PairingPhase.WAITING_FOR_CODE -> R.string.builtin_pairing_waiting_code
            PairingPhase.PAIRING -> R.string.builtin_pairing_pairing
            PairingPhase.DONE -> R.string.builtin_pairing_done
            PairingPhase.FAILED -> R.string.builtin_pairing_failed
            PairingPhase.OFF -> return
        }
    )
    Caption(text)
}

@Composable
private fun ManualCommands(ctx: Context) {
    Caption(stringResource(R.string.builtin_manual_body))
    Text(
        ADB_COMMAND,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                MaterialTheme.shapes.medium,
            )
            .padding(14.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { copy(ctx, ADB_COMMAND, R.string.adb_copied) }) {
            ButtonLabel(stringResource(R.string.adb_copy))
        }
        TextButton(onClick = { copy(ctx, ADB_COMMAND_CMD, R.string.adb_copied_cmd) }) {
            ButtonLabel(stringResource(R.string.adb_copy_cmd))
        }
    }
    TextButton(onClick = { share(ctx, ADB_COMMAND) }) {
        ButtonLabel(stringResource(R.string.adb_send))
    }
}

private fun openAboutPhone(ctx: Context) {
    val candidates = listOf(
        Intent(Settings.ACTION_DEVICE_INFO_SETTINGS),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in candidates) {
        if (runCatching { ctx.startActivity(intent) }.isSuccess) return
    }
}

private fun notificationsEnabled(ctx: Context): Boolean =
    ctx.getSystemService(android.app.NotificationManager::class.java)
        ?.areNotificationsEnabled() ?: false

private fun openAppNotificationSettings(ctx: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
    if (runCatching { ctx.startActivity(intent) }.isSuccess) return
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.fromParts("package", ctx.packageName, null))
        )
    }
}

// The confirmation is a resource rather than a string because these run from a click, outside
// composition. "hilight" is the clipboard's own label for the clip, not something a reader sees.
private fun copy(ctx: Context, text: String, @StringRes toast: Int) {
    ctx.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText("hilight", text))
    Toast.makeText(ctx, toast, Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(send, ctx.getString(R.string.adb_share_title)))
}

private fun openShizuku(ctx: Context) {
    val launch = ctx.packageManager.getLaunchIntentForPackage(ShizukuBackend.SHIZUKU_PKG)
    if (launch != null) ctx.startActivity(launch) else openShizukuListing(ctx)
}

private fun openShizukuListing(ctx: Context) {
    val uri = Uri.parse("https://shizuku.rikka.app/")
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(ctx, R.string.setup_no_browser, Toast.LENGTH_SHORT).show() }
}

private fun openRelease(ctx: Context, pageUrl: String) {
    val uri = Uri.parse(pageUrl)
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
        .onFailure { Toast.makeText(ctx, R.string.setup_no_browser, Toast.LENGTH_SHORT).show() }
}

private fun postSelfTestNotification(ctx: Context) {
    val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
    // The channel id stays a literal — it is a key, not a label. The channel *name* is a label: it
    // appears in the system's own notification settings for this app.
    nm.createNotificationChannel(
        android.app.NotificationChannel(
            "selftest",
            ctx.getString(R.string.setup_selftest_channel),
            android.app.NotificationManager.IMPORTANCE_DEFAULT,
        )
    )
    nm.notify(
        42,
        android.app.Notification.Builder(ctx, "selftest")
            .setContentTitle(ctx.getString(R.string.setup_selftest_title))
            .setContentText(ctx.getString(R.string.setup_selftest_body))
            .setSmallIcon(R.drawable.hilight_logo)
            .setAutoCancel(true)
            .build()
    )
}

fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun pickTime(ctx: Context, currentMinutes: Int, onPicked: (Int) -> Unit) {
    android.app.TimePickerDialog(
        ctx,
        { _, hour, minute -> onPicked(hour * 60 + minute) },
        currentMinutes / 60,
        currentMinutes % 60,
        android.text.format.DateFormat.is24HourFormat(ctx),
    ).show()
}

