package com.aaronhrndz.ultraremapper.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.*
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import com.aaronhrndz.ultraremapper.HapticFeedbackHelper
import com.aaronhrndz.ultraremapper.R
import com.aaronhrndz.ultraremapper.SwitchLoadingSpinner
import com.aaronhrndz.ultraremapper.adb.LocalAdbClient
import kotlin.concurrent.thread

private val SlateCard = Color(0xFF1F232A)
private val UltraOrange = Color(0xFFFF5500)
private val GreenOk = Color(0xFF4CAF50)
private val RedAlert = Color(0xFFF44336)

private fun isWirelessAdbEnabledOnSystem(context: Context): Boolean {
    return try {
        val wifiAdb = Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0)
        val usbAdb = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
        wifiAdb == 1 || usbAdb == 1
    } catch (_: Exception) {
        false
    }
}

private fun openWirelessDebuggingSettings(context: Context) {
    val devIntents = listOf(
        Intent().setClassName(
            "com.google.android.apps.wearable.settings",
            "com.samsung.android.clockwork.settings.developer.wirelessadb.WirelessDebuggingActivity"
        ),
        Intent().setClassName(
            "com.google.android.apps.wearable.settings",
            "com.samsung.android.clockwork.settings.developer.StDevOptionsActivity"
        ),
        Intent("android.settings.WIRELESS_DEBUGGING_SETTINGS"),
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
        Intent("com.google.android.clockwork.settings.DEVELOPER_OPTIONS")
    )
    for (intent in devIntents) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {}
    }
}

