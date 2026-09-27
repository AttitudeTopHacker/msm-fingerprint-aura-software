/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.msm.movin.auralock.biometrics.BiometricAuthPrompt
import com.msm.movin.auralock.crypto.CryptoManager
import com.msm.movin.auralock.model.ConnectionProgressState
import com.msm.movin.auralock.model.ConnectionStep
import com.msm.movin.auralock.model.ParsedQrData
import com.msm.movin.auralock.network.AuraLockClient
import com.msm.movin.auralock.network.ChallengeEvent
import com.msm.movin.auralock.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

enum class ScreenState {
    DASHBOARD,
    QR_SCANNER
}

class MainActivity : FragmentActivity() {

    private val cryptoManager = CryptoManager()
    private lateinit var biometricPrompt: BiometricAuthPrompt
    private var client: AuraLockClient? = null

    private val prefs by lazy {
        getSharedPreferences("auralock_paired_prefs", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        biometricPrompt = BiometricAuthPrompt(this)

        setContent {
            AuraLockTheme {
                MainAppScreen()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainAppScreen() {
        var currentScreen by remember { mutableStateOf(ScreenState.DASHBOARD) }
        var isConnected by remember { mutableStateOf(false) }

        var autoConnectEnabled by remember { mutableStateOf(prefs.getBoolean("auto_connect", true)) }
        var hapticsEnabled by remember { mutableStateOf(prefs.getBoolean("haptics_enabled", true)) }

        val savedPcName = remember { prefs.getString("pc_name", "Salman-PC") ?: "Salman-PC" }
        val savedPcIp = remember { prefs.getString("pc_ip", "10.132.234.46") ?: "10.132.234.46" }
        val savedPcPort = remember { prefs.getInt("pc_port", 47890) }
        var isPaired by remember { mutableStateOf(prefs.getBoolean("is_paired", true)) }

        var pcName by remember { mutableStateOf(savedPcName) }
        var pcIp by remember { mutableStateOf(savedPcIp) }
        var pcPort by remember { mutableStateOf(savedPcPort) }

        var statusMessage by remember {
            mutableStateOf(if (isPaired) "Paired with $pcName • Ready to connect" else "Ready to link with PC")
        }

        var showManualDialog by remember { mutableStateOf(false) }
        var showBluetoothSheet by remember { mutableStateOf(false) }
        var showSettingsDialog by remember { mutableStateOf(false) }

        var connectionProgress by remember {
            mutableStateOf(ConnectionProgressState())
        }

        // Auto-connect on launch if enabled
        LaunchedEffect(Unit) {
            if (autoConnectEnabled && isPaired && !isConnected) {
                delay(300)
                connectToDaemon(pcIp, pcPort, pcName) { success, msg ->
                    isConnected = success
                    statusMessage = msg
                }
            }
        }

        when (currentScreen) {
            ScreenState.QR_SCANNER -> {
                QrScannerView(
                    onQrCodeDetected = { qrData ->
                        currentScreen = ScreenState.DASHBOARD
                        initiatePairing(qrData) { state ->
                            connectionProgress = state
                            if (state.step == ConnectionStep.SECURE_TUNNEL_ACTIVE) {
                                isConnected = true
                                isPaired = true
                                pcName = qrData.pcName
                                pcIp = qrData.ip
                                pcPort = qrData.port
                                statusMessage = "Paired & Listening for Lockscreen"

                                prefs.edit()
                                    .putString("pc_name", qrData.pcName)
                                    .putString("pc_ip", qrData.ip)
                                    .putInt("pc_port", qrData.port)
                                    .putBoolean("is_paired", true)
                                    .apply()
                            } else if (state.errorMessage != null) {
                                isConnected = false
                                statusMessage = state.errorMessage
                            }
                        }
                    },
                    onManualInputClicked = {
                        currentScreen = ScreenState.DASHBOARD
                        showManualDialog = true
                    },
                    onClose = {
                        currentScreen = ScreenState.DASHBOARD
                    }
                )
            }

            ScreenState.DASHBOARD -> {
                DashboardView(
                    isConnected = isConnected,
                    isPaired = isPaired,
                    statusMessage = statusMessage,
                    pcName = pcName,
                    pcIp = pcIp,
                    onConnectDirect = {
                        connectToDaemon(pcIp, pcPort, pcName) { success, msg ->
                            isConnected = success
                            statusMessage = msg
                        }
                    },
                    onScanQrClicked = { currentScreen = ScreenState.QR_SCANNER },
                    onManualSetupClicked = { showManualDialog = true },
                    onShowBluetoothInfo = { showBluetoothSheet = true },
                    onOpenSettings = { showSettingsDialog = true },
                    onDisconnectClicked = {
                        client?.disconnect()
                        client = null
                        isConnected = false
                        statusMessage = "Disconnected from PC"
                        Toast.makeText(this@MainActivity, "Disconnected from PC", Toast.LENGTH_SHORT).show()
                    },
                    onTestBiometrics = {
                        testBiometricsPrompt()
                    }
                )
            }
        }

        // Connection Progress Modal
        ConnectionProgressDialog(
            state = connectionProgress,
            onCancel = {
                client?.disconnect()
                client = null
                connectionProgress = ConnectionProgressState()
            },
            onDismissError = {
                connectionProgress = ConnectionProgressState()
            }
        )

        // Manual Setup Dialog
        if (showManualDialog) {
            ManualPairDialog(
                initialIp = pcIp,
                onConfirm = { qrData ->
                    showManualDialog = false
                    initiatePairing(qrData) { state ->
                        connectionProgress = state
                        if (state.step == ConnectionStep.SECURE_TUNNEL_ACTIVE) {
                            isConnected = true
                            isPaired = true
                            pcName = qrData.pcName
                            pcIp = qrData.ip
                            pcPort = qrData.port
                            statusMessage = "Paired & Listening for Lockscreen"

                            prefs.edit()
                                .putString("pc_name", qrData.pcName)
                                .putString("pc_ip", qrData.ip)
                                .putInt("pc_port", qrData.port)
                                .putBoolean("is_paired", true)
                                .apply()
                        } else if (state.errorMessage != null) {
                            isConnected = false
                            statusMessage = state.errorMessage
                        }
                    }
                },
                onSwitchToQr = {
                    showManualDialog = false
                    currentScreen = ScreenState.QR_SCANNER
                },
                onDismiss = { showManualDialog = false }
            )
        }

        // Bluetooth & Dual-Channel Info Sheet
        if (showBluetoothSheet) {
            BluetoothInfoSheet(
                isConnected = isConnected,
                currentIp = pcIp,
                onDismiss = { showBluetoothSheet = false }
            )
        }

        // Settings Dialog
        if (showSettingsDialog) {
            SettingsDialog(
                pcName = pcName,
                pcIp = pcIp,
                pcPort = pcPort,
                isPaired = isPaired,
                isConnected = isConnected,
                autoConnectEnabled = autoConnectEnabled,
                hapticsEnabled = hapticsEnabled,
                onAutoConnectToggled = { enabled ->
                    autoConnectEnabled = enabled
                    prefs.edit().putBoolean("auto_connect", enabled).apply()
                },
                onHapticsToggled = { enabled ->
                    hapticsEnabled = enabled
                    prefs.edit().putBoolean("haptics_enabled", enabled).apply()
                },
                onTestPing = { onResult ->
                    lifecycleScope.launch {
                        val startTime = System.currentTimeMillis()
                        val testClient = client ?: AuraLockClient(pcIp, pcPort)
                        var didConnectLocally = false
                        if (client == null) {
                            didConnectLocally = testClient.connect()
                        }
                        val pingOk = testClient.sendPing()
                        val rtt = System.currentTimeMillis() - startTime
                        if (didConnectLocally) {
                            testClient.disconnect()
                        }
                        onResult(if (pingOk) rtt else -1L)
                    }
                },
                onUnpairClicked = {
                    client?.disconnect()
                    client = null
                    isConnected = false
                    isPaired = false
                    statusMessage = "Ready to link with PC"
                    prefs.edit().clear().apply()
                    Toast.makeText(this@MainActivity, "Unpaired from PC", Toast.LENGTH_SHORT).show()
                },
                onDismiss = { showSettingsDialog = false }
            )
        }
    }

    @Composable
    private fun DashboardView(
        isConnected: Boolean,
        isPaired: Boolean,
        statusMessage: String,
        pcName: String,
        pcIp: String,
        onConnectDirect: () -> Unit,
        onScanQrClicked: () -> Unit,
        onManualSetupClicked: () -> Unit,
        onShowBluetoothInfo: () -> Unit,
        onOpenSettings: () -> Unit,
        onDisconnectClicked: () -> Unit,
        onTestBiometrics: () -> Unit
    ) {
        Scaffold(
            topBar = {
                AuraLockTopBar(
                    onShowBluetoothInfo = onShowBluetoothInfo,
                    onOpenSettings = onOpenSettings
                )
            },
            containerColor = Color(0xFF070A13)
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFF0B1120),
                                Color(0xFF070A13),
                                Color(0xFF030712)
                            )
                        )
                    )
                    .padding(padding)
                    .padding(horizontal = 20.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Section 1: Top Status Card
                Column(modifier = Modifier.fillMaxWidth()) {
                    StatusOverviewCard(
                        isConnected = isConnected,
                        statusMessage = statusMessage,
                        pcName = pcName,
                        pcIp = pcIp,
                        onDisconnect = onDisconnectClicked
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Section 2: Concentric Glowing Fingerprint Area
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PulseFingerprintView(
                        isConnected = isConnected,
                        onTestClick = onTestBiometrics
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Text(
                        text = if (isConnected) "Hardware Biometric Key Active"
                        else if (isPaired) "Paired with $pcName"
                        else "Ready for Pairing",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (isConnected)
                            "Touch sensor anytime your PC lockscreen activates"
                        else if (isPaired)
                            "Tap 'Connect to $pcName' to link with the active PC daemon"
                        else
                            "Tap 'Scan QR to Pair' to link with your PC terminal",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Section 3: Action Buttons & Channels Info
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (isConnected) {
                        Button(
                            onClick = onTestBiometrics,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0E7490)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Test Sensor Authentication", fontWeight = FontWeight.Bold)
                        }
                    } else if (isPaired) {
                        // Already paired -> 1-Tap Direct Reconnect to Daemon
                        Button(
                            onClick = onConnectDirect,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0284C7)
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bolt,
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                                tint = Color(0xFFFACC15)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Connect to $pcName",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = onScanQrClicked,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFE2E8F0)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF94A3B8)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Re-Pair / Scan New PC QR", fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        Button(
                            onClick = onScanQrClicked,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(54.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0284C7)
                            ),
                            elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color.White
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "Scan QR Code to Pair",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedButton(
                            onClick = onManualSetupClicked,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFFE2E8F0)
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Keyboard,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = Color(0xFF94A3B8)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Manual IP & PIN Setup", fontWeight = FontWeight.SemiBold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Bluetooth & Channels Quick Card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onShowBluetoothInfo),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = Color(0xFF818CF8),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Wi-Fi & Bluetooth Dual Channel",
                                    color = Color(0xFFE2E8F0),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color(0xFF64748B),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    Text(
                        text = "msm.movin • Copyright (c) 2022. All rights reserved.",
                        color = Color(0xFF475569),
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }

    @Composable
    private fun AuraLockTopBar(
        onShowBluetoothInfo: () -> Unit,
        onOpenSettings: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color(0xFF0B1120))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0284C7).copy(alpha = 0.2f))
                        .border(1.dp, Color(0xFF0284C7), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Security,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "AuraLock",
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                    Text(
                        text = "msm.movin zero-bloat key",
                        fontSize = 10.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(0xFF059669).copy(alpha = 0.2f))
                        .border(1.dp, Color(0xFF10B981).copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "TEE SECURE",
                        color = Color(0xFF34D399),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                IconButton(
                    onClick = onShowBluetoothInfo,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(20.dp)
                    )
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusOverviewCard(
        isConnected: Boolean,
        statusMessage: String,
        pcName: String,
        pcIp: String,
        onDisconnect: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isConnected) Color(0xFF06B6D4).copy(alpha = 0.35f) else Color(0xFF1E293B)
            )
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (isConnected) Color(0xFF10B981) else Color(0xFFEF4444))
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isConnected) "PC Connected" else "Unlinked Standby",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    if (isConnected) {
                        TextButton(
                            onClick = onDisconnect,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text("Disconnect", color = Color(0xFFEF4444), fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = statusMessage,
                    color = Color(0xFF94A3B8),
                    fontSize = 13.sp
                )

                if (isConnected) {
                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Host: $pcName", color = Color(0xFFCBD5E1), fontSize = 12.sp)
                        Text("Endpoint: $pcIp:47890", color = Color(0xFF38BDF8), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    private fun connectToDaemon(
        ip: String,
        port: Int,
        targetPcName: String,
        onResult: (Boolean, String) -> Unit
    ) {
        lifecycleScope.launch {
            val clientInstance = AuraLockClient(ip, port)
            val connected = clientInstance.connect()

            if (!connected) {
                onResult(false, "Could not reach PC at $ip:$port. Ensure 'auralock daemon' is running.")
                Toast.makeText(this@MainActivity, "Could not reach $targetPcName at $ip:$port", Toast.LENGTH_SHORT).show()
                return@launch
            }

            client = clientInstance
            onResult(true, "Connected & Listening for Lockscreen")
            Toast.makeText(this@MainActivity, "⚡ Connected to $targetPcName!", Toast.LENGTH_SHORT).show()

            // Start challenge listener loop
            clientInstance.listenForChallenges(
                onChallenge = { challenge ->
                    handleChallenge(challenge)
                },
                onDisconnect = {
                    onResult(false, "PC Disconnected")
                }
            )
        }
    }

    private fun initiatePairing(
        qrData: ParsedQrData,
        onProgress: (ConnectionProgressState) -> Unit
    ) {
        lifecycleScope.launch {
            // Step 1: Discovering PC Endpoint
            onProgress(
                ConnectionProgressState(
                    isConnecting = true,
                    step = ConnectionStep.DISCOVERING_PC,
                    pcName = qrData.pcName,
                    targetIp = "${qrData.ip}:${qrData.port}"
                )
            )
            delay(400)

            val clientInstance = AuraLockClient(qrData.ip, qrData.port)
            val connected = clientInstance.connect()

            if (!connected) {
                onProgress(
                    ConnectionProgressState(
                        isConnecting = false,
                        step = ConnectionStep.DISCOVERING_PC,
                        errorMessage = "Could not reach PC at ${qrData.ip}:${qrData.port}. Please make sure phone & PC are on the same Wi-Fi and 'auralock daemon' or 'auralock pair' is running."
                    )
                )
                return@launch
            }

            // Step 2: Android KeyStore TEE Initializing
            onProgress(
                ConnectionProgressState(
                    isConnecting = true,
                    step = ConnectionStep.INITIALIZING_KEYSTORE,
                    pcName = qrData.pcName,
                    targetIp = "${qrData.ip}:${qrData.port}"
                )
            )
            delay(350)
            val pubKey = cryptoManager.getPublicKeyHex()

            // Step 3: Exchanging Cryptographic Keys
            onProgress(
                ConnectionProgressState(
                    isConnecting = true,
                    step = ConnectionStep.EXCHANGING_KEYS,
                    pcName = qrData.pcName,
                    targetIp = "${qrData.ip}:${qrData.port}"
                )
            )
            delay(350)

            // Step 4: Verifying 6-Digit PIN Handshake
            onProgress(
                ConnectionProgressState(
                    isConnecting = true,
                    step = ConnectionStep.VERIFYING_PIN,
                    pcName = qrData.pcName,
                    targetIp = "${qrData.ip}:${qrData.port}"
                )
            )

            val phoneName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val paired = clientInstance.sendPairingRequest(phoneName, pubKey, qrData.pin)

            if (paired) {
                // Step 5: Secure Tunnel Active
                onProgress(
                    ConnectionProgressState(
                        isConnecting = true,
                        step = ConnectionStep.SECURE_TUNNEL_ACTIVE,
                        pcName = qrData.pcName,
                        targetIp = "${qrData.ip}:${qrData.port}"
                    )
                )
                delay(600)

                client = clientInstance
                onProgress(ConnectionProgressState(isConnecting = false, step = ConnectionStep.SECURE_TUNNEL_ACTIVE))
                Toast.makeText(this@MainActivity, "✨ Paired with ${qrData.pcName} successfully!", Toast.LENGTH_LONG).show()

                // Start Challenge Listener Loop
                clientInstance.listenForChallenges(
                    onChallenge = { challenge ->
                        handleChallenge(challenge)
                    },
                    onDisconnect = {
                        onProgress(
                            ConnectionProgressState(
                                isConnecting = false,
                                errorMessage = "PC Disconnected"
                            )
                        )
                    }
                )
            } else {
                clientInstance.disconnect()
                onProgress(
                    ConnectionProgressState(
                        isConnecting = false,
                        step = ConnectionStep.VERIFYING_PIN,
                        errorMessage = "Pairing PIN '${qrData.pin}' was rejected by PC. Please verify PIN on PC screen."
                    )
                )
            }
        }
    }

    private fun handleChallenge(challenge: ChallengeEvent) {
        try {
            val signature = cryptoManager.getInitializedSignature()
            biometricPrompt.promptForUnlock(
                signature = signature,
                pcName = challenge.pcName,
                onSuccess = { authedSig ->
                    lifecycleScope.launch {
                        val signablePayload =
                            "auralock:v1:${challenge.challengeId}:${challenge.nonceHex}:${challenge.timestampEpoch}".toByteArray()
                        val signedBytes = cryptoManager.signChallenge(authedSig, signablePayload)
                        val sigHex = signedBytes.joinToString("") { "%02x".format(it) }

                        client?.sendUnlockResponse(
                            challenge.challengeId,
                            sigHex,
                            System.currentTimeMillis() / 1000
                        )
                        triggerHapticFeedback()
                        Toast.makeText(this@MainActivity, "PC Unlock Approved! 🚀", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = { err ->
                    Toast.makeText(this@MainActivity, "Biometric failed: $err", Toast.LENGTH_SHORT).show()
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Biometric Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun triggerHapticFeedback() {
        if (!prefs.getBoolean("haptics_enabled", true)) return
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(70)
            }
        } catch (_: Exception) {}
    }

    private fun testBiometricsPrompt() {
        try {
            val signature = cryptoManager.getInitializedSignature()
            biometricPrompt.promptForUnlock(
                signature = signature,
                pcName = "Hardware TEE Test",
                onSuccess = {
                    triggerHapticFeedback()
                    Toast.makeText(this, "✅ Biometric Signature Verified by Hardware TEE!", Toast.LENGTH_SHORT).show()
                },
                onError = { err ->
                    Toast.makeText(this, "Biometric sensor: $err", Toast.LENGTH_SHORT).show()
                }
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Biometric test: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}

@Composable
fun AuraLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF0284C7),
            secondary = Color(0xFF06B6D4),
            background = Color(0xFF070A13),
            surface = Color(0xFF0F172A)
        ),
        content = content
    )
}
