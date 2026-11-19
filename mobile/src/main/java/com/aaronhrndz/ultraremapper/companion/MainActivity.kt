package com.aaronhrndz.ultraremapper.companion

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import kotlinx.coroutines.withTimeoutOrNull
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

private val UltraOrange = Color(0xFFFF5500)
private val GreenOk = Color(0xFF4CAF50)
private val RedAlert = Color(0xFFF44336)

data class AppPalette(
    val isDark: Boolean,
    val bg: Color,
    val cardBg: Color,
    val rowBg: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val navBarBg: Color,
    val redBannerBg: Color,
    val mdnsBannerBg: Color
)

private val DarkPalette = AppPalette(
    isDark = true,
    bg = Color(0xFF0E1014),
    cardBg = Color(0xFF181B22),
    rowBg = Color(0xFF232833),
    border = Color(0xFF2B313E),
    textPrimary = Color(0xFFF4F6FA),
    textSecondary = Color(0xFF9EA7B8),
    navBarBg = Color(0xFF14171E),
    redBannerBg = Color(0xFF8E1B1B),
    mdnsBannerBg = Color(0xFF132A1C)
)

private val LightPalette = AppPalette(
    isDark = false,
    bg = Color(0xFFF4F6F9),
    cardBg = Color(0xFFFFFFFF),
    rowBg = Color(0xFFEEF1F6),
    border = Color(0xFFDCE1EB),
    textPrimary = Color(0xFF12151C),
    textSecondary = Color(0xFF5D6677),
    navBarBg = Color(0xFFFFFFFF),
    redBannerBg = Color(0xFFD32F2F),
    mdnsBannerBg = Color(0xFFE8F5E9)
)

val LocalPalette = compositionLocalOf { DarkPalette }

private const val SAMSUNG_PAY_PKG = "com.samsung.android.samsungpay.gear"
private const val WATCH_APP_PKG = "com.aaronhrndz.ultraremapper"

enum class WatchPermission(
    val key: String,
    val titleRes: Int,
    val descRes: Int,
    val grantCmd: String,
    val revokeCmd: String,
    val checkCmd: String,
    val expectedGrantedOutput: String
) {
    OVERLAY(
        key = "OVERLAY",
        titleRes = R.string.perm_overlay_title,
        descRes = R.string.perm_overlay_desc,
        grantCmd = "appops set com.aaronhrndz.ultraremapper SYSTEM_ALERT_WINDOW allow",
        revokeCmd = "appops set com.aaronhrndz.ultraremapper SYSTEM_ALERT_WINDOW ignore",
        checkCmd = "appops get com.aaronhrndz.ultraremapper SYSTEM_ALERT_WINDOW",
        expectedGrantedOutput = "allow"
    ),
    WRITE_SECURE(
        key = "WRITE_SECURE",
        titleRes = R.string.perm_secure_title,
        descRes = R.string.perm_secure_desc,
        grantCmd = "pm grant com.aaronhrndz.ultraremapper android.permission.WRITE_SECURE_SETTINGS",
        revokeCmd = "pm revoke com.aaronhrndz.ultraremapper android.permission.WRITE_SECURE_SETTINGS",
        checkCmd = "dumpsys package com.aaronhrndz.ultraremapper | grep WRITE_SECURE_SETTINGS",
        expectedGrantedOutput = "granted=true"
    ),
    ACTION_ROUTING(
        key = "ACTION_ROUTING",
        titleRes = R.string.perm_action_title,
        descRes = R.string.perm_action_desc,
        grantCmd = "settings put global setting_action_button_press com.aaronhrndz.ultraremapper/com.aaronhrndz.ultraremapper.ActionButtonService",
        revokeCmd = "settings put global setting_action_button_press com.samsung.android.wear.shealth/com.samsung.android.wear.shealth.service.ActionButtonService",
        checkCmd = "settings get global setting_action_button_press",
        expectedGrantedOutput = "com.aaronhrndz.ultraremapper"
    ),
    MEDIA_BRIDGE(
        key = "MEDIA_BRIDGE",
        titleRes = R.string.perm_media_title,
        descRes = R.string.perm_media_desc,
        grantCmd = "cmd notification allow_listener com.aaronhrndz.ultraremapper/com.aaronhrndz.ultraremapper.MediaControlService",
        revokeCmd = "cmd notification disallow_listener com.aaronhrndz.ultraremapper/com.aaronhrndz.ultraremapper.MediaControlService",
        checkCmd = "settings get secure enabled_notification_listeners",
        expectedGrantedOutput = "com.aaronhrndz.ultraremapper"
    ),
    BATTERY_WHITELIST(
        key = "BATTERY_WHITELIST",
        titleRes = R.string.perm_battery_title,
        descRes = R.string.perm_battery_desc,
        grantCmd = "cmd deviceidle whitelist +com.aaronhrndz.ultraremapper",
        revokeCmd = "cmd deviceidle whitelist -com.aaronhrndz.ultraremapper",
        checkCmd = "cmd deviceidle whitelist",
        expectedGrantedOutput = "com.aaronhrndz.ultraremapper"
    )
}