@Composable
fun AdbWizardScreen(
    onPairingSuccess: (LocalAdbClient) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()

    val initiallyPaired = remember { LocalAdbClient.isPaired(context) }
    // If already paired, skip directly to Step 3 (Connecting)
    var currentStep by remember { mutableStateOf(if (initiallyPaired) 3 else 1) }

    var isAdbActive by remember { mutableStateOf(isWirelessAdbEnabledOnSystem(context)) }
    var pinText by remember { mutableStateOf("") }
    var pairingPortText by remember { mutableStateOf("") }
    var connectPortText by remember { mutableStateOf("") }

    var isConnecting by remember { mutableStateOf(false) }
    var isPairing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var connectedClient by remember { mutableStateOf<LocalAdbClient?>(null) }

    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(currentStep) {
        columnState.scrollToItem(0)
    }

    // Dual Auto-Inference: Accessibility for PIN + mDNS for live ports
    DisposableEffect(currentStep) {
        var discovery: com.aaronhrndz.ultraremapper.adb.AdbMdnsDiscovery? = null
        if (currentStep >= 2 && connectedClient == null) {
            discovery = com.aaronhrndz.ultraremapper.adb.AdbMdnsDiscovery(context)
            discovery.startDiscovery(wantPairing = (currentStep == 2)) { isPairingPort, port ->
                if (isPairingPort) {
                    pairingPortText = port.toString()
                } else {
                    connectPortText = port.toString()
                }
            }
        }
        onDispose {
            discovery?.stopDiscovery()
        }
    }

    // Re-check system ADB status & capture ports from Accessibility when returning from Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isAdbActive = isWirelessAdbEnabledOnSystem(context)
                val pin = com.aaronhrndz.ultraremapper.BottomButtonService.capturedPin
                if (!pin.isNullOrBlank()) {
                    pinText = pin
                }
                val pairP = com.aaronhrndz.ultraremapper.BottomButtonService.capturedPairingPort
                if (pairP != null && pairP > 0 && pairingPortText.isEmpty()) {
                    pairingPortText = pairP.toString()
                }
                val connP = com.aaronhrndz.ultraremapper.BottomButtonService.capturedConnectPort
                if (connP != null && connP > 0 && connectPortText.isEmpty()) {
                    connectPortText = connP.toString()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        com.aaronhrndz.ultraremapper.BottomButtonService.isReadingAdbScreen = true
        onDispose {
            com.aaronhrndz.ultraremapper.BottomButtonService.isReadingAdbScreen = false
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Step 2 Action: Pair via SPAKE2 on local loopback
    val pairAction: () -> Unit = {
        keyboardController?.hide()
        isPairing = true
        errorMessage = null
        val port = pairingPortText.toIntOrNull() ?: 0

        thread {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            val client = LocalAdbClient("127.0.0.1", port)
            val success = client.pair(context, port, pinText)

            mainHandler.post {
                isPairing = false
                if (success) {
                    HapticFeedbackHelper.playButtonHaptic(context)
                    // Advance directly to Step 3 for connection
                    currentStep = 3
                } else {
                    errorMessage = context.getString(R.string.adb_wiz_err_pair)
                }
            }
        }
    }

    // Step 3 Action: Connect via ADB TLS/TCP on local loopback
    val connectAction: () -> Unit = {
        keyboardController?.hide()
        isConnecting = true
        errorMessage = null
        val port = connectPortText.toIntOrNull() ?: 0

        thread {
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            if (port <= 0) {
                mainHandler.post {
                    isConnecting = false
                    errorMessage = context.getString(R.string.adb_wiz_err_enter_port)
                }
                return@thread
            }

            val client = LocalAdbClient("127.0.0.1", port)
            val ok = client.connect(context, timeoutMs = 8000L)

            mainHandler.post {
                isConnecting = false
                if (ok) {
                    HapticFeedbackHelper.playButtonHaptic(context)
                    connectedClient = client
                } else {
                    errorMessage = context.getString(R.string.adb_wiz_err_connect, port)
                }
            }
        }
    }

    ScreenScaffold(scrollState = columnState) { _ ->
        TransformingLazyColumn(
            state = columnState,
            contentPadding = PaddingValues(top = 32.dp, bottom = 44.dp, start = 12.dp, end = 12.dp)
        ) {
            item(key = "wizard_step_header") {
                ListHeader(
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec)
                ) {
                    val headerTitle = when {
                        connectedClient != null -> context.getString(R.string.adb_wiz_hdr_connected)
                        currentStep == 1 -> context.getString(R.string.adb_wiz_hdr_step1)
                        currentStep == 2 -> context.getString(R.string.adb_wiz_hdr_step2)
                        else -> context.getString(R.string.adb_wiz_hdr_step3)
                    }
                    Text(
                        text = headerTitle,
                        color = if (connectedClient != null) GreenOk else UltraOrange,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            if (connectedClient != null) {
                // CONFIRMATION SUCCESS VIEW
                item(key = "success_card") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF162319))
                            .border(1.5.dp, GreenOk, RoundedCornerShape(16.dp))
                            .padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = context.getString(R.string.adb_wiz_connected_title),
                            color = GreenOk,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = context.getString(R.string.adb_wiz_connected_desc, connectedClient?.port ?: 0),
                            color = Color.White,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                item(key = "success_continue_btn") {
                    Button(
                        onClick = {
                            connectedClient?.let { onPairingSuccess(it) }
                        },
                        label = {
                            Text(
                                text = context.getString(R.string.adb_wiz_continue_settings),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = GreenOk,
                            contentColor = Color.White
                        ),
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }
            } else if (currentStep == 1) {
                // STEP 1: Enable Wireless Debugging
                item(key = "s1_instructions") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF181B22))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = context.getString(R.string.adb_wiz_s1_1),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = context.getString(R.string.adb_wiz_s1_2),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = context.getString(R.string.adb_wiz_s1_3),
                            color = Color(0xFFD5DCE6),
                            fontSize = 11.sp
                        )
                    }
                }

                item(key = "s1_open_btn") {
                    Button(
                        onClick = {
                            com.aaronhrndz.ultraremapper.BottomButtonService.clearCapturedAdb()
                            pinText = ""
                            pairingPortText = ""
                            connectPortText = ""
                            HapticFeedbackHelper.playUiTap(context)
                            openWirelessDebuggingSettings(context)
                        },
                        label = {
                            Text(
                                text = context.getString(R.string.adb_wiz_open_wireless),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().basicMarquee()
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UltraOrange,
                            contentColor = Color.White
                        ),
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }

                if (LocalAdbClient.isPaired(context)) {
                    item(key = "s1_direct_connect_btn") {
                        Button(
                            onClick = {
                                currentStep = 3
                            },
                            label = {
                                Text(
                                    text = context.getString(R.string.adb_wiz_already_paired),
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 1,
                                    modifier = Modifier.fillMaxWidth().basicMarquee()
                                )
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = SlateCard,
                                contentColor = Color.White
                            ),
                            transformation = SurfaceTransformation(transformationSpec),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp)
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                }

                item(key = "s1_next_btn") {
                    Button(
                        enabled = isAdbActive,
                        onClick = {
                            if (isAdbActive) {
                                HapticFeedbackHelper.playUiTap(context)
                                currentStep = 2
                            }
                        },
                        label = {
                            Text(
                                text = if (isAdbActive) context.getString(R.string.adb_wiz_next_pair) else context.getString(R.string.adb_wiz_enable_first),
                                color = if (isAdbActive) Color.White else Color(0xFF7A8392),
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                modifier = Modifier.fillMaxWidth().basicMarquee()
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlateCard,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF16191F),
                            disabledContentColor = Color(0xFF6A717E)
                        ),
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }
            } else if (currentStep == 2) {
                // STEP 2: PAIRING (PIN + Pairing Port)
                item(key = "s2_pairing_card") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF181B22))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = context.getString(R.string.adb_wiz_pin_label),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        BasicTextField(
                            value = pinText,
                            onValueChange = { newValue ->
                                pinText = newValue.filter { it.isDigit() }.take(6)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.NumberPassword,
                                imeAction = ImeAction.Next
                            ),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            cursorBrush = SolidColor(UltraOrange),
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(focusRequester),
                            decorationBox = { innerTextField ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF222733))
                                        .border(
                                            1.dp,
                                            if (pinText.isNotEmpty()) UltraOrange else Color(0xFF383D47),
                                            RoundedCornerShape(10.dp)
                                        )
                                ) {
                                    if (pinText.isEmpty()) {
                                        Text(
                                            text = context.getString(R.string.adb_wiz_pin_hint),
                                            color = Color(0xFF7A8392),
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Text(
                            text = context.getString(R.string.adb_wiz_pair_port_label),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        BasicTextField(
                            value = pairingPortText,
                            onValueChange = { newValue ->
                                pairingPortText = newValue.filter { it.isDigit() }.take(5)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    if (pinText.length == 6 && pairingPortText.isNotEmpty()) {
                                        pairAction()
                                    }
                                }
                            ),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            cursorBrush = SolidColor(UltraOrange),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { innerTextField ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF222733))
                                        .border(
                                            1.dp,
                                            if (pairingPortText.isNotEmpty()) UltraOrange else Color(0xFF383D47),
                                            RoundedCornerShape(10.dp)
                                        )
                                ) {
                                    if (pairingPortText.isEmpty()) {
                                        Text(
                                            text = context.getString(R.string.adb_wiz_pair_port_hint),
                                            color = Color(0xFF7A8392),
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        errorMessage?.let { err ->
                            Text(
                                text = err,
                                color = RedAlert,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item(key = "s2_pair_btn") {
                    val canPair = pinText.length == 6 && pairingPortText.isNotEmpty() && !isPairing
                    Button(
                        enabled = canPair,
                        onClick = pairAction,
                        label = {
                            if (isPairing) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SwitchLoadingSpinner(modifier = Modifier.padding(end = 6.dp))
                                    Text(context.getString(R.string.adb_wiz_pairing), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text(
                                    text = context.getString(R.string.adb_wiz_pair_btn),
                                    color = if (canPair) Color.White else Color(0xFF7A8392),
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UltraOrange,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF16191F),
                            disabledContentColor = Color(0xFF6A717E)
                        ),
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }

                item(key = "s2_open_settings_btn") {
                    Button(
                        onClick = {
                            com.aaronhrndz.ultraremapper.BottomButtonService.clearCapturedAdb()
                            pinText = ""
                            pairingPortText = ""
                            openWirelessDebuggingSettings(context)
                        },
                        label = {
                            Text(
                                text = context.getString(R.string.adb_wiz_open_debugging),
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlateCard,
                            contentColor = Color.White
                        ),
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }
            } else {
                // STEP 3: CONNECTION (ADB Connect Port)
                item(key = "s3_connect_card") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF181B22))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = context.getString(R.string.adb_wiz_device_paired),
                            color = GreenOk,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        Text(
                            text = context.getString(R.string.adb_wiz_connect_port_label),
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )

                        BasicTextField(
                            value = connectPortText,
                            onValueChange = { newValue ->
                                connectPortText = newValue.filter { it.isDigit() }.take(5)
                            },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    keyboardController?.hide()
                                    if (connectPortText.isNotEmpty()) {
                                        connectAction()
                                    }
                                }
                            ),
                            textStyle = TextStyle(
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            ),
                            cursorBrush = SolidColor(UltraOrange),
                            modifier = Modifier.fillMaxWidth(),
                            decorationBox = { innerTextField ->
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color(0xFF222733))
                                        .border(
                                            1.dp,
                                            if (connectPortText.isNotEmpty()) UltraOrange else Color(0xFF383D47),
                                            RoundedCornerShape(10.dp)
                                        )
                                ) {
                                    if (connectPortText.isEmpty()) {
                                        Text(
                                            text = context.getString(R.string.adb_wiz_connect_port_hint),
                                            color = Color(0xFF7A8392),
                                            fontSize = 12.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )

                        Text(
                            text = context.getString(R.string.adb_wiz_connect_port_note),
                            color = Color(0xFF9EA7B8),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth()
                        )

                        errorMessage?.let { err ->
                            Text(
                                text = err,
                                color = RedAlert,
                                style = MaterialTheme.typography.bodySmall,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item(key = "s3_connect_btn") {
                    val canConnect = connectPortText.isNotEmpty() && !isConnecting
                    Button(
                        enabled = canConnect,
                        onClick = connectAction,
                        label = {
                            if (isConnecting) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.Center,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    SwitchLoadingSpinner(modifier = Modifier.padding(end = 6.dp))
                                    Text(context.getString(R.string.adb_wiz_connecting), color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Text(
                                    text = context.getString(R.string.adb_wiz_connect_btn),
                                    color = if (canConnect) Color.White else Color(0xFF7A8392),
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = UltraOrange,
                            contentColor = Color.White,
                            disabledContainerColor = Color(0xFF16191F),
                            disabledContentColor = Color(0xFF6A717E)
                        ),
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }

                item(key = "s3_open_settings_btn") {
                    Button(
                        onClick = {
                            com.aaronhrndz.ultraremapper.BottomButtonService.capturedConnectPort = null
                            connectPortText = ""
                            openWirelessDebuggingSettings(context)
                        },
                        label = {
                            Text(
                                text = context.getString(R.string.adb_wiz_open_debugging),
                                color = Color.White,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SlateCard,
                            contentColor = Color.White
                        ),
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }

                item(key = "s3_re_pair_btn") {
                    Button(
                        onClick = {
                            currentStep = 2
                        },
                        label = {
                            Text(
                                text = context.getString(R.string.adb_wiz_re_pair),
                                color = Color(0xFF9EA7B8),
                                fontSize = 11.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color.Transparent,
                            contentColor = Color(0xFF9EA7B8)
                        ),
                        transformation = SurfaceTransformation(transformationSpec),
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }
        }
    }
}