enum class CompanionTab {
    BUTTONS,
    SETTINGS
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("companion_prefs", Context.MODE_PRIVATE)
        setContent {
            var themeMode by remember {
                mutableIntStateOf(prefs.getInt("theme_mode", 1)) // 0 = Light, 1 = Auto, 2 = Dark
            }
            val systemDark = isSystemInDarkTheme()
            val isDark = when (themeMode) {
                0 -> false
                2 -> true
                else -> systemDark
            }
            val palette = if (isDark) DarkPalette else LightPalette
            val colorScheme = if (isDark) {
                darkColorScheme(
                    background = palette.bg,
                    surface = palette.cardBg,
                    primary = UltraOrange,
                    onBackground = palette.textPrimary,
                    onSurface = palette.textPrimary
                )
            } else {
                lightColorScheme(
                    background = palette.bg,
                    surface = palette.cardBg,
                    primary = UltraOrange,
                    onBackground = palette.textPrimary,
                    onSurface = palette.textPrimary
                )
            }

            CompositionLocalProvider(LocalPalette provides palette) {
                MaterialTheme(colorScheme = colorScheme) {
                    CompanionAppRoot(
                        themeMode = themeMode,
                        onThemeModeChange = { nextMode ->
                            themeMode = nextMode
                            prefs.edit().putInt("theme_mode", nextMode).apply()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompanionAppRoot(
    themeMode: Int = 1,
    onThemeModeChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val palette = LocalPalette.current
    val scope = rememberCoroutineScope()
    val buttonsListState = rememberLazyListState()
    val settingsListState = rememberLazyListState()

    val adbHelper = remember { AdbHelper.getInstance(context) }
    val adbClient = remember { AdbClient(context) }

    // Navigation state
    var activeTab by remember { mutableStateOf(CompanionTab.BUTTONS) }
    var showDevOptionsScreen by remember { mutableStateOf(false) }

    // Bluetooth / DataLayer Sync State
    var isBtSynced by remember { mutableStateOf(false) }
    var btWatchName by remember { mutableStateOf<String?>(null) }
    var installedWatchVersion by remember { mutableStateOf<String?>(null) }
    var isMasterEnabled by remember { mutableStateOf(true) }
    var isHapticEnabled by remember { mutableStateOf(true) }
    val gestureMappings = remember { mutableStateMapOf<String, String>() }
    val permissionStates = remember { mutableStateMapOf<WatchPermission, Boolean?>() }
    var a11yEnabled by remember { mutableStateOf(true) }
    var isAssistantConfigured by remember { mutableStateOf(true) }
    var isTocTocConfigured by remember { mutableStateOf(true) }
    var haInstalledOnWatch by remember { mutableStateOf(false) }
    var isSamsungPayEnabled by remember { mutableStateOf<Boolean?>(null) }
    var watchInstalledApps by remember { mutableStateOf<List<WatchAppEntry>>(emptyList()) }

    // ADB Connection State
    var isAdbConnected by remember { mutableStateOf(adbHelper.isConnected) }
    var isAdbPaired by remember { mutableStateOf(adbHelper.isPaired()) }
    var adbWizardStep by remember { mutableIntStateOf(if (adbHelper.isPaired()) 3 else 1) }
    var lastAppliedPin by remember { mutableStateOf("") }
    var watchIp by remember {
        mutableStateOf(
            adbHelper.getLastHost().takeIf { !it.startsWith("192.168.167.") } ?: ""
        )
    }
    var pairPort by remember { mutableStateOf("") }
    var watchPort by remember { mutableStateOf(adbHelper.getLastPort()?.toString() ?: "") }
    var pairingCode by remember { mutableStateOf("") }
    var mdnsPairDetected by remember { mutableStateOf(false) }
    var mdnsConnectDetected by remember { mutableStateOf(false) }
    var isMdnsScanning by remember { mutableStateOf(false) }

    var isAdbConnecting by remember { mutableStateOf(false) }
    var isPairing by remember { mutableStateOf(false) }
    var adbStatusMsg by remember { mutableStateOf<String?>(null) }
    var isAdbStatusOk by remember { mutableStateOf(false) }

    // Collapsible Sections (ALL collapsed by default)
    var isTopExpanded by remember { mutableStateOf(false) }
    var isActionExpanded by remember { mutableStateOf(false) }
    var isBottomExpanded by remember { mutableStateOf(false) }
    var isGesturesExpanded by remember { mutableStateOf(false) }
    var isAdbExpanded by remember { mutableStateOf(false) }
    var isPermsExpanded by remember { mutableStateOf(false) }

    // Loading spinners per item
    var activeLoadingItem by remember { mutableStateOf<String?>(null) }
    var samsungPayError by remember { mutableStateOf<String?>(null) }

    // OTA Updates
    var isBetaEnabled by remember { mutableStateOf(UpdateChecker.isBetaChannel(context)) }
    var versionTapCount by remember { mutableIntStateOf(0) }
    var companionUpdate by remember { mutableStateOf<ReleaseUpdate?>(null) }
    var latestWatchRelease by remember { mutableStateOf<ReleaseUpdate?>(null) }
    var companionUpdateProgress by remember { mutableFloatStateOf(0f) }
    var companionUpdateStatus by remember { mutableStateOf("") }

    var isInstallingWatchApk by remember { mutableStateOf(false) }
    var watchApkProgress by remember { mutableFloatStateOf(0f) }
    var watchApkStatusMsg by remember { mutableStateOf("") }
    var watchApkSuccess by remember { mutableStateOf(false) }

    // Action Picker Bottom Sheet state
    var pickingGesture by remember { mutableStateOf<String?>(null) }
    var pickingAppSublist by remember { mutableStateOf(false) }

    val currentVersion = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "2.3.6"
        } catch (_: Exception) {
            "2.3.6"
        }
    }

    // Apply incoming Bluetooth / DataLayer state
    val dataLayerBridge = remember {
        WatchDataLayerBridge(
            context = context,
            onNodeConnected = { nodeName ->
                isBtSynced = true
                if (nodeName.isNotBlank()) btWatchName = nodeName
            },
            onStateReceived = { state, nodeName ->
                isBtSynced = true
                if (!nodeName.isNullOrBlank()) btWatchName = nodeName
                if (state.versionName.isNotBlank()) installedWatchVersion = state.versionName
                isMasterEnabled = state.masterEnabled
                isHapticEnabled = state.hapticEnabled
                for ((k, v) in state.mappings) {
                    gestureMappings[k] = v
                }
                for (perm in WatchPermission.values()) {
                    state.permissions[perm.key]?.let { permissionStates[perm] = it }
                }
                state.permissions["ACCESSIBILITY"]?.let { a11yEnabled = it }
                state.permissions["IS_ASSISTANT"]?.let { isAssistantConfigured = it }
                state.permissions["IS_TOCTOC"]?.let { isTocTocConfigured = it }
                state.permissions["HA_INSTALLED"]?.let { haInstalledOnWatch = it }
                state.permissions["SAMSUNG_PAY_ENABLED"]?.let { isSamsungPayEnabled = it }
                if (state.installedApps.isNotEmpty()) {
                    watchInstalledApps = state.installedApps
                }
                if (!state.capturedHostIp.isNullOrBlank() && !state.capturedHostIp.startsWith("192.168.167.")) {
                    watchIp = state.capturedHostIp
                }
                if (state.capturedPairingPort != null && state.capturedPairingPort > 0) {
                    pairPort = state.capturedPairingPort.toString()
                    mdnsPairDetected = true
                    if (isMdnsScanning && adbWizardStep == 2) {
                        isMdnsScanning = false
                    }
                }
                if (state.capturedConnectPort != null && state.capturedConnectPort > 0 && !isAdbConnected && !isAdbConnecting) {
                    if (watchPort.isBlank() || watchPort != state.capturedConnectPort.toString()) {
                        watchPort = state.capturedConnectPort.toString()
                        mdnsConnectDetected = true
                    }
                    if (isMdnsScanning && adbWizardStep == 3) {
                        isMdnsScanning = false
                    }
                }
                if (!state.capturedPin.isNullOrBlank() && state.capturedPin != lastAppliedPin) {
                    lastAppliedPin = state.capturedPin
                    pairingCode = state.capturedPin
                    if (!isAdbPaired || adbWizardStep == 2) {
                        adbWizardStep = 2
                    }
                }
            }
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    var isAppInForeground by remember { mutableStateOf(true) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    isAppInForeground = true
                    dataLayerBridge.start(includeApps = watchInstalledApps.isEmpty())
                }
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    isAppInForeground = false
                    dataLayerBridge.stop()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            dataLayerBridge.stop()
        }
    }

    // Foreground-only Bluetooth polling while ADB Connection Wizard is actively expanded & disconnected
    LaunchedEffect(isAppInForeground, activeTab, isAdbExpanded, isAdbConnected) {
        if (isAppInForeground && activeTab == CompanionTab.SETTINGS && isAdbExpanded && !isAdbConnected) {
            while (true) {
                delay(4000L)
                dataLayerBridge.requestSyncFromWatch(includeApps = false)
            }
        }
    }

    // Check GitHub Releases
    LaunchedEffect(isBetaEnabled) {
        UpdateChecker.checkAll(context) { compUpd, latestRel ->
            companionUpdate = compUpd
            latestWatchRelease = latestRel
        }
    }

    /**
     * Reads complete state from Watch over ADB.
     */
    suspend fun fetchFullWatchStateViaAdb(host: String, port: Int, forceReconnect: Boolean = false): Boolean {
        val ok = adbHelper.ensureConnected(host, port, forceReconnect)
        isAdbConnected = ok
        isAdbPaired = adbHelper.isPaired()
        if (!ok) {
            return false
        }
        isAdbPaired = true
        adbWizardStep = 3

        // Single consolidated shell query (10x faster, avoids timeout & multi-stream race)
        val combinedCmd = buildString {
            append("echo '===VER==='; dumpsys package $WATCH_APP_PKG | grep versionName; ")
            append("echo '===OVL==='; appops get $WATCH_APP_PKG SYSTEM_ALERT_WINDOW; ")
            append("echo '===SEC==='; dumpsys package $WATCH_APP_PKG | grep WRITE_SECURE_SETTINGS; ")
            append("echo '===ACT==='; settings get global setting_action_button_press; ")
            append("echo '===MED==='; settings get secure enabled_notification_listeners; ")
            append("echo '===BAT==='; cmd deviceidle whitelist | grep $WATCH_APP_PKG; ")
            append("echo '===A11Y==='; settings get secure enabled_accessibility_services; ")
            append("echo '===AST==='; settings get secure voice_interaction_service; settings get secure assistant; ")
            append("echo '===TOC==='; settings get global setting_gesture_launch_app; ")
            append("echo '===SPAY==='; pm list packages -d $SAMSUNG_PAY_PKG; dumpsys package $SAMSUNG_PAY_PKG | grep -E 'enabled=[234]|enabledSetting=[234]'; ")
            append("am broadcast -a com.aaronhrndz.ultraremapper.ACTION_STATE_UPDATED -p $WATCH_APP_PKG >/dev/null 2>&1")
        }

        val fullOut = adbHelper.executeCommand(host, port, combinedCmd, timeoutMs = 8000L)
        if (fullOut.startsWith("ERROR:")) {
            isAdbConnected = false
            return false
        }

        fun section(tag: String): String {
            val start = fullOut.indexOf("===$tag===")
            if (start == -1) return ""
            val contentStart = start + tag.length + 6
            val next = fullOut.indexOf("===", contentStart)
            return if (next == -1) fullOut.substring(contentStart).trim() else fullOut.substring(contentStart, next).trim()
        }

        val verOut = section("VER")
        val verMatch = Regex("versionName=([^\\s]+)").find(verOut)
        installedWatchVersion = verMatch?.groupValues?.getOrNull(1)

        permissionStates[WatchPermission.OVERLAY] = section("OVL").contains(WatchPermission.OVERLAY.expectedGrantedOutput)
        permissionStates[WatchPermission.WRITE_SECURE] = section("SEC").contains(WatchPermission.WRITE_SECURE.expectedGrantedOutput)
        permissionStates[WatchPermission.ACTION_ROUTING] = section("ACT").contains(WatchPermission.ACTION_ROUTING.expectedGrantedOutput)
        permissionStates[WatchPermission.MEDIA_BRIDGE] = section("MED").contains(WatchPermission.MEDIA_BRIDGE.expectedGrantedOutput)
        permissionStates[WatchPermission.BATTERY_WHITELIST] = section("BAT").contains(WatchPermission.BATTERY_WHITELIST.expectedGrantedOutput)

        a11yEnabled = section("A11Y").contains(WATCH_APP_PKG)
        isAssistantConfigured = section("AST").contains(WATCH_APP_PKG)
        isTocTocConfigured = section("TOC").contains(WATCH_APP_PKG)

        val spayDump = section("SPAY")
        val isSpayDisabled = spayDump.contains("package:$SAMSUNG_PAY_PKG") ||
            spayDump.contains("enabled=2") ||
            spayDump.contains("enabled=3") ||
            spayDump.contains("enabled=4") ||
            spayDump.contains("enabledSetting=2") ||
            spayDump.contains("enabledSetting=3") ||
            spayDump.contains("enabledSetting=4")
        isSamsungPayEnabled = !isSpayDisabled

        // 5. Read shared_prefs mappings if Bluetooth hasn't synced them yet
        if (!isBtSynced && installedWatchVersion != null) {
            val prefsXml = adbHelper.executeCommand(
                host,
                port,
                "run-as $WATCH_APP_PKG cat shared_prefs/mapping.xml 2>/dev/null"
            )
            if (prefsXml.contains("<map>")) {
                Regex("<string name=\"([^\"]+)\">([^<]+)</string>").findAll(prefsXml).forEach { m ->
                    gestureMappings[m.groupValues[1]] = m.groupValues[2]
                }
                Regex("<boolean name=\"remapper_master_enabled\" value=\"(true|false)\"").find(prefsXml)?.let {
                    isMasterEnabled = it.groupValues[1].toBoolean()
                }
                Regex("<boolean name=\"remapper_haptic_feedback_enabled\" value=\"(true|false)\"").find(prefsXml)?.let {
                    isHapticEnabled = it.groupValues[1].toBoolean()
                }
            }
        }

        dataLayerBridge.requestSyncFromWatch()
        return true
    }

    // Verify cached ADB connection ONLY when user explicitly connects or expands with a valid connection
    LaunchedEffect(activeTab, isAdbExpanded) {
        if (activeTab == CompanionTab.SETTINGS && isAdbExpanded) {
            adbWizardStep = if (isAdbPaired || isAdbConnected) 3 else 1
            dataLayerBridge.requestSyncFromWatch()
        } else {
            isMdnsScanning = false
        }
    }

    // Automatically trigger NsdPickerActivity / mDNS discovery ONLY when PIN is captured without pairing port
    // or when entering Step 3 (Connect) without a known ADB port
    LaunchedEffect(isAdbExpanded, adbWizardStep, pairingCode, pairPort, watchPort) {
        if (!isAdbExpanded || activeTab != CompanionTab.SETTINGS) return@LaunchedEffect
        if (adbWizardStep == 2 && pairPort.isEmpty() && !isMdnsScanning) {
            // Give Bluetooth Accessibility sync 350ms to deliver capturedPairingPort before opening NsdPicker
            delay(350L)
            if (adbWizardStep == 2 && pairPort.isEmpty() && !isMdnsScanning) {
                isMdnsScanning = true
            }
        } else if (adbWizardStep == 3 && !isAdbConnected && watchPort.isEmpty() && !isMdnsScanning) {
            delay(350L)
            if (adbWizardStep == 3 && !isAdbConnected && watchPort.isEmpty() && !isMdnsScanning) {
                isMdnsScanning = true
            }
        }
    }

    DisposableEffect(isMdnsScanning, adbWizardStep) {
        if (isMdnsScanning) {
            val wantPairing = (adbWizardStep == 2)
            adbClient.startDiscovery(wantPairing = wantPairing) { endpoint ->
                watchIp = endpoint.host
                if (endpoint.isPairing) {
                    pairPort = endpoint.port.toString()
                    mdnsPairDetected = true
                    if (!isAdbPaired) adbWizardStep = 2
                    dataLayerBridge.requestSyncFromWatch()
                } else {
                    watchPort = endpoint.port.toString()
                    mdnsConnectDetected = true
                }
                if ((adbWizardStep == 2 && pairPort.isNotEmpty()) || (adbWizardStep == 3 && watchPort.isNotEmpty())) {
                    isMdnsScanning = false
                }
            }
        }
        onDispose {
            adbClient.stopDiscovery()
        }
    }

    // Auto-connect when watch connect port is detected in Step 3
    LaunchedEffect(mdnsConnectDetected, watchPort, isAdbPaired, adbWizardStep) {
        val port = watchPort.toIntOrNull()
        if (isAdbPaired && adbWizardStep == 3 && mdnsConnectDetected && !isAdbConnected && !isAdbConnecting && watchIp.isNotBlank() && port != null) {
            mdnsConnectDetected = false
            isAdbConnecting = true
            try {
                withTimeoutOrNull(14000L) {
                    fetchFullWatchStateViaAdb(watchIp, port, forceReconnect = true)
                }?.let { ok ->
                    if (ok) {
                        adbStatusMsg = context.getString(R.string.adb_status_connected, watchIp, port.toString())
                        isAdbStatusOk = true
                        isAdbExpanded = false
                    }
                }
            } finally {
                isAdbConnecting = false
            }
        }
    }

    // Helper to update a button/gesture mapping
    fun updateWatchMapping(gesture: String, action: String) {
        gestureMappings[gesture] = action
        val cmd = JSONObject().apply {
            put("type", "set_mapping")
            put("gesture", gesture)
            put("action", action)
        }
        dataLayerBridge.sendCommandToWatch(cmd)
    }

    fun updateMasterSwitch(enabled: Boolean) {
        isMasterEnabled = enabled
        val cmd = JSONObject().apply {
            put("type", "set_master")
            put("enabled", enabled)
        }
        dataLayerBridge.sendCommandToWatch(cmd)
    }

    fun updateHapticSwitch(enabled: Boolean) {
        isHapticEnabled = enabled
        val cmd = JSONObject().apply {
            put("type", "set_haptic")
            put("enabled", enabled)
        }
        dataLayerBridge.sendCommandToWatch(cmd)
    }

    // Diagnostic calculation
    val allConfiguredActions = WatchActionCatalog.allGestures.map { gestureMappings[it.first] ?: "none" }.toSet()
    val bottomConfigured = WatchActionCatalog.bottomGestures.any { (gestureMappings[it.first] ?: "none") != "none" }
    val needsA11y = bottomConfigured || allConfiguredActions.contains("flashlight")
    val needsSecureSettings = allConfiguredActions.any { it in setOf("lte_toggle", "adb_mode") } ||
        (gestureMappings["toctoc"] ?: "none") != "none" ||
        (gestureMappings["top_double"] ?: "none") != "none" ||
        (gestureMappings["top_long"] ?: "none") != "none"
    val needsMediaBridge = a11yEnabled || allConfiguredActions.any { act ->
        act == "flashlight" || WatchActionCatalog.mediaActions.any { it.first == act }
    }
    val needsOverlay = allConfiguredActions.contains("red_flashlight") || allConfiguredActions.contains("flashlight")
    val topLongAct = gestureMappings["top_long"] ?: "none"
    val needsAssistant = isMasterEnabled && topLongAct != "none" && topLongAct != "assistant"
    val needsTocToc = isMasterEnabled && (gestureMappings["toctoc"] ?: "none") != "none"
    val needsSamsungPayFreeze = isMasterEnabled &&
        (gestureMappings["bottom_long"] ?: "none") != "none" &&
        (isSamsungPayEnabled == true)

    val watchConnectedAny = isBtSynced || isAdbConnected

    // Developer Options Dedicated Subscreen (7 taps on version in Settings)
    if (showDevOptionsScreen) {
        BackHandler { showDevOptionsScreen = false }
        DevOptionsFullScreen(
            isBetaEnabled = isBetaEnabled,
            onToggleBeta = { next ->
                isBetaEnabled = next
                UpdateChecker.setBetaChannel(context, next)
            },
            onBack = { showDevOptionsScreen = false }
        )
        return
    }

    Scaffold(
        containerColor = palette.bg,
        topBar = {
            // Compact Status Bar (NO title "Ultra Remapper Companion")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bg)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatusPill(
                    label = if (isBtSynced) {
                        if (!btWatchName.isNullOrBlank()) {
                            stringResource(R.string.status_bt_connected, btWatchName!!)
                        } else {
                            stringResource(R.string.status_bt_synced)
                        }
                    } else {
                        stringResource(R.string.status_bt_waiting)
                    },
                    active = isBtSynced,
                    activeColor = GreenOk,
                    onClick = { dataLayerBridge.requestSyncFromWatch() }
                )

                StatusPill(
                    label = when {
                        isAdbConnected -> stringResource(R.string.status_adb_connected)
                        isAdbPaired -> stringResource(R.string.status_adb_paired)
                        else -> stringResource(R.string.status_adb_unpaired)
                    },
                    active = isAdbConnected,
                    activeColor = GreenOk,
                    onClick = {
                        activeTab = CompanionTab.SETTINGS
                        isAdbExpanded = true
                    }
                )
            }
        },
        bottomBar = {
            NavigationBar(
                containerColor = palette.navBarBg,
                tonalElevation = 0.dp,
                modifier = Modifier.border(
                    width = 1.dp,
                    color = palette.border,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
                )
            ) {
                NavigationBarItem(
                    selected = activeTab == CompanionTab.BUTTONS,
                    onClick = { activeTab = CompanionTab.BUTTONS },
                    icon = {
                        ButtonsTabIcon(
                            color = if (activeTab == CompanionTab.BUTTONS) UltraOrange else palette.textSecondary
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.nav_buttons),
                            fontWeight = if (activeTab == CompanionTab.BUTTONS) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = UltraOrange,
                        selectedTextColor = UltraOrange,
                        indicatorColor = UltraOrange.copy(alpha = 0.15f),
                        unselectedIconColor = palette.textSecondary,
                        unselectedTextColor = palette.textSecondary
                    )
                )

                NavigationBarItem(
                    selected = activeTab == CompanionTab.SETTINGS,
                    onClick = { activeTab = CompanionTab.SETTINGS },
                    icon = {
                        SettingsTabIcon(
                            color = if (activeTab == CompanionTab.SETTINGS) UltraOrange else palette.textSecondary
                        )
                    },
                    label = {
                        Text(
                            text = stringResource(R.string.nav_settings),
                            fontWeight = if (activeTab == CompanionTab.SETTINGS) FontWeight.Bold else FontWeight.Medium
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = UltraOrange,
                        selectedTextColor = UltraOrange,
                        indicatorColor = UltraOrange.copy(alpha = 0.15f),
                        unselectedIconColor = palette.textSecondary,
                        unselectedTextColor = palette.textSecondary
                    )
                )
            }
        }
    ) { innerPadding ->
        if (activeTab == CompanionTab.BUTTONS) {
            LazyColumn(
                state = buttonsListState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. COMPANION APP SELF-UPDATE BANNER (if available)
                companionUpdate?.let { upd ->
                    item(key = "companion_update_banner") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.5.dp, UltraOrange, RoundedCornerShape(18.dp)),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = palette.cardBg)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.companion_update_title, upd.versionName),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = UltraOrange
                                )
                                if (companionUpdateStatus.isNotEmpty()) {
                                    LinearProgressIndicator(
                                        progress = { companionUpdateProgress },
                                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                        color = UltraOrange,
                                        trackColor = palette.border
                                    )
                                    Text(companionUpdateStatus, fontSize = 12.sp, color = palette.textPrimary)
                                } else {
                                    Button(
                                        onClick = {
                                            UpdateChecker.downloadAndInstallCompanion(context, upd) { frac, status ->
                                                companionUpdateProgress = frac
                                                companionUpdateStatus = status
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = UltraOrange),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            stringResource(R.string.companion_update_btn),
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. DIAGNOSTIC BANNERS
                if (watchConnectedAny) {
                    val missingRoute = permissionStates[WatchPermission.ACTION_ROUTING] == false
                    val missingOverlay = permissionStates[WatchPermission.OVERLAY] == false && needsOverlay
                    val missingMedia = permissionStates[WatchPermission.MEDIA_BRIDGE] == false && needsMediaBridge
                    val missingSecure = permissionStates[WatchPermission.WRITE_SECURE] == false && needsSecureSettings
                    val missingA11y = !a11yEnabled && needsA11y
                    val missingAssistant = !isAssistantConfigured && needsAssistant
                    val missingTocToc = !isTocTocConfigured && needsTocToc

                    if (missingRoute || missingOverlay || missingMedia || missingSecure || missingA11y || missingAssistant || missingTocToc || needsSamsungPayFreeze) {
                        item(key = "diag_banners") {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = stringResource(R.string.diag_header_status),
                                    color = RedAlert,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(horizontal = 4.dp)
                                )

                                if (missingRoute) {
                                    DiagBanner(
                                        title = stringResource(R.string.diag_route_title),
                                        subtitle = stringResource(R.string.diag_route_sub),
                                        badge = stringResource(R.string.badge_adb)
                                    ) {
                                        activeTab = CompanionTab.SETTINGS
                                        isPermsExpanded = true
                                    }
                                }
                                if (missingOverlay) {
                                    DiagBanner(
                                        title = stringResource(R.string.diag_overlay_title),
                                        subtitle = stringResource(R.string.diag_overlay_sub),
                                        badge = stringResource(R.string.badge_adb)
                                    ) {
                                        activeTab = CompanionTab.SETTINGS
                                        isPermsExpanded = true
                                    }
                                }
                                if (missingSecure) {
                                    DiagBanner(
                                        title = stringResource(R.string.diag_secure_title),
                                        subtitle = stringResource(R.string.diag_secure_sub),
                                        badge = stringResource(R.string.badge_adb)
                                    ) {
                                        activeTab = CompanionTab.SETTINGS
                                        isPermsExpanded = true
                                    }
                                }
                                if (missingMedia) {
                                    DiagBanner(
                                        title = stringResource(R.string.diag_media_title),
                                        subtitle = stringResource(R.string.diag_media_sub),
                                        badge = stringResource(R.string.badge_adb)
                                    ) {
                                        activeTab = CompanionTab.SETTINGS
                                        isPermsExpanded = true
                                    }
                                }
                                if (missingA11y) {
                                    DiagBanner(
                                        title = stringResource(R.string.diag_a11y_title),
                                        subtitle = stringResource(R.string.diag_a11y_sub),
                                        badge = stringResource(R.string.badge_watch)
                                    ) {
                                        dataLayerBridge.sendCommandToWatch(JSONObject().put("type", "open_a11y"))
                                        Toast.makeText(context, context.getString(R.string.toast_opening_a11y), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                if (missingAssistant) {
                                    DiagBanner(
                                        title = stringResource(R.string.diag_assistant_title),
                                        subtitle = stringResource(R.string.diag_assistant_sub),
                                        badge = stringResource(R.string.badge_watch)
                                    ) {
                                        dataLayerBridge.sendCommandToWatch(JSONObject().put("type", "open_assistant"))
                                        Toast.makeText(context, context.getString(R.string.toast_select_assistant), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                if (missingTocToc) {
                                    DiagBanner(
                                        title = stringResource(R.string.diag_toctoc_title),
                                        subtitle = stringResource(R.string.diag_toctoc_sub),
                                        badge = stringResource(R.string.badge_watch)
                                    ) {
                                        dataLayerBridge.sendCommandToWatch(JSONObject().put("type", "open_toctoc"))
                                        Toast.makeText(context, context.getString(R.string.toast_select_toctoc), Toast.LENGTH_SHORT).show()
                                    }
                                }
                                if (needsSamsungPayFreeze) {
                                    DiagBanner(
                                        title = stringResource(R.string.diag_spay_title),
                                        subtitle = stringResource(R.string.diag_spay_sub),
                                        badge = stringResource(R.string.badge_adb)
                                    ) {
                                        activeTab = CompanionTab.SETTINGS
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. BUTTON & GESTURE CONFIGURATION
                item(key = "hdr_remap_config") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.hdr_buttons_gestures),
                            color = palette.textPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        if (!watchConnectedAny) {
                            Text(
                                text = stringResource(R.string.sub_connect_to_configure),
                                color = palette.textSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // Top Button Collapsible
                item(key = "card_top_button") {
                    val count = WatchActionCatalog.topGestures.count { (gestureMappings[it.first] ?: "none") != "none" }
                    WatchSectionCollapsible(
                        iconRes = R.drawable.ic_watch_top_button,
                        title = stringResource(R.string.btn_top_title),
                        subtitle = if (count > 0) stringResource(R.string.count_configured, count) else stringResource(R.string.status_unassigned),
                        isHighlighted = count > 0,
                        isExpanded = isTopExpanded,
                        onToggle = { isTopExpanded = !isTopExpanded }
                    ) {
                        WatchActionCatalog.topGestures.forEach { (gesture, nameRes) ->
                            val act = gestureMappings[gesture] ?: "none"
                            GestureMappingRow(
                                gestureName = stringResource(nameRes),
                                actionLabel = WatchActionCatalog.getActionLabel(context, act, watchInstalledApps),
                                isConfigured = act != "none",
                                enabled = watchConnectedAny
                            ) {
                                pickingAppSublist = false
                                pickingGesture = gesture
                            }
                        }
                    }
                }

                // Center / Action Button Collapsible
                item(key = "card_action_button") {
                    val count = WatchActionCatalog.actionGestures.count { (gestureMappings[it.first] ?: "none") != "none" }
                    WatchSectionCollapsible(
                        iconRes = R.drawable.ic_watch_quick_button,
                        title = stringResource(R.string.btn_center_title),
                        subtitle = if (count > 0) stringResource(R.string.count_configured, count) else stringResource(R.string.status_unassigned),
                        isHighlighted = count > 0,
                        isExpanded = isActionExpanded,
                        onToggle = { isActionExpanded = !isActionExpanded }
                    ) {
                        Text(
                            text = stringResource(R.string.btn_center_note),
                            color = palette.textSecondary,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                        WatchActionCatalog.actionGestures.forEach { (gesture, nameRes) ->
                            val act = gestureMappings[gesture] ?: "none"
                            GestureMappingRow(
                                gestureName = stringResource(nameRes),
                                actionLabel = WatchActionCatalog.getActionLabel(context, act, watchInstalledApps),
                                isConfigured = act != "none",
                                enabled = watchConnectedAny
                            ) {
                                pickingAppSublist = false
                                pickingGesture = gesture
                            }
                        }
                    }
                }

                // Bottom Button Collapsible
                item(key = "card_bottom_button") {
                    val count = WatchActionCatalog.bottomGestures.count { (gestureMappings[it.first] ?: "none") != "none" }
                    WatchSectionCollapsible(
                        iconRes = R.drawable.ic_watch_bottom_button,
                        title = stringResource(R.string.btn_bottom_title),
                        subtitle = if (count > 0) stringResource(R.string.count_configured, count) else stringResource(R.string.status_unassigned),
                        isHighlighted = count > 0,
                        isExpanded = isBottomExpanded,
                        onToggle = { isBottomExpanded = !isBottomExpanded }
                    ) {
                        WatchActionCatalog.bottomGestures.forEach { (gesture, nameRes) ->
                            val act = gestureMappings[gesture] ?: "none"
                            GestureMappingRow(
                                gestureName = stringResource(nameRes),
                                actionLabel = WatchActionCatalog.getActionLabel(context, act, watchInstalledApps),
                                isConfigured = act != "none",
                                enabled = watchConnectedAny
                            ) {
                                pickingAppSublist = false
                                pickingGesture = gesture
                            }
                        }
                    }
                }

                // Gestures (Toc toc) Collapsible
                item(key = "card_gestures") {
                    val count = WatchActionCatalog.gestureList.count { (gestureMappings[it.first] ?: "none") != "none" }
                    WatchSectionCollapsible(
                        iconRes = R.drawable.ic_wrist_gesture,
                        title = stringResource(R.string.btn_gestures_title),
                        subtitle = if (count > 0) stringResource(R.string.status_toctoc_active) else stringResource(R.string.status_unassigned),
                        isHighlighted = count > 0,
                        isExpanded = isGesturesExpanded,
                        onToggle = { isGesturesExpanded = !isGesturesExpanded }
                    ) {
                        WatchActionCatalog.gestureList.forEach { (gesture, nameRes) ->
                            val act = gestureMappings[gesture] ?: "none"
                            GestureMappingRow(
                                gestureName = stringResource(nameRes),
                                actionLabel = WatchActionCatalog.getActionLabel(context, act, watchInstalledApps),
                                isConfigured = act != "none",
                                enabled = watchConnectedAny
                            ) {
                                pickingAppSublist = false
                                pickingGesture = gesture
                            }
                        }
                    }
                }
            }
        } else {
            // SETTINGS TAB
            LazyColumn(
                state = settingsListState,
                contentPadding = PaddingValues(top = 8.dp, bottom = 28.dp, start = 16.dp, end = 16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. WATCH APP INSTALL / UPDATE BANNER
                val watchRel = latestWatchRelease
                val watchNeedsInstall = (isAdbConnected && installedWatchVersion == null)
                val watchHasUpdate = (installedWatchVersion != null && watchRel != null &&
                    UpdateChecker.isNewerVersion(watchRel.versionName, installedWatchVersion!!))

                if (watchNeedsInstall || watchHasUpdate || isInstallingWatchApk || watchApkStatusMsg.isNotEmpty()) {
                    item(key = "watch_apk_banner") {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, UltraOrange.copy(alpha = 0.6f), RoundedCornerShape(18.dp)),
                            shape = RoundedCornerShape(18.dp),
                            colors = CardDefaults.cardColors(containerColor = palette.cardBg)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (installedWatchVersion == null) {
                                            stringResource(R.string.watch_install_title)
                                        } else {
                                            stringResource(R.string.watch_update_title, watchRel?.versionName ?: "")
                                        },
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = UltraOrange
                                    )
                                    Text(
                                        text = if (installedWatchVersion != null) {
                                            stringResource(R.string.watch_ver_installed, installedWatchVersion!!)
                                        } else {
                                            stringResource(R.string.watch_ver_not_installed)
                                        },
                                        fontSize = 11.sp,
                                        color = palette.textSecondary
                                    )
                                }

                                if (watchRel?.watchApkUrl != null && !isInstallingWatchApk && (watchNeedsInstall || watchHasUpdate)) {
                                    Button(
                                        onClick = {
                                            val port = watchPort.toIntOrNull()
                                            if (!isAdbConnected || watchIp.isBlank() || port == null) {
                                                isAdbExpanded = true
                                                Toast.makeText(context, context.getString(R.string.toast_connect_adb_first), Toast.LENGTH_SHORT).show()
                                                return@Button
                                            }
                                            isInstallingWatchApk = true
                                            watchApkProgress = 0.02f
                                            watchApkStatusMsg = context.getString(R.string.msg_downloading_ver, watchRel.versionName)
                                            watchApkSuccess = false

                                            UpdateChecker.downloadApkToFile(
                                                context = context,
                                                apkUrl = watchRel.watchApkUrl,
                                                targetFileName = "ultra-remapper-watch-v${watchRel.versionName}.apk",
                                                onProgress = { frac, status ->
                                                    watchApkProgress = frac * 0.45f
                                                    watchApkStatusMsg = status
                                                }
                                            ) { file ->
                                                if (file == null) {
                                                    isInstallingWatchApk = false
                                                    watchApkStatusMsg = context.getString(R.string.msg_download_error)
                                                    return@downloadApkToFile
                                                }
                                                scope.launch {
                                                    val (ok, msg) = adbHelper.installApkOnWatch(watchIp, port, file) { frac, status ->
                                                        scope.launch {
                                                            watchApkProgress = 0.45f + (frac * 0.55f)
                                                            watchApkStatusMsg = status
                                                        }
                                                    }
                                                    watchApkSuccess = ok
                                                    watchApkStatusMsg = msg
                                                    isInstallingWatchApk = false
                                                    if (ok) {
                                                        fetchFullWatchStateViaAdb(watchIp, port)
                                                        delay(3000)
                                                        watchApkStatusMsg = ""
                                                    }
                                                }
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = UltraOrange),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            text = if (installedWatchVersion == null) {
                                                stringResource(R.string.btn_install_watch, watchRel.versionName)
                                            } else {
                                                stringResource(R.string.btn_update_watch, watchRel.versionName)
                                            },
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White
                                        )
                                    }
                                }

                                if (isInstallingWatchApk || watchApkStatusMsg.isNotEmpty()) {
                                    if (isInstallingWatchApk) {
                                        LinearProgressIndicator(
                                            progress = { watchApkProgress },
                                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                                            color = UltraOrange,
                                            trackColor = palette.border
                                        )
                                    }
                                    Text(
                                        text = watchApkStatusMsg,
                                        fontSize = 12.sp,
                                        color = if (watchApkSuccess) GreenOk else if (isInstallingWatchApk) palette.textPrimary else RedAlert,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }

                // 2. GENERAL SETTINGS HEADER
                item(key = "hdr_settings_section") {
                    Text(
                        text = stringResource(R.string.hdr_general_settings),
                        color = palette.textPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Master Remapper, Haptic & Theme Triple Switch
                item(key = "master_haptic_card") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(palette.cardBg)
                            .border(1.dp, palette.border, RoundedCornerShape(18.dp))
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        WatchToggleRow(
                            title = stringResource(R.string.master_switch_title),
                            subtitle = if (isMasterEnabled) stringResource(R.string.master_switch_on) else stringResource(R.string.master_switch_off),
                            checked = isMasterEnabled,
                            enabled = watchConnectedAny,
                            onToggle = { updateMasterSwitch(!isMasterEnabled) }
                        )
                        WatchToggleRow(
                            title = stringResource(R.string.haptic_switch_title),
                            subtitle = stringResource(R.string.haptic_switch_sub),
                            checked = isHapticEnabled,
                            enabled = watchConnectedAny,
                            onToggle = { updateHapticSwitch(!isHapticEnabled) }
                        )
                        ThemeTripleSwitchRow(
                            title = stringResource(R.string.theme_selector_title),
                            subtitle = stringResource(R.string.theme_selector_sub),
                            selectedMode = themeMode,
                            onSelectMode = onThemeModeChange
                        )
                    }
                }

                // 3. COLLAPSIBLE ADB CONNECTION CARD
                item(key = "adb_collapsible") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(palette.cardBg)
                            .border(1.dp, palette.border, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { isAdbExpanded = !isAdbExpanded }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.hdr_adb_wireless),
                                    color = palette.textPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                val statusText = when {
                                    isAdbConnecting -> stringResource(R.string.adb_status_connecting)
                                    isAdbConnected -> stringResource(R.string.adb_status_connected, watchIp, watchPort)
                                    isAdbPaired -> stringResource(R.string.adb_status_paired_tap)
                                    else -> stringResource(R.string.adb_status_unpaired)
                                }
                                Text(
                                    text = statusText,
                                    color = when {
                                        isAdbConnecting -> Color(0xFFFFB74D)
                                        isAdbConnected -> GreenOk
                                        isAdbPaired -> UltraOrange
                                        else -> palette.textSecondary
                                    },
                                    fontSize = 12.sp
                                )
                            }
                            CollapseChevron(isExpanded = isAdbExpanded)
                        }

                        AnimatedVisibility(visible = isAdbExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // 3-Step Progress Indicator Bar
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    for (s in 1..3) {
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(4.dp)
                                                .clip(RoundedCornerShape(2.dp))
                                                .background(
                                                    when {
                                                        s < adbWizardStep -> GreenOk
                                                        s == adbWizardStep -> UltraOrange
                                                        else -> palette.border
                                                    }
                                                )
                                        )
                                    }
                                }

                                // Step Header Badge
                                Text(
                                    text = when (adbWizardStep) {
                                        1 -> stringResource(R.string.adb_wiz_step1_title)
                                        2 -> stringResource(R.string.adb_wiz_step2_title)
                                        else -> stringResource(R.string.adb_wiz_step3_title)
                                    },
                                    color = UltraOrange,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp
                                )

                                Text(
                                    text = when (adbWizardStep) {
                                        1 -> if (installedWatchVersion != null) stringResource(R.string.adb_wiz_step1_instructions_installed) else stringResource(R.string.adb_wiz_step1_instructions)
                                        2 -> stringResource(R.string.adb_wiz_step2_instructions)
                                        else -> stringResource(R.string.adb_wiz_step3_instructions)
                                    },
                                    color = palette.textSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 17.sp
                                )

                                when (adbWizardStep) {
                                    1 -> {
                                        // STEP 1: ENABLE WIRELESS DEBUGGING ON WATCH
                                        // Show 1-click button ONLY if watch app is already installed and listening
                                        if (installedWatchVersion != null) {
                                            Button(
                                                enabled = !isPairing && !isAdbConnecting,
                                                onClick = {
                                                    dataLayerBridge.sendCommandToWatch(JSONObject().put("type", "open_wireless_adb"))
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.toast_opening_wireless_adb),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = UltraOrange),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.adb_open_on_watch_btn),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }

                                        OutlinedButton(
                                            enabled = !isPairing && !isAdbConnecting,
                                            onClick = {
                                                adbStatusMsg = null
                                                adbWizardStep = 2
                                                dataLayerBridge.requestSyncFromWatch()
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = stringResource(R.string.adb_wiz_step1_next),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        OutlinedButton(
                                            enabled = !isPairing && !isAdbConnecting,
                                            onClick = {
                                                adbHelper.setPaired(true, watchIp.ifBlank { null }, null)
                                                isAdbPaired = true
                                                adbStatusMsg = null
                                                adbWizardStep = 3
                                                dataLayerBridge.requestSyncFromWatch()
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                text = stringResource(R.string.adb_wiz_step1_skip),
                                                fontSize = 12.sp,
                                                color = palette.textSecondary
                                            )
                                        }
                                    }

                                    2 -> {
                                        // STEP 2: PAIR WATCH WITH 6-DIGIT PIN
                                        if (mdnsPairDetected && watchIp.isNotBlank() && pairPort.isNotBlank()) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(palette.mdnsBannerBg)
                                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.adb_mdns_pair_found, watchIp, pairPort),
                                                    fontSize = 12.sp,
                                                    color = GreenOk,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = watchIp,
                                                onValueChange = { watchIp = it },
                                                label = { Text(stringResource(R.string.label_watch_ip), fontSize = 11.sp) },
                                                placeholder = { Text("192.168.1.X") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1.5f),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = UltraOrange,
                                                    unfocusedBorderColor = palette.border,
                                                    focusedTextColor = palette.textPrimary,
                                                    unfocusedTextColor = palette.textPrimary
                                                )
                                            )
                                            OutlinedTextField(
                                                value = pairPort,
                                                onValueChange = { pairPort = it.filter { c -> c.isDigit() } },
                                                label = { Text(stringResource(R.string.label_pairing_port), fontSize = 11.sp) },
                                                placeholder = { Text("38123") },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.weight(1f),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = UltraOrange,
                                                    unfocusedBorderColor = palette.border,
                                                    focusedTextColor = palette.textPrimary,
                                                    unfocusedTextColor = palette.textPrimary
                                                )
                                            )
                                        }

                                        OutlinedTextField(
                                            value = pairingCode,
                                            onValueChange = { pairingCode = it.filter { c -> c.isDigit() }.take(6) },
                                            label = { Text(stringResource(R.string.label_pairing_pin), fontSize = 11.sp) },
                                            placeholder = { Text("000000") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                            modifier = Modifier.fillMaxWidth(),
                                            colors = OutlinedTextFieldDefaults.colors(
                                                focusedBorderColor = UltraOrange,
                                                unfocusedBorderColor = palette.border,
                                                focusedTextColor = palette.textPrimary,
                                                unfocusedTextColor = palette.textPrimary
                                            )
                                        )

                                        Button(
                                            enabled = !isPairing && pairingCode.length == 6,
                                            onClick = {
                                                val pPort = pairPort.toIntOrNull()
                                                if (watchIp.isBlank() || pPort == null) {
                                                    Toast.makeText(context, context.getString(R.string.toast_enter_ip_pair_port), Toast.LENGTH_SHORT).show()
                                                    return@Button
                                                }
                                                scope.launch {
                                                    isPairing = true
                                                    adbStatusMsg = context.getString(R.string.msg_adb_pairing)
                                                    val (ok, msg) = adbHelper.pairDevice(watchIp, pPort, pairingCode)
                                                    isPairing = false
                                                    adbStatusMsg = msg
                                                    isAdbStatusOk = ok
                                                    if (ok) {
                                                        dataLayerBridge.sendCommandToWatch(JSONObject().put("type", "clear_captured_adb"))
                                                        isAdbPaired = true
                                                        mdnsPairDetected = false
                                                        pairingCode = ""
                                                        pairPort = ""
                                                        adbWizardStep = 3
                                                        dataLayerBridge.requestSyncFromWatch()
                                                        val cPort = watchPort.toIntOrNull()
                                                        if (cPort != null) {
                                                            isAdbConnecting = true
                                                            val connOk = fetchFullWatchStateViaAdb(watchIp, cPort)
                                                            isAdbConnecting = false
                                                            if (connOk) {
                                                                adbStatusMsg = context.getString(R.string.adb_status_connected, watchIp, cPort.toString())
                                                                isAdbStatusOk = true
                                                                isAdbExpanded = false
                                                            }
                                                        }
                                                    }
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = UltraOrange),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            if (isPairing) {
                                                SwitchLoadingSpinner()
                                            } else {
                                                Text(
                                                    stringResource(R.string.btn_pair_with_pin),
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White
                                                )
                                            }
                                        }

                                        OutlinedButton(
                                            enabled = !isPairing,
                                            onClick = {
                                                adbStatusMsg = null
                                                adbWizardStep = 1
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                stringResource(R.string.adb_wiz_step2_back),
                                                fontSize = 12.sp,
                                                color = palette.textSecondary
                                            )
                                        }
                                    }

                                    else -> {
                                        // STEP 3: CONNECT ADB SESSION (Watch IP + ADB Connection Port)
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(palette.mdnsBannerBg)
                                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = when {
                                                    isAdbConnected -> stringResource(R.string.adb_wiz_connected_badge) + " ($watchIp:$watchPort)"
                                                    mdnsConnectDetected && watchPort.isNotBlank() -> stringResource(R.string.adb_mdns_connect_found, watchIp, watchPort)
                                                    else -> stringResource(R.string.adb_wiz_paired_badge)
                                                },
                                                fontSize = 12.sp,
                                                color = GreenOk,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }

                                        if (installedWatchVersion != null) {
                                            OutlinedButton(
                                                enabled = !isPairing && !isAdbConnecting,
                                                onClick = {
                                                    dataLayerBridge.sendCommandToWatch(JSONObject().put("type", "open_wireless_adb"))
                                                    Toast.makeText(
                                                        context,
                                                        context.getString(R.string.toast_opening_wireless_adb),
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = UltraOrange),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.adb_open_on_watch_btn),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            OutlinedTextField(
                                                value = watchIp,
                                                onValueChange = { watchIp = it },
                                                label = { Text(stringResource(R.string.label_watch_ip), fontSize = 11.sp) },
                                                placeholder = { Text("192.168.1.X") },
                                                singleLine = true,
                                                modifier = Modifier.weight(1.5f),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = UltraOrange,
                                                    unfocusedBorderColor = palette.border,
                                                    focusedTextColor = palette.textPrimary,
                                                    unfocusedTextColor = palette.textPrimary
                                                )
                                            )
                                            OutlinedTextField(
                                                value = watchPort,
                                                onValueChange = { watchPort = it.filter { c -> c.isDigit() } },
                                                label = { Text(stringResource(R.string.label_adb_port), fontSize = 11.sp) },
                                                placeholder = { Text("45183") },
                                                singleLine = true,
                                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                                modifier = Modifier.weight(1f),
                                                colors = OutlinedTextFieldDefaults.colors(
                                                    focusedBorderColor = UltraOrange,
                                                    unfocusedBorderColor = palette.border,
                                                    focusedTextColor = palette.textPrimary,
                                                    unfocusedTextColor = palette.textPrimary
                                                )
                                            )
                                        }

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                enabled = true,
                                                onClick = {
                                                    val port = watchPort.toIntOrNull()
                                                    if (watchIp.isBlank() || port == null) {
                                                        Toast.makeText(context, context.getString(R.string.toast_enter_ip_adb_port), Toast.LENGTH_SHORT).show()
                                                        return@Button
                                                    }
                                                    scope.launch {
                                                        isAdbConnecting = true
                                                        adbStatusMsg = context.getString(R.string.msg_adb_connecting)
                                                        val ok = withTimeoutOrNull(14000L) {
                                                            fetchFullWatchStateViaAdb(watchIp, port, forceReconnect = true)
                                                        } ?: false
                                                        isAdbConnecting = false
                                                        if (ok) {
                                                            adbStatusMsg = context.getString(R.string.adb_status_connected, watchIp, port.toString())
                                                            isAdbStatusOk = true
                                                            delay(300)
                                                            isAdbExpanded = false
                                                        } else {
                                                            // Stay on Step 3 (Connect) and show clear error message!
                                                            adbWizardStep = 3
                                                            val detail = adbHelper.lastConnectError?.let { "\n($it)" } ?: ""
                                                            adbStatusMsg = context.getString(R.string.msg_adb_connect_failed, watchIp, port.toString()) + detail
                                                            isAdbStatusOk = false
                                                        }
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = if (isAdbConnected) palette.rowBg else UltraOrange
                                                ),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.weight(1.4f)
                                            ) {
                                                if (isAdbConnecting) {
                                                    SwitchLoadingSpinner()
                                                } else {
                                                    Text(
                                                        text = if (isAdbConnected) stringResource(R.string.btn_refresh_adb) else stringResource(R.string.btn_connect_adb),
                                                        fontWeight = FontWeight.Bold,
                                                        color = if (isAdbConnected) palette.textPrimary else Color.White,
                                                        fontSize = 13.sp
                                                    )
                                                }
                                            }

                                            OutlinedButton(
                                                enabled = !isAdbConnecting,
                                                onClick = {
                                                    adbHelper.unpair()
                                                    dataLayerBridge.sendCommandToWatch(JSONObject().put("type", "clear_captured_adb"))
                                                    isAdbPaired = false
                                                    isAdbConnected = false
                                                    lastAppliedPin = ""
                                                    pairingCode = ""
                                                    pairPort = ""
                                                    adbWizardStep = 1
                                                    adbStatusMsg = context.getString(R.string.msg_adb_unpaired)
                                                    isAdbStatusOk = false
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = RedAlert),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text(stringResource(R.string.btn_unpair_adb), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                            }
                                        }

                                        OutlinedButton(
                                            enabled = !isAdbConnecting,
                                            onClick = {
                                                dataLayerBridge.sendCommandToWatch(JSONObject().put("type", "clear_captured_adb"))
                                                lastAppliedPin = ""
                                                pairingCode = ""
                                                pairPort = ""
                                                adbStatusMsg = null
                                                adbWizardStep = 2
                                                dataLayerBridge.requestSyncFromWatch()
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Text(
                                                stringResource(R.string.btn_need_new_pin),
                                                fontSize = 12.sp,
                                                color = palette.textSecondary
                                            )
                                        }
                                    }
                                }

                                adbStatusMsg?.let { msg ->
                                    Text(
                                        text = msg,
                                        fontSize = 12.sp,
                                        color = if (isAdbStatusOk) GreenOk else RedAlert,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }

                // 4. COLLAPSIBLE PERMISSIONS CARD
                item(key = "permissions_collapsible") {
                    val grantedCount = WatchPermission.values().count { permissionStates[it] == true }
                    val totalPerms = WatchPermission.values().size

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(palette.cardBg)
                            .border(1.dp, palette.border, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { isPermsExpanded = !isPermsExpanded }
                                .padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.hdr_permissions),
                                    color = palette.textPrimary,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (permissionStates.isEmpty()) {
                                        stringResource(R.string.perms_connect_to_view)
                                    } else {
                                        stringResource(R.string.perms_active_count, grantedCount, totalPerms)
                                    },
                                    color = if (grantedCount == totalPerms && totalPerms > 0) GreenOk else palette.textSecondary,
                                    fontSize = 12.sp
                                )
                            }
                            CollapseChevron(isExpanded = isPermsExpanded)
                        }

                        AnimatedVisibility(visible = isPermsExpanded) {
                            Column(
                                modifier = Modifier.padding(top = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                WatchPermission.values().forEach { perm ->
                                    val isPermKnown = permissionStates.containsKey(perm)
                                    val isGranted = permissionStates[perm] == true
                                    val isThisLoading = activeLoadingItem == perm.key
                                    val isAnyBusy = activeLoadingItem != null || isAdbConnecting || isPairing

                                    WatchToggleRow(
                                        title = stringResource(perm.titleRes),
                                        subtitle = stringResource(perm.descRes),
                                        checked = isGranted,
                                        isUnknownState = !isPermKnown,
                                        isLoading = isThisLoading,
                                        enabled = !isAnyBusy,
                                        onToggle = {
                                            if (activeLoadingItem != null) return@WatchToggleRow
                                            val port = watchPort.toIntOrNull()
                                            if (watchIp.isBlank() || port == null) {
                                                isAdbExpanded = true
                                                Toast.makeText(context, context.getString(R.string.toast_connect_adb_first), Toast.LENGTH_SHORT).show()
                                                return@WatchToggleRow
                                            }
                                            val targetState = !isGranted
                                            activeLoadingItem = perm.key
                                            scope.launch {
                                                try {
                                                    val cmd = if (targetState) perm.grantCmd else perm.revokeCmd
                                                    val res = adbHelper.executeCommand(
                                                        watchIp,
                                                        port,
                                                        "$cmd; ${perm.checkCmd}; am broadcast -a com.aaronhrndz.ultraremapper.ACTION_STATE_UPDATED -p $WATCH_APP_PKG"
                                                    )
                                                    if (res.startsWith("ERROR:")) {
                                                        isAdbConnected = false
                                                        isAdbPaired = adbHelper.isPaired()
                                                        isAdbExpanded = true
                                                        Toast.makeText(context, res, Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        isAdbConnected = true
                                                        permissionStates[perm] = res.contains(perm.expectedGrantedOutput)
                                                        dataLayerBridge.requestSyncFromWatch()
                                                    }
                                                } finally {
                                                    activeLoadingItem = null
                                                }
                                            }
                                        }
                                    )
                                }

                                if (grantedCount < totalPerms) {
                                    OutlinedButton(
                                        enabled = activeLoadingItem == null && !isAdbConnecting && !isPairing,
                                        onClick = {
                                            if (activeLoadingItem != null) return@OutlinedButton
                                            val port = watchPort.toIntOrNull()
                                            if (watchIp.isBlank() || port == null) {
                                                isAdbExpanded = true
                                                Toast.makeText(context, context.getString(R.string.toast_connect_adb_first), Toast.LENGTH_SHORT).show()
                                                return@OutlinedButton
                                            }
                                            scope.launch {
                                                activeLoadingItem = "ALL_PERMS"
                                                try {
                                                    val allCmds = WatchPermission.values().joinToString("; ") { it.grantCmd }
                                                    adbHelper.executeCommand(watchIp, port, allCmds)
                                                    fetchFullWatchStateViaAdb(watchIp, port)
                                                } finally {
                                                    activeLoadingItem = null
                                                }
                                            }
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = UltraOrange),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (activeLoadingItem == "ALL_PERMS") {
                                            SwitchLoadingSpinner(color = UltraOrange)
                                        } else {
                                            Text(stringResource(R.string.btn_grant_missing_perms), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 5. SAMSUNG PAY FREEZE ROW
                item(key = "samsung_pay_row") {
                    val isPayLoading = activeLoadingItem == "SAMSUNG_PAY"
                    val isPayStateKnown = isSamsungPayEnabled != null
                    val payEnabled = isSamsungPayEnabled == true
                    val isAnyBusy = activeLoadingItem != null || isAdbConnecting || isPairing

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .background(palette.cardBg)
                            .border(1.dp, palette.border, RoundedCornerShape(18.dp))
                            .padding(12.dp)
                    ) {
                        WatchToggleRow(
                            title = stringResource(R.string.hdr_samsung_pay),
                            subtitle = when (isSamsungPayEnabled) {
                                true -> stringResource(R.string.spay_status_active)
                                false -> stringResource(R.string.spay_status_frozen)
                                null -> stringResource(R.string.spay_status_unknown)
                            },
                            subtitleColor = if (isSamsungPayEnabled == false) UltraOrange else palette.textSecondary,
                            checked = payEnabled,
                            isUnknownState = !isPayStateKnown,
                            isLoading = isPayLoading,
                            enabled = !isAnyBusy,
                            onToggle = {
                                if (activeLoadingItem != null) return@WatchToggleRow
                                val port = watchPort.toIntOrNull()
                                if (watchIp.isBlank() || port == null) {
                                    isAdbExpanded = true
                                    Toast.makeText(context, context.getString(R.string.toast_connect_adb_first), Toast.LENGTH_SHORT).show()
                                    return@WatchToggleRow
                                }
                                val targetEnabled = !payEnabled
                                activeLoadingItem = "SAMSUNG_PAY"
                                samsungPayError = null
                                scope.launch {
                                    try {
                                        val toggleCmd = if (targetEnabled) {
                                            "pm enable $SAMSUNG_PAY_PKG; pm unhide $SAMSUNG_PAY_PKG; cmd package install-existing $SAMSUNG_PAY_PKG"
                                        } else {
                                            "pm disable-user --user 0 $SAMSUNG_PAY_PKG || pm disable-user $SAMSUNG_PAY_PKG || pm disable $SAMSUNG_PAY_PKG || pm hide $SAMSUNG_PAY_PKG"
                                        }
                                        val checkAndNotifyCmd = "$toggleCmd; pm list packages -d $SAMSUNG_PAY_PKG; dumpsys package $SAMSUNG_PAY_PKG | grep -E 'enabled=[234]|enabledSetting=[234]'; am broadcast -a com.aaronhrndz.ultraremapper.ACTION_STATE_UPDATED -p $WATCH_APP_PKG"
                                        val res = adbHelper.executeCommand(watchIp, port, checkAndNotifyCmd)
                                        if (res.startsWith("ERROR:")) {
                                            isAdbConnected = false
                                            isAdbPaired = adbHelper.isPaired()
                                            isAdbExpanded = true
                                            samsungPayError = res
                                        } else {
                                            isAdbConnected = true
                                            val isSpayDisabled = res.contains("package:$SAMSUNG_PAY_PKG") ||
                                                res.contains("enabled=2") ||
                                                res.contains("enabled=3") ||
                                                res.contains("enabled=4") ||
                                                res.contains("enabledSetting=2") ||
                                                res.contains("enabledSetting=3") ||
                                                res.contains("enabledSetting=4")
                                            isSamsungPayEnabled = !isSpayDisabled
                                            dataLayerBridge.requestSyncFromWatch()
                                        }
                                    } finally {
                                        activeLoadingItem = null
                                    }
                                }
                            }
                        )

                        samsungPayError?.let { err ->
                            Text(
                                text = err,
                                color = RedAlert,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                            )
                        }
                    }
                }

                // 6. VERSION CODE FOOTER AT BOTTOM OF SETTINGS (7 taps -> DevOptionsFullScreen)
                item(key = "version_footer") {
                    val footerText = if (isBetaEnabled) {
                        stringResource(R.string.version_footer_beta, currentVersion)
                    } else {
                        stringResource(R.string.version_footer, currentVersion)
                    }
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp, bottom = 8.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                versionTapCount++
                                if (versionTapCount >= 7) {
                                    versionTapCount = 0
                                    showDevOptionsScreen = true
                                }
                            }
                            .padding(vertical = 8.dp)
                    ) {
                        Text(
                            text = footerText,
                            fontSize = 12.sp,
                            color = if (isBetaEnabled) UltraOrange else palette.textSecondary,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    // MODAL BOTTOM SHEET FOR PICKING BUTTON / GESTURE ACTION
    val activeGesture = pickingGesture
    if (activeGesture != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        val currentAct = gestureMappings[activeGesture] ?: "none"

        ModalBottomSheet(
            onDismissRequest = { pickingGesture = null },
            sheetState = sheetState,
            containerColor = palette.cardBg,
            contentColor = palette.textPrimary
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
            ) {
                var actionSearchQuery by remember(activeGesture, pickingAppSublist) { mutableStateOf("") }

                Text(
                    text = if (pickingAppSublist) {
                        stringResource(R.string.picker_select_watch_app)
                    } else {
                        WatchActionCatalog.getGestureTitle(context, activeGesture)
                    },
                    color = UltraOrange,
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                OutlinedTextField(
                    value = actionSearchQuery,
                    onValueChange = { actionSearchQuery = it },
                    placeholder = {
                        Text(
                            text = if (pickingAppSublist) stringResource(R.string.search_app_hint) else stringResource(R.string.search_action_hint),
                            fontSize = 13.sp,
                            color = palette.textSecondary
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            tint = palette.textSecondary
                        )
                    },
                    trailingIcon = {
                        if (actionSearchQuery.isNotEmpty()) {
                            IconButton(onClick = { actionSearchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = palette.textSecondary
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = UltraOrange,
                        unfocusedBorderColor = palette.border,
                        focusedTextColor = palette.textPrimary,
                        unfocusedTextColor = palette.textPrimary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 10.dp)
                )

                val query = actionSearchQuery.trim()

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    if (pickingAppSublist) {
                        item(key = "back_to_actions") {
                            OutlinedButton(
                                onClick = { pickingAppSublist = false },
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = palette.textPrimary),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.picker_back_to_actions))
                            }
                        }
                        val filteredApps = if (query.isEmpty()) {
                            watchInstalledApps
                        } else {
                            watchInstalledApps.filter {
                                it.label.contains(query, ignoreCase = true) ||
                                    it.pkg.contains(query, ignoreCase = true)
                            }
                        }
                        if (filteredApps.isEmpty()) {
                            item(key = "no_apps_msg") {
                                Text(
                                    text = stringResource(R.string.picker_no_apps_synced),
                                    color = palette.textSecondary,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                        } else {
                            items(filteredApps, key = { it.pkg }) { app ->
                                val actKey = "app:${app.pkg}"
                                ActionRadioRow(
                                    label = app.label,
                                    subtitle = app.pkg,
                                    selected = currentAct == actKey,
                                    iconB64 = app.iconB64
                                ) {
                                    updateWatchMapping(activeGesture, actKey)
                                    pickingGesture = null
                                }
                            }
                        }
                    } else {
                        if (query.isEmpty()) {
                            // Open App Row
                            item(key = "pick_open_app") {
                                val isApp = currentAct.startsWith("app:")
                                ActionRadioRow(
                                    label = stringResource(R.string.action_open_app),
                                    subtitle = if (isApp) {
                                        WatchActionCatalog.getActionLabel(context, currentAct, watchInstalledApps)
                                    } else {
                                        stringResource(R.string.action_choose_from_apps, watchInstalledApps.size)
                                    },
                                    selected = isApp
                                ) {
                                    pickingAppSublist = true
                                    if (watchInstalledApps.isEmpty()) {
                                        dataLayerBridge.requestSyncFromWatch(includeApps = true)
                                    }
                                }
                            }

                            // None Row
                            item(key = "pick_none") {
                                ActionRadioRow(
                                    label = stringResource(R.string.action_none),
                                    subtitle = null,
                                    selected = currentAct == "none"
                                ) {
                                    updateWatchMapping(activeGesture, "none")
                                    pickingGesture = null
                                }
                            }
                        }

                        // 1. SECTION: PHONE ACTIONS
                        val filteredPhoneActions = if (query.isEmpty()) {
                            WatchActionCatalog.phoneActions
                        } else {
                            WatchActionCatalog.phoneActions.filter { (_, labelRes) ->
                                context.getString(labelRes).contains(query, ignoreCase = true)
                            }
                        }
                        if (filteredPhoneActions.isNotEmpty()) {
                            item(key = "section_phone_hdr") {
                                Text(
                                    text = stringResource(R.string.hdr_section_phone),
                                    color = UltraOrange,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp, start = 4.dp)
                                )
                            }
                            items(filteredPhoneActions, key = { it.first }) { (actKey, labelRes) ->
                                ActionRadioRow(
                                    label = stringResource(labelRes),
                                    subtitle = null,
                                    selected = currentAct == actKey
                                ) {
                                    updateWatchMapping(activeGesture, actKey)
                                    pickingGesture = null
                                }
                            }
                        }

                        // 2. SECTION: WATCH ACTIONS (grouped by categories)
                        val watchSections = listOf(
                            context.getString(R.string.cat_shealth) to WatchActionCatalog.shealthActions,
                            context.getString(R.string.cat_samsung) to WatchActionCatalog.samsungActions,
                            context.getString(R.string.cat_dev) to WatchActionCatalog.devActions,
                            context.getString(R.string.cat_utility) to WatchActionCatalog.utilityActions,
                            context.getString(R.string.cat_ha) to WatchActionCatalog.homeAssistantActions,
                            context.getString(R.string.cat_media) to WatchActionCatalog.mediaActions
                        ).mapNotNull { (catTitle, actions) ->
                            val filtered = if (query.isEmpty()) {
                                actions
                            } else {
                                actions.filter { (_, labelRes) ->
                                    context.getString(labelRes).contains(query, ignoreCase = true)
                                }
                            }
                            if (filtered.isEmpty()) null else catTitle to filtered
                        }

                        if (watchSections.isNotEmpty()) {
                            item(key = "section_watch_hdr") {
                                Text(
                                    text = stringResource(R.string.hdr_section_watch),
                                    color = UltraOrange,
                                    fontWeight = FontWeight.Black,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(top = 18.dp, bottom = 4.dp, start = 4.dp)
                                )
                            }

                            for ((catTitle, actions) in watchSections) {
                                item(key = "hdr_$catTitle") {
                                    Text(
                                        text = catTitle,
                                        color = palette.textSecondary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp)
                                    )
                                }
                                items(actions, key = { it.first }) { (actKey, labelRes) ->
                                    ActionRadioRow(
                                        label = stringResource(labelRes),
                                        subtitle = null,
                                        selected = currentAct == actKey
                                    ) {
                                        updateWatchMapping(activeGesture, actKey)
                                        pickingGesture = null
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DevOptionsFullScreen(
    isBetaEnabled: Boolean,
    onToggleBeta: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val palette = LocalPalette.current
    Scaffold(
        containerColor = palette.bg,
        topBar = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(palette.bg)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text(stringResource(R.string.btn_back), color = palette.textPrimary)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.dev_options_title),
                    color = palette.textPrimary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.cardBg)
                    .border(1.dp, palette.border, RoundedCornerShape(18.dp))
                    .padding(14.dp)
            ) {
                WatchToggleRow(
                    title = stringResource(R.string.beta_channel_title),
                    subtitle = stringResource(R.string.beta_channel_desc),
                    checked = isBetaEnabled,
                    enabled = true,
                    onToggle = { onToggleBeta(!isBetaEnabled) }
                )
            }
        }
    }
}

@Composable
private fun StatusPill(
    label: String,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit = {}
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(palette.cardBg)
            .border(1.dp, if (active) activeColor.copy(alpha = 0.5f) else palette.border, RoundedCornerShape(50))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) activeColor else palette.textSecondary)
        )
        Text(
            text = label,
            fontSize = 12.sp,
            color = if (active) palette.textPrimary else palette.textSecondary,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun DiagBanner(
    title: String,
    subtitle: String,
    badge: String,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(palette.redBannerBg)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Text(subtitle, color = Color.White.copy(alpha = 0.88f), fontSize = 11.sp)
        }
        Spacer(modifier = Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.35f))
                .padding(horizontal = 6.dp, vertical = 3.dp)
        ) {
            Text(badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun WatchSectionCollapsible(
    iconRes: Int,
    title: String,
    subtitle: String,
    isHighlighted: Boolean,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    val palette = LocalPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(palette.cardBg)
            .border(1.dp, palette.border, RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { onToggle() }
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Image(
                    painter = painterResource(id = iconRes),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        color = palette.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = subtitle,
                        color = if (isHighlighted) UltraOrange else palette.textSecondary,
                        fontSize = 11.sp,
                        fontWeight = if (isHighlighted) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
            CollapseChevron(isExpanded = isExpanded)
        }

        AnimatedVisibility(visible = isExpanded) {
            Column(
                modifier = Modifier.padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun GestureMappingRow(
    gestureName: String,
    actionLabel: String,
    isConfigured: Boolean,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.rowBg)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = gestureName,
                color = if (enabled) palette.textPrimary else palette.textSecondary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = actionLabel,
                color = if (isConfigured) UltraOrange else palette.textSecondary,
                fontSize = 12.sp,
                fontWeight = if (isConfigured) FontWeight.SemiBold else FontWeight.Normal
            )
        }
        ForwardChevron()
    }
}

@Composable
private fun WatchToggleRow(
    title: String,
    subtitle: String,
    subtitleColor: Color? = null,
    checked: Boolean,
    isUnknownState: Boolean = false,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    onToggle: () -> Unit
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.rowBg)
            .clickable(enabled = enabled && !isLoading) { onToggle() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(
                text = title,
                color = if (isUnknownState || !enabled) palette.textSecondary else palette.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = subtitle,
                color = subtitleColor ?: palette.textSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }
        if (isLoading) {
            SwitchLoadingSpinner(color = UltraOrange)
        } else if (isUnknownState) {
            Box(
                modifier = Modifier
                    .width(38.dp)
                    .height(22.dp)
                    .clip(CircleShape)
                    .background(palette.border.copy(alpha = 0.65f))
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.Center)
                        .clip(CircleShape)
                        .background(palette.textSecondary.copy(alpha = 0.45f))
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .width(38.dp)
                    .height(22.dp)
                    .clip(CircleShape)
                    .background(if (checked) UltraOrange else palette.border)
                    .padding(3.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .align(if (checked) Alignment.CenterEnd else Alignment.CenterStart)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun ThemeTripleSwitchRow(
    title: String,
    subtitle: String,
    selectedMode: Int, // 0 = Light, 1 = Auto, 2 = Dark
    onSelectMode: (Int) -> Unit
) {
    val palette = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.rowBg)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(
                text = title,
                color = palette.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Text(
                text = subtitle,
                color = palette.textSecondary,
                fontSize = 11.sp,
                lineHeight = 15.sp
            )
        }

        // Triple Segmented Switch: [ Sun (0) | Auto (1) | Moon (2) ]
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(palette.cardBg)
                .border(1.dp, palette.border, RoundedCornerShape(50))
                .padding(3.dp)
        ) {
            // 0 = Light (Sun Icon)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 34.dp, height = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selectedMode == 0) UltraOrange else Color.Transparent)
                    .clickable { onSelectMode(0) }
            ) {
                ThemeSunIcon(color = if (selectedMode == 0) Color.White else palette.textSecondary)
            }

            // 1 = Auto (Half Sun / Half Moon or 'A' Circle Icon)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 36.dp, height = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selectedMode == 1) UltraOrange else Color.Transparent)
                    .clickable { onSelectMode(1) }
            ) {
                ThemeAutoIcon(color = if (selectedMode == 1) Color.White else palette.textSecondary)
            }

            // 2 = Dark (Moon Icon)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(width = 34.dp, height = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selectedMode == 2) UltraOrange else Color.Transparent)
                    .clickable { onSelectMode(2) }
            ) {
                ThemeMoonIcon(color = if (selectedMode == 2) Color.White else palette.textSecondary)
            }
        }
    }
}

@Composable
private fun ThemeSunIcon(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = size.minDimension * 0.24f
        val stroke = 1.7.dp.toPx()
        drawCircle(color = color, radius = r, center = Offset(cx, cy), style = Stroke(width = stroke))
        val rayIn = size.minDimension * 0.34f
        val rayOut = size.minDimension * 0.47f
        for (i in 0 until 8) {
            val rad = Math.toRadians((i * 45).toDouble())
            val cos = kotlin.math.cos(rad).toFloat()
            val sin = kotlin.math.sin(rad).toFloat()
            drawLine(
                color = color,
                start = Offset(cx + cos * rayIn, cy + sin * rayIn),
                end = Offset(cx + cos * rayOut, cy + sin * rayOut),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun ThemeAutoIcon(color: Color) {
    val stroke = 1.6.dp
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(16.dp)) {
            val r = size.minDimension * 0.44f
            drawCircle(
                color = color,
                radius = r,
                style = Stroke(width = stroke.toPx())
            )
        }
        Text(
            text = "A",
            color = color,
            fontSize = 9.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 9.sp
        )
    }
}

@Composable
private fun ThemeMoonIcon(color: Color) {
    Canvas(modifier = Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            val r = w * 0.40f
            val cx = w * 0.50f
            val cy = h * 0.50f
            addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
        }
        val cutout = androidx.compose.ui.graphics.Path().apply {
            val r = w * 0.34f
            val cx = w * 0.68f
            val cy = h * 0.34f
            addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r))
        }
        val crescent = androidx.compose.ui.graphics.Path.combine(
            androidx.compose.ui.graphics.PathOperation.Difference,
            path,
            cutout
        )
        drawPath(path = crescent, color = color)
    }
}

@Composable
private fun ActionRadioRow(
    label: String,
    subtitle: String?,
    selected: Boolean,
    iconB64: String? = null,
    onSelect: () -> Unit
) {
    val palette = LocalPalette.current
    val iconBitmap = remember(iconB64) {
        if (iconB64.isNullOrBlank()) null
        else try {
            val bytes = android.util.Base64.decode(iconB64, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (_: Exception) {
            null
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.rowBg)
            .clickable { onSelect() }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            colors = RadioButtonDefaults.colors(
                selectedColor = UltraOrange,
                unselectedColor = palette.textSecondary
            )
        )
        if (iconBitmap != null) {
            Spacer(modifier = Modifier.width(4.dp))
            Image(
                bitmap = iconBitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Spacer(modifier = Modifier.width(6.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = palette.textPrimary,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = if (selected) UltraOrange else palette.textSecondary,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun ButtonsTabIcon(color: Color) {
    Image(
        painter = painterResource(id = R.drawable.ic_launcher_foreground),
        contentDescription = null,
        modifier = Modifier.size(30.dp)
    )
}

@Composable
private fun SettingsTabIcon(color: Color) {
    Icon(
        imageVector = Icons.Rounded.Settings,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(24.dp)
    )
}

@Composable
private fun CollapseChevron(
    isExpanded: Boolean,
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val color = palette.textSecondary
    Canvas(modifier = modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val strokeWidth = 2.dp.toPx()
        if (isExpanded) {
            drawLine(color, Offset(w * 0.2f, h * 0.65f), Offset(w * 0.5f, h * 0.35f), strokeWidth, StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.35f), Offset(w * 0.8f, h * 0.65f), strokeWidth, StrokeCap.Round)
        } else {
            drawLine(color, Offset(w * 0.2f, h * 0.35f), Offset(w * 0.5f, h * 0.65f), strokeWidth, StrokeCap.Round)
            drawLine(color, Offset(w * 0.5f, h * 0.65f), Offset(w * 0.8f, h * 0.35f), strokeWidth, StrokeCap.Round)
        }
    }
}

@Composable
private fun ForwardChevron(
    modifier: Modifier = Modifier
) {
    val palette = LocalPalette.current
    val color = palette.textSecondary
    Canvas(modifier = modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val strokeWidth = 2.2.dp.toPx()
        drawLine(color, Offset(w * 0.35f, h * 0.2f), Offset(w * 0.7f, h * 0.5f), strokeWidth, StrokeCap.Round)
        drawLine(color, Offset(w * 0.7f, h * 0.5f), Offset(w * 0.35f, h * 0.8f), strokeWidth, StrokeCap.Round)
    }
}

@Composable
private fun SwitchLoadingSpinner(
    modifier: Modifier = Modifier,
    color: Color = Color.White
) {
    val infiniteTransition = rememberInfiniteTransition(label = "SwitchSpinner")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing)
        ),
        label = "SpinnerAngle"
    )
    Canvas(modifier = modifier.size(16.dp)) {
        drawArc(
            color = color,
            startAngle = angle,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
