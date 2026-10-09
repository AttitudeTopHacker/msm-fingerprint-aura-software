/*
 * Copyright (c) 2022 msm.movin. All rights reserved.
 * Proprietary software of msm.movin organization.
 */

package com.msm.movin.auralock

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.msm.movin.auralock.biometrics.BiometricAuthPrompt
import com.msm.movin.auralock.crypto.CryptoManager
import com.msm.movin.auralock.model.*
import com.msm.movin.auralock.network.AuraLockClient
import com.msm.movin.auralock.network.ChallengeEvent
import com.msm.movin.auralock.service.AuraLockService
import com.msm.movin.auralock.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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

    private val receivedFiles = mutableStateListOf<AuraDropFileItem>()
    private var transferState by mutableStateOf(AuraDropTransferState())
    private var showTransferDialogState by mutableStateOf(false)
    private var activeTransferJob: Job? = null
    private var isTransferMinimized by mutableStateOf(false)
    private var lastSentUris: List<Uri> = emptyList()
    private var latestReceivedClipboard by mutableStateOf<String?>(null)
    private var isConnectedState by mutableStateOf(false)
    private var isPairedState by mutableStateOf(false)
    private var isPcLockedState by mutableStateOf(false)
    private var isLockActionPendingState by mutableStateOf(false)
    private var isRefreshingStatusState by mutableStateOf(false)
    private var challengeRemainingSecondsState by mutableIntStateOf(0)
    private var pcNameState by mutableStateOf("")
    private var pcIpState by mutableStateOf("")
    private var pcPortState by mutableIntStateOf(47890)
    private var statusMessageState by mutableStateOf("Ready to link with PC")
    private var isBluetoothEnabledState = mutableStateOf(false)
    private var isPcBluetoothEnabledState = mutableStateOf(false)
    private var networkTransportState = mutableStateOf("Offline")

    private var autoReconnectJob: Job? = null
    private var challengeTimerJob: Job? = null
    private var activeChallengeEvent: ChallengeEvent? = null
    private var currentToast: Toast? = null

    // Direct Activity-level file picker (bypasses Compose launcher — works on ALL devices)
    private val FILE_PICKER_RC = 3001
    private val MEDIA_PICKER_RC = 3002
    private var pendingPickerCallback: ((List<Uri>) -> Unit)? = null

    private fun showSingleToast(msg: String, duration: Int = Toast.LENGTH_SHORT) {
        runOnUiThread {
            try {
                currentToast?.cancel()
                currentToast = Toast.makeText(applicationContext, msg, duration).apply { show() }
            } catch (_: Throwable) {}
        }
    }

    private fun getRequiredPermissionsList(): List<String> {
        val permissions = mutableListOf<String>()

        // Camera for QR Scanner
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.CAMERA)
        }

        // Bluetooth Scan & Connect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        // Push Notifications & Media Storage
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }
        return permissions
    }
    private var isUserManuallyDisconnected = false

    private var bluetoothReceiver: BroadcastReceiver? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private fun getNetworkTransportStatus(): String {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val capabilities = cm?.getNetworkCapabilities(network)
            if (capabilities != null) {
                when {
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Hotspot / Cell"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                    else -> "Connected"
                }
            } else {
                "Offline"
            }
        } catch (e: Exception) {
            "Offline"
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        return try {
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            adapter?.isEnabled == true
        } catch (e: SecurityException) {
            false
        } catch (e: Throwable) {
            false
        }
    }

    private fun unpairBluetoothDevices(targetPcName: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                return
            }
            val bm = getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
            val adapter = bm?.adapter ?: BluetoothAdapter.getDefaultAdapter()
            val bonded = adapter?.bondedDevices ?: return
            for (device in bonded) {
                val devName = try { device.name ?: "" } catch (_: SecurityException) { "" }
                if ((targetPcName.isNotBlank() && devName.contains(targetPcName, ignoreCase = true)) ||
                    devName.contains("AuraLock", ignoreCase = true) ||
                    devName.contains("msm", ignoreCase = true)) {
                    try {
                        val removeBondMethod = device.javaClass.getMethod("removeBond")
                        removeBondMethod.invoke(device)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun performLocalUnpairCleanUp() {
        val targetName = pcNameState
        autoReconnectJob?.cancel()
        client?.disconnect()
        client = null
        isConnectedState = false
        isPairedState = false
        isPcLockedState = false
        isRefreshingStatusState = false
        pcNameState = ""
        pcIpState = ""
        pcPortState = 47890
        isPcBluetoothEnabledState.value = false
        statusMessageState = "Ready to link with PC"
        isUserManuallyDisconnected = true

        prefs.edit()
            .putBoolean("is_paired", false)
            .putBoolean("is_pc_locked", false)
            .remove("pc_name")
            .remove("pc_ip")
            .remove("pc_port")
            .apply()

        AuraLockService.stop(this)
        unpairBluetoothDevices(targetName)
    }

    private fun setupRealtimeReceivers() {
        try {
            // Real-time Bluetooth State Receiver
            bluetoothReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                        val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                        val isEnabled = (state == BluetoothAdapter.STATE_ON)
                        val isTurningOff = (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF)

                        isBluetoothEnabledState.value = isEnabled
                        if (isEnabled && isPairedState && !isConnectedState && pcIpState.isNotBlank() && prefs.getBoolean("auto_connect", true) && !isUserManuallyDisconnected) {
                            startAutoReconnectLoop(pcIpState, pcPortState, pcNameState)
                        }
                    }
                }
            }
            val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothReceiver, btFilter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(bluetoothReceiver, btFilter)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        // Real-time Network Connectivity Callback
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            networkCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        networkTransportState.value = getNetworkTransportStatus()
                        if (isPairedState && !isConnectedState && pcIpState.isNotBlank() && prefs.getBoolean("auto_connect", true) && !isUserManuallyDisconnected) {
                            startAutoReconnectLoop(pcIpState, pcPortState, pcNameState)
                        }
                    }
                }
                override fun onLost(network: Network) {
                    runOnUiThread {
                        networkTransportState.value = getNetworkTransportStatus()
                    }
                }
                override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                    runOnUiThread {
                        networkTransportState.value = getNetworkTransportStatus()
                    }
                }
            }
            networkCallback?.let { cm?.registerNetworkCallback(request, it) }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    private fun unregisterRealtimeReceivers() {
        try {
            bluetoothReceiver?.let { unregisterReceiver(it) }
            bluetoothReceiver = null
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            networkCallback?.let { cm?.unregisterNetworkCallback(it) }
            networkCallback = null
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            isPairedState = prefs.getBoolean("is_paired", false)
            isPcLockedState = prefs.getBoolean("is_pc_locked", false)
            pcNameState = prefs.getString("pc_name", "") ?: ""
            pcIpState = prefs.getString("pc_ip", "") ?: ""
            pcPortState = prefs.getInt("pc_port", 47890)
            isBluetoothEnabledState.value = isBluetoothEnabled()
            networkTransportState.value = getNetworkTransportStatus()
            biometricPrompt = BiometricAuthPrompt(this)
            setupRealtimeReceivers()
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        setContent {
            AuraLockTheme {
                MainAppScreen()
            }
        }

        try {
            handleShareIntent(intent)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterRealtimeReceivers()
    }

    override fun onResume() {
        super.onResume()
        isBluetoothEnabledState.value = isBluetoothEnabled()
        networkTransportState.value = getNetworkTransportStatus()
        if (isPairedState && !isConnectedState && pcIpState.isNotBlank() && prefs.getBoolean("auto_connect", true) && !isUserManuallyDisconnected) {
            startAutoReconnectLoop(pcIpState, pcPortState, pcNameState)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if ((requestCode == FILE_PICKER_RC || requestCode == MEDIA_PICKER_RC) && resultCode == RESULT_OK && data != null) {
            val uris = mutableListOf<Uri>()
            val clipData = data.clipData
            if (clipData != null) {
                for (i in 0 until clipData.itemCount) {
                    clipData.getItemAt(i)?.uri?.let { uris.add(it) }
                }
            }
            if (uris.isEmpty()) {
                data.data?.let { uris.add(it) }
            }
            if (uris.isNotEmpty()) {
                pendingPickerCallback?.invoke(uris)
            }
        }
        pendingPickerCallback = null
    }

    @Suppress("DEPRECATION")
    private fun launchFilePickerDirect() {
        if (!isPairedState || pcIpState.isBlank()) {
            showSingleToast("Pair with your PC first to send files", Toast.LENGTH_LONG)
            return
        }
        pendingPickerCallback = { uris -> sendAuraDropFiles(uris) }
        var launched = false

        // Try ACTION_GET_CONTENT (works on all Android versions, no permission needed for system picker)
        if (!launched) {
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(Intent.createChooser(intent, "Select Files"), FILE_PICKER_RC)
                launched = true
            } catch (e: Throwable) { e.printStackTrace() }
        }

        // Fallback: ACTION_OPEN_DOCUMENT (SAF — always available, no permission needed)
        if (!launched) {
            try {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(intent, FILE_PICKER_RC)
                launched = true
            } catch (e: Throwable) { e.printStackTrace() }
        }

        if (!launched) {
            pendingPickerCallback = null
            showSingleToast("Could not open file picker on this device", Toast.LENGTH_SHORT)
        }
    }

    @Suppress("DEPRECATION")
    private fun launchMediaPickerDirect() {
        if (!isPairedState || pcIpState.isBlank()) {
            showSingleToast("Pair with your PC first to send photos", Toast.LENGTH_LONG)
            return
        }
        pendingPickerCallback = { uris -> sendAuraDropFiles(uris) }
        var launched = false

        // Try Android Photo Picker (Android 13+, no permission needed)
        if (!launched && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                val intent = Intent(MediaStore.ACTION_PICK_IMAGES).apply {
                    putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, 50)
                }
                startActivityForResult(intent, MEDIA_PICKER_RC)
                launched = true
            } catch (e: Throwable) { e.printStackTrace() }
        }

        // Fallback: ACTION_GET_CONTENT for images+videos
        if (!launched) {
            try {
                val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "video/*"))
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivityForResult(Intent.createChooser(intent, "Select Photos & Videos"), MEDIA_PICKER_RC)
                launched = true
            } catch (e: Throwable) { e.printStackTrace() }
        }

        // Fallback: ACTION_PICK from MediaStore
        if (!launched) {
            try {
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }
                startActivityForResult(intent, MEDIA_PICKER_RC)
                launched = true
            } catch (e: Throwable) { e.printStackTrace() }
        }

        if (!launched) {
            pendingPickerCallback = null
            showSingleToast("Could not open photo picker on this device", Toast.LENGTH_SHORT)
        }
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)

                if (uri != null) {
                    sendAuraDropFiles(listOf(uri))
                } else if (!text.isNullOrBlank()) {
                    sendAuraDropClipboard(text)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                if (!uris.isNullOrEmpty()) {
                    sendAuraDropFiles(uris)
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainAppScreen() {
        var currentScreen by remember { mutableStateOf(ScreenState.DASHBOARD) }

        var autoConnectEnabledState by remember { mutableStateOf(prefs.getBoolean("auto_connect", true)) }
        var backgroundServiceEnabledState by remember { mutableStateOf(prefs.getBoolean("background_service_enabled", true)) }
        var hapticsEnabledState by remember { mutableStateOf(prefs.getBoolean("haptics_enabled", true)) }

        var showManualDialog by remember { mutableStateOf(false) }
        var showBluetoothSheet by remember { mutableStateOf(false) }
        var showBluetoothRequiredDialog by remember { mutableStateOf(false) }
        var showPermissionOnboardingDialog by remember {
            mutableStateOf(!prefs.getBoolean("has_completed_permission_onboarding", false))
        }
        var showSettingsDialog by remember { mutableStateOf(false) }
        var showAuraDropSheet by remember { mutableStateOf(false) }

        var connectionProgress by remember {
            mutableStateOf(ConnectionProgressState())
        }

        val requestAllPermissionsLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { results ->
            isBluetoothEnabledState.value = isBluetoothEnabled()
            prefs.edit().putBoolean("has_completed_permission_onboarding", true).apply()
            showPermissionOnboardingDialog = false
            val deniedCount = results.filter { !it.value }.size
            if (deniedCount > 0) {
                Toast.makeText(this@MainActivity, "$deniedCount permission(s) not granted. You can configure them anytime in Settings.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, "✅ All permissions granted!", Toast.LENGTH_SHORT).show()
            }
            if (isPairedState && !isConnectedState && autoConnectEnabledState && pcIpState.isNotBlank()) {
                connectToDaemon(pcIpState, pcPortState, pcNameState) { success, msg ->
                    isConnectedState = success
                    statusMessageState = msg
                }
            }
        }

        val enableBluetoothLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { _ ->
            isBluetoothEnabledState.value = isBluetoothEnabled()
        }

        val requestEnableBluetooth: () -> Unit = {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                try {
                    requestAllPermissionsLauncher.launch(
                        arrayOf(
                            Manifest.permission.BLUETOOTH_CONNECT,
                            Manifest.permission.BLUETOOTH_SCAN
                        )
                    )
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            } else {
                val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                try {
                    enableBluetoothLauncher.launch(enableBtIntent)
                } catch (e: Exception) {
                    try {
                        val settingsIntent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                        startActivity(settingsIntent)
                    } catch (e2: Exception) {
                        showSingleToast("Please turn on Bluetooth in Android settings", Toast.LENGTH_LONG)
                    }
                }
            }
        }

        // Modern Photo & Media Picker (Android 13+ Photo Picker / Google Play Services backport)
        val photoPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia()
        ) { uris ->
            if (uris.isNotEmpty()) {
                sendAuraDropFiles(uris)
            }
        }

        // Storage Access Framework Document Picker (Universal across all Android versions)
        val openDocumentsLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenMultipleDocuments()
        ) { uris ->
            if (uris.isNotEmpty()) {
                sendAuraDropFiles(uris)
            }
        }

        // Standard GetMultipleContents Launcher
        val getMultipleContentsLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetMultipleContents()
        ) { uris ->
            if (uris.isNotEmpty()) {
                sendAuraDropFiles(uris)
            }
        }

        // Single Content Fallback Launcher
        val getSingleContentLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.GetContent()
        ) { uri ->
            if (uri != null) {
                sendAuraDropFiles(listOf(uri))
            }
        }

        // Universal Activity Result Fallback Launcher
        val universalPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                val selectedUris = mutableListOf<Uri>()
                val clipData = result.data?.clipData
                if (clipData != null) {
                    for (i in 0 until clipData.itemCount) {
                        clipData.getItemAt(i)?.uri?.let { uri ->
                            selectedUris.add(uri)
                        }
                    }
                }
                if (selectedUris.isEmpty()) {
                    result.data?.data?.let { uri ->
                        selectedUris.add(uri)
                    }
                }
                if (selectedUris.isNotEmpty()) {
                    sendAuraDropFiles(selectedUris)
                }
            }
        }

        // Use direct Activity-level pickers (reliable on ALL devices — bypasses Compose launcher issues)
        val launchSafeFilePicker: () -> Unit = { launchFilePickerDirect() }
        val launchSafeMediaPicker: () -> Unit = { launchMediaPickerDirect() }



        val checkBluetoothAndExecute: (() -> Unit) -> Unit = { action ->
            action()
        }

        // Check if permissions are needed on launch
        LaunchedEffect(Unit) {
            val missing = getRequiredPermissionsList()
            if (missing.isNotEmpty() && !prefs.getBoolean("has_completed_permission_onboarding", false)) {
                showPermissionOnboardingDialog = true
            }
        }

        // Auto-connect on launch if enabled and device is paired
        LaunchedEffect(Unit) {
            if (autoConnectEnabledState && isPairedState && !isConnectedState && pcIpState.isNotBlank()) {
                delay(300)
                connectToDaemon(pcIpState, pcPortState, pcNameState) { success, msg ->
                    isConnectedState = success
                    statusMessageState = msg
                }
            }
        }

        val performUnpair: () -> Unit = {
            lifecycleScope.launch {
                try {
                    if (isConnectedState && client != null) {
                        client?.sendUnpairRequest()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                performLocalUnpairCleanUp()
                Toast.makeText(this@MainActivity, "Device Unpaired Successfully", Toast.LENGTH_SHORT).show()
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
                                isConnectedState = true
                                isPairedState = true
                                pcNameState = qrData.pcName
                                pcIpState = qrData.ip
                                pcPortState = qrData.port
                                statusMessageState = "Paired & Listening for Lockscreen"

                                prefs.edit()
                                    .putString("pc_name", qrData.pcName)
                                    .putString("pc_ip", qrData.ip)
                                    .putInt("pc_port", qrData.port)
                                    .putBoolean("is_paired", true)
                                    .apply()
                            } else if (state.errorMessage != null) {
                                isConnectedState = false
                                statusMessageState = state.errorMessage
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
                val performLockPc: () -> Unit = {
                    lifecycleScope.launch {
                        if (isLockActionPendingState) return@launch
                        if (!isPairedState || pcIpState.isBlank()) {
                            Toast.makeText(this@MainActivity, "No PC is paired yet", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        isLockActionPendingState = true
                        var activeClient = client
                        if (activeClient == null || !isConnectedState) {
                            Toast.makeText(this@MainActivity, "Connecting to lock PC...", Toast.LENGTH_SHORT).show()
                            val newClient = AuraLockClient(pcIpState, pcPortState)
                            if (newClient.connect()) {
                                client = newClient
                                isConnectedState = true
                                activeClient = newClient
                                AuraLockService.start(this@MainActivity, pcNameState)
                                listenToClientEvents(newClient, pcIpState, pcPortState, pcNameState)
                            }
                        }
                        val targetClient = activeClient
                        if (targetClient != null) {
                            val sent = targetClient.sendRemoteLock()
                            if (sent) {
                                // Wait up to 4 seconds for PC to confirm lock state via LockStateChanged
                                val startTime = System.currentTimeMillis()
                                while (System.currentTimeMillis() - startTime < 4000) {
                                    if (isPcLockedState) {
                                        break
                                    }
                                    delay(100)
                                }

                                if (isPcLockedState) {
                                    triggerHapticFeedback()
                                    Toast.makeText(this@MainActivity, "🔒 PC Screen Locked", Toast.LENGTH_SHORT).show()
                                } else {
                                    // PC did not confirm lock within timeout -> revert/remain unlocked!
                                    isPcLockedState = false
                                    prefs.edit().putBoolean("is_pc_locked", false).apply()
                                    triggerHapticFeedback()
                                    Toast.makeText(this@MainActivity, "⚠️ PC Lock unconfirmed. PC remains unlocked.", Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                isPcLockedState = false
                                prefs.edit().putBoolean("is_pc_locked", false).apply()
                                Toast.makeText(this@MainActivity, "Failed to send lock signal to PC", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            isPcLockedState = false
                            prefs.edit().putBoolean("is_pc_locked", false).apply()
                            Toast.makeText(this@MainActivity, "Cannot reach PC to lock", Toast.LENGTH_SHORT).show()
                        }
                        isLockActionPendingState = false
                    }
                }

                val performUnlockPc: () -> Unit = {
                    lifecycleScope.launch {
                        if (isLockActionPendingState) return@launch
                        if (!isPairedState || pcIpState.isBlank()) {
                            Toast.makeText(this@MainActivity, "No PC is paired yet", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        if (!isPcLockedState) {
                            Toast.makeText(this@MainActivity, "💻 PC is already UNLOCKED", Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        isLockActionPendingState = true
                        var activeClient = client
                        if (activeClient == null || !isConnectedState) {
                            val newClient = AuraLockClient(pcIpState, pcPortState)
                            if (newClient.connect()) {
                                client = newClient
                                isConnectedState = true
                                activeClient = newClient
                                AuraLockService.start(this@MainActivity, pcNameState)
                                listenToClientEvents(newClient, pcIpState, pcPortState, pcNameState)
                            }
                        }
                        val targetClient = activeClient
                        if (targetClient != null && isConnectedState) {
                            val sent = targetClient.sendUnlockTrigger()
                            if (!sent) {
                                testBiometricsPrompt()
                            }
                        } else {
                            Toast.makeText(this@MainActivity, "Cannot reach PC to unlock", Toast.LENGTH_SHORT).show()
                        }
                        delay(500)
                        isLockActionPendingState = false
                    }
                }

                val performRefreshStatus: () -> Unit = {
                    lifecycleScope.launch {
                        triggerHapticFeedback()
                        isRefreshingStatusState = true
                        if (!isPairedState || pcIpState.isBlank()) {
                            Toast.makeText(this@MainActivity, "No PC paired yet. Tap 'Scan QR' to link.", Toast.LENGTH_SHORT).show()
                            isRefreshingStatusState = false
                            return@launch
                        }

                        // If a challenge is already waiting/active, immediately re-trigger biometric prompt on tap!
                        val pendingChallenge = activeChallengeEvent
                        if (challengeRemainingSecondsState > 0 && pendingChallenge != null) {
                            isRefreshingStatusState = false
                            handleChallenge(pendingChallenge)
                            return@launch
                        }

                        isUserManuallyDisconnected = false
                        val targetName = if (pcNameState.isNotBlank()) pcNameState else "PC"

                        val activeClient = client
                        if (activeClient == null || !isConnectedState) {
                            statusMessageState = "🔄 Reconnecting to $targetName & checking status..."
                            Toast.makeText(this@MainActivity, "🔄 Reconnecting to $targetName...", Toast.LENGTH_SHORT).show()
                            connectToDaemon(pcIpState, pcPortState, pcNameState) { success, msg ->
                                isConnectedState = success
                                isRefreshingStatusState = false
                                if (success) {
                                    triggerHapticFeedback()
                                    val stateDesc = if (isPcLockedState) "LOCKED 🔒 (Tap to Unlock)" else "UNLOCKED 💻 (Tap to Lock)"
                                    statusMessageState = if (isPcLockedState) "🟢 $targetName is Locked • Ready to Unlock" else "🟢 $targetName is Unlocked • System Active"
                                    Toast.makeText(this@MainActivity, "⚡ Connected • $targetName is $stateDesc", Toast.LENGTH_SHORT).show()
                                } else {
                                    statusMessageState = msg
                                    Toast.makeText(this@MainActivity, "⚠️ Could not reach $targetName. Retrying...", Toast.LENGTH_SHORT).show()
                                }
                            }
                        } else {
                            val start = System.currentTimeMillis()
                            // Query actual PC desktop state (Locked or Unlocked) & fetch pending challenges
                            activeClient.sendPing()
                            delay(300)
                            val latency = (System.currentTimeMillis() - start - 300).coerceAtLeast(1)
                            isRefreshingStatusState = false
                            val stateDesc = if (isPcLockedState) "LOCKED 🔒" else "UNLOCKED 💻"
                            statusMessageState = if (isPcLockedState) "🟢 $targetName: Locked" else "🟢 $targetName: Unlocked"
                            triggerHapticFeedback()
                            Toast.makeText(this@MainActivity, "⚡ Synced: $targetName is $stateDesc (${latency}ms)", Toast.LENGTH_SHORT).show()
                        }
                    }
                }

                DashboardView(
                    isConnected = isConnectedState,
                    isPaired = isPairedState,
                    isPcLocked = isPcLockedState,
                    isLockActionPending = isLockActionPendingState,
                    isRefreshingStatus = isRefreshingStatusState,
                    challengeRemainingSeconds = challengeRemainingSecondsState,
                    statusMessage = statusMessageState,
                    pcName = pcNameState,
                    pcIp = pcIpState,
                    isPhoneBluetoothOn = isBluetoothEnabledState.value,
                    isPcBluetoothOn = isPcBluetoothEnabledState.value,
                    networkTransport = networkTransportState.value,
                    transferState = transferState,
                    receivedFiles = receivedFiles,
                    latestReceivedClipboard = latestReceivedClipboard,
                    onConnectDirect = {
                        checkBluetoothAndExecute {
                            isUserManuallyDisconnected = false
                            if (pcIpState.isNotBlank()) {
                                connectToDaemon(pcIpState, pcPortState, pcNameState) { success, msg ->
                                    isConnectedState = success
                                    statusMessageState = msg
                                }
                            }
                        }
                    },
                    onScanQrClicked = { checkBluetoothAndExecute { currentScreen = ScreenState.QR_SCANNER } },
                    onManualSetupClicked = { checkBluetoothAndExecute { showManualDialog = true } },
                    onRequestEnableBluetooth = requestEnableBluetooth,
                    onRequestSendFile = launchSafeFilePicker,
                    onRequestSendMedia = launchSafeMediaPicker,
                    onSendClipboardText = { text -> sendAuraDropClipboard(text) },
                    onShowBluetoothInfo = { showBluetoothSheet = true },
                    onOpenSettings = { showSettingsDialog = true },
                    onDisconnectClicked = {
                        isUserManuallyDisconnected = true
                        autoReconnectJob?.cancel()
                        client?.disconnect()
                        client = null
                        isConnectedState = false
                        AuraLockService.stop(this@MainActivity)
                        statusMessageState = "🔴 Paused (Manual Disconnect)"
                        Toast.makeText(this@MainActivity, "Disconnected from PC", Toast.LENGTH_SHORT).show()
                    },
                    onUnpairClicked = performUnpair,
                    onToggleLockAction = {
                        if (isPcLockedState) {
                            performUnlockPc()
                        } else {
                            performLockPc()
                        }
                    },
                    onManualToggleLockState = {
                        isPcLockedState = !isPcLockedState
                        prefs.edit().putBoolean("is_pc_locked", isPcLockedState).apply()
                        triggerHapticFeedback()
                        Toast.makeText(
                            this@MainActivity,
                            if (isPcLockedState) "Switched state to LOCKED (Unlock Ready)" else "Switched state to UNLOCKED (Lock Ready)",
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onRefreshStatus = performRefreshStatus,
                    onRequestShowTransferDialog = {
                        showTransferDialogState = true
                    },
                    onTestBiometrics = {
                        checkBluetoothAndExecute {
                            testBiometricsPrompt()
                        }
                    }
                )
            }
        }

        // First Launch / Missing Permissions Onboarding Modal
        if (showPermissionOnboardingDialog) {
            PermissionOnboardingDialog(
                onGrantAllClicked = {
                    val missing = getRequiredPermissionsList()
                    if (missing.isNotEmpty()) {
                        try {
                            requestAllPermissionsLauncher.launch(missing.toTypedArray())
                        } catch (e: Throwable) {
                            e.printStackTrace()
                            try {
                                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                    data = Uri.fromParts("package", packageName, null)
                                }
                                startActivity(intent)
                            } catch (_: Throwable) {}
                        }
                    } else {
                        prefs.edit().putBoolean("has_completed_permission_onboarding", true).apply()
                        showPermissionOnboardingDialog = false
                        Toast.makeText(this@MainActivity, "All permissions are already granted!", Toast.LENGTH_SHORT).show()
                    }
                },
                onOpenSettingsClicked = {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.fromParts("package", packageName, null)
                        }
                        startActivity(intent)
                    } catch (e: Throwable) {
                        Toast.makeText(this@MainActivity, "Open Settings -> Apps -> MsmConnect -> Permissions", Toast.LENGTH_LONG).show()
                    }
                },
                onDismiss = {
                    prefs.edit().putBoolean("has_completed_permission_onboarding", true).apply()
                    showPermissionOnboardingDialog = false
                }
            )
        }

        // Bluetooth Required Alert Modal
        if (showBluetoothRequiredDialog) {
            AlertDialog(
                onDismissRequest = { showBluetoothRequiredDialog = false },
                containerColor = Color(0xFF0F172A),
                titleContentColor = Color.White,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.BluetoothDisabled,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Bluetooth is Required", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                },
                text = {
                    Text(
                        text = "MsmConnect requires Bluetooth (BLE) to detect PC proximity and establish secure hardware verification. Please turn on Bluetooth to proceed.",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showBluetoothRequiredDialog = false
                            requestEnableBluetooth()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Turn On Bluetooth", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showBluetoothRequiredDialog = false }) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }
                }
            )
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
                initialIp = pcIpState,
                onConfirm = { qrData ->
                    showManualDialog = false
                    initiatePairing(qrData) { state ->
                        connectionProgress = state
                        if (state.step == ConnectionStep.SECURE_TUNNEL_ACTIVE) {
                            isConnectedState = true
                            isPairedState = true
                            pcNameState = qrData.pcName
                            pcIpState = qrData.ip
                            pcPortState = qrData.port
                            statusMessageState = "Paired & Listening for Lockscreen"

                            prefs.edit()
                                .putString("pc_name", qrData.pcName)
                                .putString("pc_ip", qrData.ip)
                                .putInt("pc_port", qrData.port)
                                .putBoolean("is_paired", true)
                                .apply()
                        } else if (state.errorMessage != null) {
                            isConnectedState = false
                            statusMessageState = state.errorMessage
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

        // AuraDrop Transfer Animation Popup Dialog (Active during transfer, with Cancel, Retry, and Close buttons)
        if (showTransferDialogState && (transferState.isTransferring || transferState.isCompleted || transferState.isError)) {
            AuraDropTransferDialog(
                transferState = transferState,
                onCancelTransfer = {
                    client?.cancelFileTransfer(transferState.activeFileId, transferState.activeFileName)
                    activeTransferJob?.cancel()
                    activeTransferJob = null
                    transferState = AuraDropTransferState()
                    showTransferDialogState = false
                    AuraLockService.start(this@MainActivity, pcNameState)
                    Toast.makeText(this@MainActivity, "Transfer cancelled", Toast.LENGTH_SHORT).show()
                },
                onRetryTransfer = {
                    if (lastSentUris.isNotEmpty()) {
                        sendAuraDropFiles(lastSentUris)
                    }
                },
                onDismiss = {
                    showTransferDialogState = false
                }
            )
        }

        // AuraDrop Bottom Sheet
        if (showAuraDropSheet) {
            AuraDropSheet(
                isConnected = isConnectedState,
                pcName = pcNameState,
                transferState = transferState,
                receivedFiles = receivedFiles,
                latestReceivedClipboard = latestReceivedClipboard,
                onRequestSendFile = launchSafeFilePicker,
                onRequestSendMedia = launchSafeMediaPicker,
                onSendClipboardText = { text -> sendAuraDropClipboard(text) },
                onDismiss = { showAuraDropSheet = false }
            )
        }

        // Bluetooth & Dual-Channel Info Sheet
        if (showBluetoothSheet) {
            BluetoothInfoSheet(
                isConnected = isConnectedState,
                currentIp = pcIpState,
                isPhoneBluetoothOn = isBluetoothEnabledState.value,
                isPcBluetoothOn = isPcBluetoothEnabledState.value,
                onDismiss = { showBluetoothSheet = false }
            )
        }

        // Settings Dialog
        if (showSettingsDialog) {
            SettingsDialog(
                pcName = pcNameState,
                pcIp = pcIpState,
                pcPort = pcPortState,
                isPaired = isPairedState,
                isConnected = isConnectedState,
                backgroundServiceEnabled = backgroundServiceEnabledState,
                autoConnectEnabled = autoConnectEnabledState,
                hapticsEnabled = hapticsEnabledState,
                onBackgroundServiceToggled = { enabled ->
                    backgroundServiceEnabledState = enabled
                    prefs.edit().putBoolean("background_service_enabled", enabled).apply()
                    if (!enabled) {
                        AuraLockService.stop(this@MainActivity)
                        Toast.makeText(this@MainActivity, "🛑 Background Service Disabled (Battery Saver Mode)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@MainActivity, "⚡ Background Service Enabled & Reconnecting...", Toast.LENGTH_SHORT).show()
                        if (isConnectedState) {
                            AuraLockService.start(this@MainActivity, pcNameState)
                        } else if (isPairedState && pcIpState.isNotBlank() && !isUserManuallyDisconnected) {
                            startAutoReconnectLoop(pcIpState, pcPortState, pcNameState)
                        }
                    }
                },
                onAutoConnectToggled = { enabled ->
                    autoConnectEnabledState = enabled
                    prefs.edit().putBoolean("auto_connect", enabled).apply()
                },
                onHapticsToggled = { enabled ->
                    hapticsEnabledState = enabled
                    prefs.edit().putBoolean("haptics_enabled", enabled).apply()
                },
                onTestPing = { onResult ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val latency = client?.measurePing() ?: -1L
                        withContext(Dispatchers.Main) {
                            onResult(if (latency >= 0) latency else -1L)
                        }
                    }
                },
                onUnpairClicked = performUnpair,
                onDismiss = { showSettingsDialog = false }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun DashboardView(
        isConnected: Boolean,
        isPaired: Boolean,
        isPcLocked: Boolean,
        isLockActionPending: Boolean = false,
        isRefreshingStatus: Boolean,
        challengeRemainingSeconds: Int = 0,
        statusMessage: String,
        pcName: String,
        pcIp: String,
        isPhoneBluetoothOn: Boolean,
        isPcBluetoothOn: Boolean,
        networkTransport: String,
        transferState: AuraDropTransferState,
        receivedFiles: List<AuraDropFileItem>,
        latestReceivedClipboard: String?,
        onConnectDirect: () -> Unit,
        onScanQrClicked: () -> Unit,
        onManualSetupClicked: () -> Unit,
        onRequestEnableBluetooth: () -> Unit,
        onRequestSendFile: () -> Unit,
        onRequestSendMedia: () -> Unit,
        onSendClipboardText: (String) -> Unit,
        onShowBluetoothInfo: () -> Unit,
        onOpenSettings: () -> Unit,
        onDisconnectClicked: () -> Unit,
        onUnpairClicked: () -> Unit,
        onToggleLockAction: () -> Unit,
        onManualToggleLockState: () -> Unit,
        onRefreshStatus: () -> Unit,
        onRequestShowTransferDialog: () -> Unit,
        onTestBiometrics: () -> Unit
    ) {
        val pagerState = rememberPagerState(pageCount = { 2 })
        val coroutineScope = rememberCoroutineScope()

        Scaffold(
            topBar = {
                AuraLockTopBar(
                    isConnected = isConnected,
                    isPhoneBluetoothOn = isPhoneBluetoothOn,
                    isPcBluetoothOn = isPcBluetoothOn,
                    networkTransport = networkTransport,
                    onRequestEnableBluetooth = onRequestEnableBluetooth,
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
            ) {
                // Sleek Tab / Navigation Segment
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 6.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0F172A))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Tab 0: Security & Key
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.horizontalGradient(
                                    if (pagerState.currentPage == 0)
                                        listOf(
                                            Color(0xFF0284C7).copy(alpha = 0.35f),
                                            Color(0xFF00FF88).copy(alpha = 0.25f)
                                        )
                                    else
                                        listOf(Color.Transparent, Color.Transparent)
                                )
                            )
                            .border(
                                1.dp,
                                if (pagerState.currentPage == 0) Color(0xFF00FF88).copy(alpha = 0.5f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                coroutineScope.launch { pagerState.animateScrollToPage(0) }
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = if (pagerState.currentPage == 0) Color(0xFF00FF88) else Color(0xFF64748B),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Security Key",
                                color = if (pagerState.currentPage == 0) Color.White else Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                fontWeight = if (pagerState.currentPage == 0) FontWeight.Bold else FontWeight.Medium
                            )
                        }
                    }

                    // Tab 1: AuraDrop & Tools
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.horizontalGradient(
                                    if (pagerState.currentPage == 1)
                                        listOf(
                                            Color(0xFF00D2FF).copy(alpha = 0.35f),
                                            Color(0xFF00FF88).copy(alpha = 0.25f)
                                        )
                                    else
                                        listOf(Color.Transparent, Color.Transparent)
                                )
                            )
                            .border(
                                1.dp,
                                if (pagerState.currentPage == 1) Color(0xFF00D2FF).copy(alpha = 0.5f) else Color.Transparent,
                                RoundedCornerShape(10.dp)
                            )
                            .clickable {
                                coroutineScope.launch { pagerState.animateScrollToPage(1) }
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.NearMe,
                                contentDescription = null,
                                tint = if (pagerState.currentPage == 1) Color(0xFF00D2FF) else Color(0xFF64748B),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "AuraDrop & Tools",
                                color = if (pagerState.currentPage == 1) Color.White else Color(0xFF94A3B8),
                                fontSize = 13.sp,
                                fontWeight = if (pagerState.currentPage == 1) FontWeight.Bold else FontWeight.Medium
                            )
                            if (transferState.isTransferring) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00FF88))
                                )
                            }
                        }
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    when (page) {
                        0 -> BiometricDashboardPage(
                            isConnected = isConnected,
                            isPaired = isPaired,
                            isPcLocked = isPcLocked,
                            isLockActionPending = isLockActionPending,
                            isRefreshingStatus = isRefreshingStatus,
                            challengeRemainingSeconds = challengeRemainingSeconds,
                            statusMessage = statusMessage,
                            pcName = pcName,
                            pcIp = pcIp,
                            isPhoneBluetoothOn = isPhoneBluetoothOn,
                            isPcBluetoothOn = isPcBluetoothOn,
                            networkTransport = networkTransport,
                            onConnectDirect = onConnectDirect,
                            onScanQrClicked = onScanQrClicked,
                            onManualSetupClicked = onManualSetupClicked,
                            onRequestEnableBluetooth = onRequestEnableBluetooth,
                            onDisconnectClicked = onDisconnectClicked,
                            onUnpairClicked = onUnpairClicked,
                            onToggleLockAction = onToggleLockAction,
                            onManualToggleLockState = onManualToggleLockState,
                            onRefreshStatus = onRefreshStatus,
                            onSwipeToAuraDrop = {
                                coroutineScope.launch { pagerState.animateScrollToPage(1) }
                            },
                            onRequestShowTransferDialog = onRequestShowTransferDialog
                        )
                        1 -> AuraDropToolsPage(
                            isConnected = isConnected,
                            pcName = pcName,
                            pcIp = pcIp,
                            transferState = transferState,
                            receivedFiles = receivedFiles,
                            latestReceivedClipboard = latestReceivedClipboard,
                            onRequestSendFile = onRequestSendFile,
                            onRequestSendMedia = onRequestSendMedia,
                            onSendClipboardText = onSendClipboardText,
                            onRequestShowTransferDialog = onRequestShowTransferDialog,
                            onShowBluetoothInfo = onShowBluetoothInfo,
                            onTestBiometrics = onTestBiometrics
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun BiometricDashboardPage(
        isConnected: Boolean,
        isPaired: Boolean,
        isPcLocked: Boolean,
        isLockActionPending: Boolean = false,
        isRefreshingStatus: Boolean,
        challengeRemainingSeconds: Int = 0,
        statusMessage: String,
        pcName: String,
        pcIp: String,
        isPhoneBluetoothOn: Boolean,
        isPcBluetoothOn: Boolean,
        networkTransport: String,
        onConnectDirect: () -> Unit,
        onScanQrClicked: () -> Unit,
        onManualSetupClicked: () -> Unit,
        onRequestEnableBluetooth: () -> Unit,
        onDisconnectClicked: () -> Unit,
        onUnpairClicked: () -> Unit,
        onToggleLockAction: () -> Unit,
        onManualToggleLockState: () -> Unit,
        onRefreshStatus: () -> Unit,
        onSwipeToAuraDrop: () -> Unit,
        onRequestShowTransferDialog: () -> Unit
    ) {
        var showUnpairConfirmDialog by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Section 1: Top Status Card (Clickable Paired Device & Live Status)
                StatusOverviewCard(
                    isConnected = isConnected,
                    isPaired = isPaired,
                    statusMessage = statusMessage,
                    pcName = pcName,
                    pcIp = pcIp,
                    networkTransport = networkTransport,
                    isPhoneBluetoothOn = isPhoneBluetoothOn,
                    isPcBluetoothOn = isPcBluetoothOn,
                    onConnectDirect = onConnectDirect,
                    onDisconnect = onDisconnectClicked
                )

                // Active Biometric Challenge 60s Countdown Banner
                if (challengeRemainingSeconds > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF00FF88).copy(alpha = 0.8f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Fingerprint,
                                    contentDescription = null,
                                    tint = Color(0xFF00FF88),
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "Unlock Request Active",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = Color.White
                                    )
                                    Text(
                                        text = "Touch sensor on phone",
                                        fontSize = 11.sp,
                                        color = Color(0xFF6EE7B7)
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF022C22))
                                    .border(1.dp, Color(0xFF00FF88).copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "⏱️ ${challengeRemainingSeconds}s",
                                    color = Color(0xFF00FF88),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                            }
                        }
                    }
                }

                // Phone Bluetooth Disabled Warning Banner
                if (!isPhoneBluetoothOn) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(onClick = onRequestEnableBluetooth),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF2D1804)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BluetoothDisabled,
                                contentDescription = null,
                                tint = Color(0xFFF59E0B),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Phone Bluetooth Off",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color(0xFFFBBF24)
                                )
                                Text(
                                    text = "Enable Bluetooth for proximity security",
                                    fontSize = 11.sp,
                                    color = Color(0xFFFDE68A)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ArrowForward,
                                contentDescription = null,
                                tint = Color(0xFFFBBF24),
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                } else if (isConnected && !isPcBluetoothOn) {
                    // PC Bluetooth Disabled Warning Banner
                    Spacer(modifier = Modifier.height(8.dp))
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp)),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1219)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.6f))
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.BluetoothDisabled,
                                contentDescription = null,
                                tint = Color(0xFFEF4444),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "PC Bluetooth Off",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = Color(0xFFFCA5A5)
                                )
                                Text(
                                    text = "Turn ON PC Bluetooth for proximity verification",
                                    fontSize = 11.sp,
                                    color = Color(0xFFFECDD3)
                                )
                            }
                        }
                    }
                }
            }

            // Section 2: Concentric Glowing Fingerprint Hologram Area (Tap to Refresh & Reconnect)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 10.dp)
            ) {
                PulseFingerprintView(
                    isConnected = isConnected,
                    isRefreshing = isRefreshingStatus,
                    onRefreshClick = onRefreshStatus
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = if (challengeRemainingSeconds > 0) "Biometric Unlock (⏱️ ${challengeRemainingSeconds}s)"
                    else if (isConnected && isPhoneBluetoothOn && isPcBluetoothOn) "Hardware Biometric Key"
                    else if (isConnected && !isPcBluetoothOn) "PC Bluetooth Off"
                    else if (isConnected && !isPhoneBluetoothOn) "Phone Bluetooth Off"
                    else if (isPaired) "$pcName Paired"
                    else "Ready to Pair",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )

                Spacer(modifier = Modifier.height(3.dp))

                Text(
                    text = if (challengeRemainingSeconds > 0)
                        "Touch fingerprint sensor on phone to unlock ⚡"
                    else if (isConnected)
                        "Tap fingerprint to refresh link & sync ⚡"
                    else if (isPaired)
                        "Tap fingerprint to reconnect with $pcName ⚡"
                    else
                        "Tap 'Scan QR to Pair' to link with PC",
                    color = Color(0xFF94A3B8),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Swipe Left Pill Hint / AuraDrop Access
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xFF10192A))
                        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                        .clickable {
                            if (transferState.isTransferring || transferState.isCompleted || transferState.isError) {
                                onRequestShowTransferDialog()
                            } else {
                                onSwipeToAuraDrop()
                            }
                        }
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (transferState.isTransferring) "⚡ File Transfer Active (${(transferState.progress * 100).toInt()}%)" else "Swipe for AuraDrop & Tools ⚡",
                            color = Color(0xFF00D2FF),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = Color(0xFF00D2FF),
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }

            // Section 3: Bottom Action Button (⚡ Single Smart Dynamic Lock / Unlock Action Button)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (isPaired) {
                    DynamicLockActionButton(
                        isPcLocked = isPcLocked,
                        isConnected = isConnected,
                        isActionPending = isLockActionPending,
                        onExecuteAction = onToggleLockAction,
                        onManualToggleState = onManualToggleLockState
                    )
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

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "msm.movin • Copyright (c) 2022. All rights reserved.",
                    color = Color(0xFF475569),
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (showUnpairConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showUnpairConfirmDialog = false },
                containerColor = Color(0xFF0F172A),
                titleContentColor = Color.White,
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Unpair $pcName?", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    }
                },
                text = {
                    Text(
                        text = "Do you want to unpair from $pcName? The connection will be removed immediately and you can pair again anytime.",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 18.sp
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showUnpairConfirmDialog = false
                            onUnpairClicked()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEF4444))
                    ) {
                        Text("Yes, Unpair", fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showUnpairConfirmDialog = false }) {
                        Text("Cancel", color = Color(0xFF94A3B8))
                    }
                }
            )
        }
    }

    @Composable
    private fun DynamicLockActionButton(
        isPcLocked: Boolean,
        isConnected: Boolean,
        isActionPending: Boolean = false,
        onExecuteAction: () -> Unit,
        onManualToggleState: () -> Unit
    ) {
        val bgGradient = if (isActionPending) {
            // Action is in progress -> Amber / Orange glowing gradient
            Brush.horizontalGradient(
                listOf(
                    Color(0xFFD97706),
                    Color(0xFFB45309),
                    Color(0xFF92400E)
                )
            )
        } else if (isPcLocked) {
            // PC is Locked -> Action is Unlock PC (Emerald / Teal / Cyan glowing gradient)
            Brush.horizontalGradient(
                listOf(
                    Color(0xFF059669),
                    Color(0xFF0D9488),
                    Color(0xFF0284C7)
                )
            )
        } else {
            // PC is Unlocked -> Action is Lock PC (Crimson / Ruby glowing gradient)
            Brush.horizontalGradient(
                listOf(
                    Color(0xFFE11D48),
                    Color(0xFFBE123C),
                    Color(0xFF9F1239)
                )
            )
        }

        val borderColor = when {
            isActionPending -> Color(0xFFFBBF24).copy(alpha = 0.75f)
            isPcLocked -> Color(0xFF34D399).copy(alpha = 0.55f)
            else -> Color(0xFFFB7185).copy(alpha = 0.55f)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.5.dp, borderColor, RoundedCornerShape(16.dp))
                .clickable(enabled = !isActionPending, onClick = onExecuteAction),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent),
            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgGradient)
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.25f)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isActionPending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.5.dp,
                                    color = Color.White
                                )
                            } else {
                                AnimatedContent(
                                    targetState = isPcLocked,
                                    transitionSpec = {
                                        (fadeIn(animationSpec = tween(220)) + scaleIn(initialScale = 0.8f))
                                            .togetherWith(fadeOut(animationSpec = tween(220)) + scaleOut(targetScale = 0.8f))
                                    },
                                    label = "LockIconAnim"
                                ) { locked ->
                                    Icon(
                                        imageVector = if (locked) Icons.Default.LockOpen else Icons.Default.Lock,
                                        contentDescription = if (locked) "Unlock PC" else "Lock PC",
                                        modifier = Modifier.size(22.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column {
                            Text(
                                text = when {
                                    isActionPending && isPcLocked -> "Unlocking..."
                                    isActionPending && !isPcLocked -> "Locking..."
                                    isPcLocked -> "Unlock PC"
                                    else -> "Lock PC"
                                },
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp,
                                color = Color.White
                            )
                            Text(
                                text = when {
                                    isActionPending -> "Verifying..."
                                    isPcLocked -> "Locked • Tap to Unlock"
                                    else -> "Active • Tap to Lock"
                                },
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Right manual state toggle pill
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color.Black.copy(alpha = 0.28f))
                            .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                            .clickable(enabled = !isActionPending, onClick = onManualToggleState)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            isActionPending -> Color(0xFFFBBF24)
                                            isPcLocked -> Color(0xFF34D399)
                                            else -> Color(0xFFFB7185)
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = when {
                                    isActionPending -> "SYNC"
                                    isPcLocked -> "LOCKED"
                                    else -> "UNLOCKED"
                                },
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Default.SwapHoriz,
                                contentDescription = "Toggle status",
                                tint = Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(13.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun AuraDropToolsPage(
        isConnected: Boolean,
        pcName: String,
        pcIp: String,
        transferState: AuraDropTransferState,
        receivedFiles: List<AuraDropFileItem>,
        latestReceivedClipboard: String?,
        onRequestSendFile: () -> Unit,
        onRequestSendMedia: () -> Unit,
        onSendClipboardText: (String) -> Unit,
        onRequestShowTransferDialog: () -> Unit,
        onShowBluetoothInfo: () -> Unit,
        onTestBiometrics: () -> Unit
    ) {
        val context = LocalContext.current
        var clipboardInput by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // AuraDrop Title Banner (Clicking reopens transfer animation popup if transfer is active or completed)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (transferState.isTransferring || transferState.isCompleted || transferState.isError) {
                            onRequestShowTransferDialog()
                        }
                    },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF10192A)),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    Brush.horizontalGradient(
                        listOf(
                            Color(0xFF00FF88).copy(alpha = 0.6f),
                            Color(0xFF00D2FF).copy(alpha = 0.6f)
                        )
                    )
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFF00FF88), Color(0xFF00D2FF))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NearMe,
                                contentDescription = null,
                                tint = Color(0xFF07090E),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "⚡ AuraDrop (Data Transfer)",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (transferState.isTransferring) "⚡ Transfer in progress (${(transferState.progress * 100).toInt()}%) • Tap to view"
                                else if (isConnected) "Connected to $pcName ($pcIp)"
                                else "PC Disconnected • Connect to share",
                                color = if (transferState.isTransferring) Color(0xFF00D2FF) else if (isConnected) Color(0xFF00FF88) else Color(0xFF94A3B8),
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (transferState.isTransferring || transferState.isCompleted) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF00D2FF).copy(alpha = 0.15f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "View ⚡",
                                color = Color(0xFF00D2FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Quick File / Photo Share Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Send File Button
                Button(
                    onClick = {
                        if (!isConnected) {
                            Toast.makeText(context, "Connect to PC first to send files", Toast.LENGTH_SHORT).show()
                        } else {
                            onRequestSendFile()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00D2FF),
                        contentColor = Color(0xFF07090E)
                    )
                ) {
                    Icon(Icons.Default.UploadFile, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Send File", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Send Media Button
                Button(
                    onClick = {
                        if (!isConnected) {
                            Toast.makeText(context, "Connect to PC first to send photos", Toast.LENGTH_SHORT).show()
                        } else {
                            onRequestSendMedia()
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00FF88),
                        contentColor = Color(0xFF07090E)
                    )
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Send Media", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Send Text / Link / Clipboard to PC Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF101622)),
                shape = RoundedCornerShape(14.dp),
                border = CardDefaults.outlinedCardBorder().copy(brush = Brush.linearGradient(listOf(Color(0xFF334155), Color(0xFF1E293B))))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "📋 Send Text / Link to PC",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = clipboardInput,
                            onValueChange = { clipboardInput = it },
                            placeholder = { Text("Type or paste message / URL...", color = Color(0xFF64748B), fontSize = 13.sp) },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFF00D2FF),
                                unfocusedBorderColor = Color(0xFF334155),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                if (clipboardInput.isNotBlank()) {
                                    onSendClipboardText(clipboardInput)
                                    clipboardInput = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF00FF88))
                        ) {
                            Icon(Icons.Default.Send, contentDescription = "Send", tint = Color(0xFF07090E))
                        }
                    }
                }
            }

            // Received Text from PC Card
            if (!latestReceivedClipboard.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C221F)),
                    shape = RoundedCornerShape(12.dp),
                    border = CardDefaults.outlinedCardBorder().copy(brush = Brush.horizontalGradient(listOf(Color(0xFF00FF88), Color(0xFF00D2FF))))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Received from PC:", color = Color(0xFF00FF88), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = latestReceivedClipboard,
                                color = Color.White,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("AuraLock Text", latestReceivedClipboard)
                            clipboard.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied to phone clipboard!", Toast.LENGTH_SHORT).show()
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = Color(0xFF00FF88))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Dual-Channel Wi-Fi & Bluetooth Info Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onShowBluetoothInfo),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF818CF8).copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Bluetooth,
                                contentDescription = null,
                                tint = Color(0xFF818CF8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Wi-Fi & Bluetooth Dual-Channel",
                                color = Color(0xFFE2E8F0),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Proximity auto-wakeup & latency diagnostics",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Biometric Sensor Hardware Test Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onTestBiometrics),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF00FF88).copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Fingerprint,
                                contentDescription = null,
                                tint = Color(0xFF00FF88),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Test Hardware Fingerprint Sensor",
                                color = Color(0xFFE2E8F0),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Hardware TEE Keystore signature verify",
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp
                            )
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Received Files Section
            Text(
                text = "Received Files (${receivedFiles.size})",
                color = Color(0xFF94A3B8),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (receivedFiles.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF0C1322))
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No files received yet from PC",
                        color = Color(0xFF475569),
                        fontSize = 13.sp
                    )
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    receivedFiles.forEach { item ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    item.localUri?.let { path ->
                                        openReceivedFile(context, File(path))
                                    }
                                },
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF101622)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.InsertDriveFile,
                                    contentDescription = null,
                                    tint = Color(0xFF00D2FF),
                                    modifier = Modifier.size(24.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.fileName,
                                        color = Color.White,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.getFormattedSize(),
                                        color = Color(0xFF64748B),
                                        fontSize = 11.sp
                                    )
                                }
                                Icon(
                                    Icons.Default.OpenInNew,
                                    contentDescription = "Open",
                                    tint = Color(0xFF94A3B8),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "msm.movin • Copyright (c) 2022. All rights reserved.",
                color = Color(0xFF475569),
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    private fun openReceivedFile(context: Context, file: File) {
        try {
            if (!file.exists()) {
                Toast.makeText(context, "File not found on disk", Toast.LENGTH_SHORT).show()
                return
            }
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Open File"))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    @Composable
    private fun AuraLockTopBar(
        isConnected: Boolean,
        isPhoneBluetoothOn: Boolean,
        isPcBluetoothOn: Boolean,
        networkTransport: String,
        onRequestEnableBluetooth: () -> Unit,
        onShowBluetoothInfo: () -> Unit,
        onOpenSettings: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .background(Color(0xFF0B1120))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(Color(0xFF00FF88), Color(0xFF00D2FF))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "MsmConnect Logo",
                        tint = Color(0xFF070A13),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column {
                    Text(
                        text = "MsmConnect",
                        color = Color.White,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "msm.movin",
                        color = Color(0xFF00FF88),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Wi-Fi / Hotspot Connection Channel Indicator Pill
                val isNetworkActive = networkTransport != "Offline"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isConnected) Color(0xFF00FF88).copy(alpha = 0.15f)
                            else if (isNetworkActive) Color(0xFF00D2FF).copy(alpha = 0.15f)
                            else Color(0xFF1E293B)
                        )
                        .border(
                            1.dp,
                            if (isConnected) Color(0xFF00FF88).copy(alpha = 0.4f)
                            else if (isNetworkActive) Color(0xFF00D2FF).copy(alpha = 0.4f)
                            else Color(0xFF334155),
                            RoundedCornerShape(8.dp)
                        )
                        .clickable(onClick = onShowBluetoothInfo)
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = "Network Status",
                            tint = if (isConnected) Color(0xFF00FF88) else if (isNetworkActive) Color(0xFF00D2FF) else Color(0xFF64748B),
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isConnected) "Linked" else networkTransport,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isConnected) Color(0xFF00FF88) else if (isNetworkActive) Color(0xFF00D2FF) else Color(0xFF64748B)
                        )
                    }
                }

                // Bluetooth Proximity Channel Indicator Pill
                val btPillColor = if (!isPhoneBluetoothOn) Color(0xFFF59E0B)
                    else if (!isConnected) Color(0xFF64748B)
                    else if (!isPcBluetoothOn) Color(0xFFEF4444)
                    else Color(0xFF00FF88)
                val btPillBg = btPillColor.copy(alpha = 0.15f)
                val btPillBorder = btPillColor.copy(alpha = 0.4f)
                val btPillText = if (!isPhoneBluetoothOn) "Phone BT Off"
                    else if (!isConnected) "BLE Standby"
                    else if (!isPcBluetoothOn) "PC BT Off"
                    else "BLE Linked"

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(btPillBg)
                        .border(1.dp, btPillBorder, RoundedCornerShape(8.dp))
                        .clickable(onClick = {
                            if (!isPhoneBluetoothOn) onRequestEnableBluetooth() else onShowBluetoothInfo()
                        })
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isPhoneBluetoothOn && (!isConnected || isPcBluetoothOn)) Icons.Default.Bluetooth else Icons.Default.BluetoothDisabled,
                            contentDescription = "Bluetooth Status",
                            tint = btPillColor,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = btPillText,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = btPillColor
                        )
                    }
                }

                IconButton(
                    onClick = onOpenSettings,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = Color(0xFF94A3B8),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun StatusOverviewCard(
        isConnected: Boolean,
        isPaired: Boolean,
        statusMessage: String,
        pcName: String,
        pcIp: String,
        networkTransport: String,
        isPhoneBluetoothOn: Boolean,
        isPcBluetoothOn: Boolean,
        onConnectDirect: () -> Unit,
        onDisconnect: () -> Unit
    ) {
        val isFullBluetoothOn = isPhoneBluetoothOn && (!isConnected || isPcBluetoothOn)

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .clickable(enabled = isPaired, onClick = {
                    if (isConnected) onDisconnect() else onConnectDirect()
                }),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF131D31)),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isConnected && isFullBluetoothOn) Color(0xFF00FF88).copy(alpha = 0.4f)
                else if (isConnected && !isPcBluetoothOn) Color(0xFFEF4444).copy(alpha = 0.5f)
                else if (isPaired && isPhoneBluetoothOn) Color(0xFF00D2FF).copy(alpha = 0.25f)
                else if (isPaired) Color(0xFFF59E0B).copy(alpha = 0.35f)
                else Color(0xFF1E293B)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
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
                                .background(
                                    if (isConnected && isFullBluetoothOn) Color(0xFF00FF88)
                                    else if (isConnected && !isPcBluetoothOn) Color(0xFFEF4444)
                                    else if (isPaired && !isPhoneBluetoothOn) Color(0xFFF59E0B)
                                    else if (isPaired) Color(0xFF00D2FF)
                                    else Color(0xFF64748B)
                                )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isConnected && isFullBluetoothOn) "🟢 Linked"
                            else if (isConnected && !isPcBluetoothOn) "🔴 PC BT Off"
                            else if (isPaired && !isPhoneBluetoothOn) "⚠️ Phone BT Off"
                            else if (isPaired) "🔵 Ready"
                            else "⚪ Unpaired",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    if (isPaired) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isConnected) Color(0xFFEF4444).copy(alpha = 0.15f)
                                    else if (!isPhoneBluetoothOn) Color(0xFFF59E0B).copy(alpha = 0.15f)
                                    else Color(0xFF00D2FF).copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isConnected) "Disconnect"
                                else if (!isPhoneBluetoothOn) "Enable BLE"
                                else "Connect ⚡",
                                color = if (isConnected) Color(0xFFEF4444)
                                else if (!isPhoneBluetoothOn) Color(0xFFFBBF24)
                                else Color(0xFF00D2FF),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                if (isPaired) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = null,
                            tint = if (isConnected && isFullBluetoothOn) Color(0xFF00FF88) else if (isConnected && !isPcBluetoothOn) Color(0xFFEF4444) else Color(0xFF94A3B8),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = pcName,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (isConnected && isFullBluetoothOn)
                            "Secured on $pcIp via $networkTransport"
                        else if (isConnected && !isPcBluetoothOn)
                            "PC Bluetooth is OFF. Turn ON for proximity."
                        else if (!isPhoneBluetoothOn)
                            "Phone Bluetooth required for proximity security."
                        else
                            "Paired device. Tap card to connect.",
                        color = if (isConnected && !isPcBluetoothOn) Color(0xFFFCA5A5) else if (!isPhoneBluetoothOn) Color(0xFFFDE68A) else Color(0xFF94A3B8),
                        fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.height(10.dp))
                    HorizontalDivider(color = Color(0xFF1E293B), thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "$networkTransport • $pcIp",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp
                        )
                        Text(
                            text = if (isConnected && isFullBluetoothOn) "🟢 Armed"
                                else if (isConnected && !isPcBluetoothOn) "🔴 PC BT Off"
                                else if (!isPhoneBluetoothOn) "⚠️ No BLE"
                                else if (!isConnected) "⚪ Standby"
                                else "🔵 Linked",
                            color = if (isConnected && isFullBluetoothOn) Color(0xFF00FF88)
                                else if (isConnected && !isPcBluetoothOn) Color(0xFFEF4444)
                                else if (!isPhoneBluetoothOn) Color(0xFFF59E0B)
                                else Color(0xFF64748B),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                } else {
                    Text(
                        text = "No PC paired yet. Scan the QR code on your PC screen or enter the IP manually.",
                        color = Color(0xFF94A3B8),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }

    private fun sendAuraDropFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        lastSentUris = uris
        if (!isPairedState || pcIpState.isBlank()) {
            showSingleToast("Pair with your PC first to send file(s)")
            return
        }

        showTransferDialogState = true
        activeTransferJob?.cancel()
        activeTransferJob = lifecycleScope.launch {
            isTransferMinimized = false
            if (!isConnectedState || client == null) {
                transferState = AuraDropTransferState(
                    isTransferring = true,
                    activeFileName = "Connecting to $pcNameState...",
                    isIncoming = false,
                    progress = 0.05f
                )
                val newClient = AuraLockClient(pcIpState, pcPortState)
                val connected = newClient.connect()
                if (connected) {
                    client = newClient
                    isConnectedState = true
                    AuraLockService.start(this@MainActivity, pcNameState)
                    listenToClientEvents(newClient, pcIpState, pcPortState, pcNameState)
                } else {
                    transferState = AuraDropTransferState(
                        isTransferring = false,
                        isError = true,
                        activeFileName = "Could not reach $pcNameState"
                    )
                    showSingleToast("Could not reach PC ($pcIpState). Make sure PC & phone are on the same Wi-Fi.", Toast.LENGTH_LONG)
                    return@launch
                }
            }

            val totalFiles = uris.size
            var successCount = 0
            for ((index, uri) in uris.withIndex()) {
                try {
                    val fileName = getFileNameFromUri(uri)
                    val mimeType = try {
                        contentResolver.getType(uri) ?: "application/octet-stream"
                    } catch (_: Throwable) {
                        "application/octet-stream"
                    }
                    val fileSize = getFileSizeFromUri(uri)
                    val inputStream = try {
                        contentResolver.openInputStream(uri)
                    } catch (e: Throwable) {
                        null
                    }

                    if (inputStream == null) {
                        showSingleToast("Could not open $fileName")
                        continue
                    }

                    val prefix = if (totalFiles > 1) "(${index + 1}/$totalFiles) " else ""
                    transferState = AuraDropTransferState(
                        isTransferring = true,
                        activeFileName = "$prefix$fileName",
                        isIncoming = false,
                        totalBytes = fileSize,
                        progress = 0.05f
                    )

                    val phoneName = "${Build.MANUFACTURER} ${Build.MODEL}"
                    AuraLockService.updateTransfer(this@MainActivity, "$prefix$fileName", 0.05f, isIncoming = false)
                    var lastTime = System.currentTimeMillis()
                    var lastBytes = 0L
                    val success = client?.sendFileStreamToPc(
                        fileName = fileName,
                        inputStream = inputStream,
                        totalBytes = fileSize,
                        mimeType = mimeType,
                        phoneName = phoneName
                    ) { progress, bytesSent, total ->
                        val currentTime = System.currentTimeMillis()
                        val timeDelta = (currentTime - lastTime) / 1000f
                        var currentSpeed = transferState.speedMBps
                        if (timeDelta >= 0.5f) {
                            currentSpeed = ((bytesSent - lastBytes) / (1024f * 1024f)) / timeDelta
                            lastTime = currentTime
                            lastBytes = bytesSent
                        }
                        AuraLockService.updateTransfer(this@MainActivity, "$prefix$fileName", progress, isIncoming = false)
                        transferState = transferState.copy(
                            progress = progress,
                            bytesTransferred = bytesSent,
                            totalBytes = total,
                            speedMBps = currentSpeed
                        )
                    } ?: false

                    if (success) {
                        successCount++
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            AuraLockService.start(this@MainActivity, pcNameState)
            if (successCount == totalFiles && totalFiles > 0) {
                transferState = transferState.copy(isTransferring = false, isCompleted = true)
                triggerHapticFeedback()
                showSingleToast(if (totalFiles == 1) "✅ Sent file to PC!" else "✅ Sent all $totalFiles files to PC!")
            } else if (successCount > 0) {
                transferState = transferState.copy(isTransferring = false, isCompleted = true)
                triggerHapticFeedback()
                showSingleToast("⚠️ Sent $successCount of $totalFiles files to PC")
            } else {
                transferState = transferState.copy(isTransferring = false, isError = true)
                showSingleToast("Failed to send file(s) to PC")
            }
            activeTransferJob = null
        }
    }

    private fun sendAuraDropFile(uri: Uri) {
        sendAuraDropFiles(listOf(uri))
    }

    private fun sendAuraDropClipboard(text: String) {
        if (!isConnectedState || client == null) {
            Toast.makeText(this, "Connect to PC first", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            val phoneName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val success = client?.sendClipboardToPc(text, phoneName) ?: false
            if (success) {
                Toast.makeText(this@MainActivity, "Text pushed to PC!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getFileSizeFromUri(uri: Uri): Long {
        return try {
            if (uri.scheme == "content") {
                val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex != -1) {
                            return it.getLong(sizeIndex)
                        }
                    }
                }
            }
            if (uri.scheme == "file") {
                uri.path?.let { File(it).length() } ?: 0L
            } else {
                contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }
        } catch (_: Throwable) {
            0L
        }
    }

    private fun getFileNameFromUri(uri: Uri): String {
        var name: String? = null
        try {
            if (uri.scheme == "content") {
                val cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (index != -1) {
                            name = it.getString(index)
                        }
                    }
                }
            }
            if (name == null) {
                name = uri.lastPathSegment
            }
            if (name == null) {
                name = uri.path?.let { File(it).name }
            }
        } catch (_: Throwable) {
            name = uri.lastPathSegment
        }
        return name ?: "shared_file.bin"
    }

    private fun listenToClientEvents(
        clientInstance: AuraLockClient,
        ip: String,
        port: Int,
        targetPcName: String,
        onDisconnectExtra: (() -> Unit)? = null
    ) {
        lifecycleScope.launch {
            val safeStorageDir = try {
                getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: filesDir
            } catch (_: Throwable) {
                filesDir
            }
            clientInstance.listenForEvents(
                onChallenge = { challenge -> handleChallenge(challenge) },
                onIncomingFileOffer = { offer ->
                    isTransferMinimized = false
                    showTransferDialogState = true
                    AuraLockService.updateTransfer(this@MainActivity, offer.fileName, 0.05f, isIncoming = true)
                    transferState = AuraDropTransferState(
                        isTransferring = true,
                        activeFileId = offer.fileId,
                        activeFileName = offer.fileName,
                        isIncoming = true,
                        totalBytes = offer.fileSize,
                        progress = 0.05f
                    )
                },
                onFileProgress = { event ->
                    AuraLockService.updateTransfer(this@MainActivity, event.fileName, event.progress, isIncoming = event.isIncoming)
                    transferState = transferState.copy(
                        isTransferring = true,
                        activeFileName = event.fileName,
                        isIncoming = event.isIncoming,
                        progress = event.progress,
                        bytesTransferred = event.bytesTransferred,
                        totalBytes = event.totalBytes
                    )
                },
                onFileReceived = { fileId, fileName, file ->
                    AuraLockService.start(this@MainActivity, pcNameState)
                    transferState = transferState.copy(isTransferring = false, isCompleted = true)
                    receivedFiles.add(
                        0,
                        AuraDropFileItem(
                            fileId = fileId,
                            fileName = fileName,
                            fileSize = file.length(),
                            localUri = file.absolutePath,
                            isIncoming = true,
                            isCompleted = true
                        )
                    )
                    Toast.makeText(this@MainActivity, "📥 Received $fileName from PC!", Toast.LENGTH_LONG).show()
                    triggerHapticFeedback()
                },
                onClipboardReceived = { text, _ ->
                    latestReceivedClipboard = text
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("AuraLock PC", text)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(this@MainActivity, "📋 Text received from PC: $text", Toast.LENGTH_SHORT).show()
                    triggerHapticFeedback()
                },
                onPcBluetoothStatus = { isActive ->
                    isPcBluetoothEnabledState.value = isActive
                },
                onLockStateChanged = { isLocked ->
                    isPcLockedState = isLocked
                    prefs.edit().putBoolean("is_pc_locked", isLocked).apply()
                    if (isConnectedState) {
                        statusMessageState = if (isLocked) "🟢 $targetPcName is Locked • Ready to Unlock" else "🟢 $targetPcName is Unlocked • System Active"
                    }
                },
                onChallengeCancelled = { reason ->
                    handleChallengeCancelled(reason)
                },
                onUnpaired = {
                    performLocalUnpairCleanUp()
                    Toast.makeText(this@MainActivity, "Device Unpaired by PC", Toast.LENGTH_LONG).show()
                },
                fallbackStorageDir = safeStorageDir,
                onFileTransferCancelled = { fileId, fileName, message ->
                    AuraLockService.start(this@MainActivity, pcNameState)
                    transferState = transferState.copy(isTransferring = false, isError = true)
                    Toast.makeText(this@MainActivity, "❌ Transfer cancelled: $fileName", Toast.LENGTH_SHORT).show()
                },
                onDisconnect = {
                    isConnectedState = false
                    isPcBluetoothEnabledState.value = false
                    client = null
                    AuraLockService.stop(this@MainActivity)
                    onDisconnectExtra?.invoke()
                    statusMessageState = "🔴 Disconnected — Auto-Reconnecting..."
                    if (isPairedState && !isUserManuallyDisconnected && ip.isNotBlank()) {
                        startAutoReconnectLoop(ip, port, targetPcName)
                    }
                }
            )
        }
    }

    private fun startAutoReconnectLoop(
        ip: String,
        port: Int,
        targetPcName: String
    ) {
        if (!isPairedState || isConnectedState || isUserManuallyDisconnected || ip.isBlank()) return
        if (autoReconnectJob?.isActive == true) return

        autoReconnectJob = lifecycleScope.launch {
            statusMessageState = "🔄 Searching & auto-linking to $targetPcName..."
            while (!isConnectedState && isPairedState && !isUserManuallyDisconnected && ip.isNotBlank()) {
                val clientInstance = AuraLockClient(ip, port)
                val connected = clientInstance.connect()
                if (connected) {
                    client = clientInstance
                    isConnectedState = true
                    AuraLockService.start(this@MainActivity, targetPcName)
                    statusMessageState = "🟢 Linked & Armed"
                    showSingleToast("⚡ Connected to $targetPcName!")
                    listenToClientEvents(clientInstance, ip, port, targetPcName)
                    break
                }
                delay(3000)
            }
        }
    }

    private fun connectToDaemon(
        ip: String,
        port: Int,
        targetPcName: String,
        onResult: (Boolean, String) -> Unit
    ) {
        if (ip.isBlank()) return
        isUserManuallyDisconnected = false
        autoReconnectJob?.cancel()
        lifecycleScope.launch {
            val clientInstance = AuraLockClient(ip, port)
            val connected = clientInstance.connect()

            if (!connected) {
                onResult(false, "Could not reach PC at $ip:$port. Auto-reconnecting in background...")
                Toast.makeText(this@MainActivity, "Searching for $targetPcName...", Toast.LENGTH_SHORT).show()
                startAutoReconnectLoop(ip, port, targetPcName)
                return@launch
            }

            client = clientInstance
            isConnectedState = true
            AuraLockService.start(this@MainActivity, targetPcName)
            onResult(true, "🟢 Linked & Armed")
            Toast.makeText(this@MainActivity, "⚡ Connected to $targetPcName!", Toast.LENGTH_SHORT).show()
            listenToClientEvents(clientInstance, ip, port, targetPcName)
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
                        errorMessage = "Could not reach PC at ${qrData.ip}:${qrData.port}. Please make sure phone & PC are on the same Wi-Fi and 'auralock daemon' or 'auralock' is running."
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
            val pairResult = clientInstance.sendPairingRequest(phoneName, pubKey, qrData.pin)

            if (pairResult.success) {
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
                isConnectedState = true
                isPairedState = true
                pcNameState = qrData.pcName
                pcIpState = qrData.ip
                pcPortState = qrData.port
                isUserManuallyDisconnected = false

                prefs.edit()
                    .putBoolean("is_paired", true)
                    .putString("pc_name", qrData.pcName)
                    .putString("pc_ip", qrData.ip)
                    .putInt("pc_port", qrData.port)
                    .apply()

                AuraLockService.start(this@MainActivity, qrData.pcName)
                onProgress(ConnectionProgressState(isConnecting = false, step = ConnectionStep.SECURE_TUNNEL_ACTIVE))
                Toast.makeText(this@MainActivity, "✨ Paired with ${qrData.pcName} successfully!", Toast.LENGTH_LONG).show()

                listenToClientEvents(
                    clientInstance = clientInstance,
                    ip = qrData.ip,
                    port = qrData.port,
                    targetPcName = qrData.pcName,
                    onDisconnectExtra = {
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
                        errorMessage = pairResult.message.ifBlank { "Pairing PIN '${qrData.pin}' was rejected by PC. Please verify PIN on PC screen." }
                    )
                )
            }
        }
    }

    private fun handleChallenge(challenge: ChallengeEvent) {
        try {
            activeChallengeEvent = challenge
            isPcLockedState = true
            prefs.edit().putBoolean("is_pc_locked", true).apply()

            // Start 60s live countdown timer
            challengeTimerJob?.cancel()
            challengeRemainingSecondsState = 60
            challengeTimerJob = lifecycleScope.launch {
                while (challengeRemainingSecondsState > 0) {
                    delay(1000)
                    challengeRemainingSecondsState--
                }
                if (challengeRemainingSecondsState <= 0) {
                    activeChallengeEvent = null
                    Toast.makeText(this@MainActivity, "⏱️ Challenge Expired (60s)", Toast.LENGTH_SHORT).show()
                }
            }

            val signature = cryptoManager.getInitializedSignature()
            biometricPrompt.promptForUnlock(
                signature = signature,
                pcName = challenge.pcName,
                onSuccess = { authedSig ->
                    challengeTimerJob?.cancel()
                    challengeRemainingSecondsState = 0
                    activeChallengeEvent = null
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
                        isPcLockedState = false
                        prefs.edit().putBoolean("is_pc_locked", false).apply()
                        triggerHapticFeedback()
                        Toast.makeText(this@MainActivity, "PC Unlock Approved! 🚀", Toast.LENGTH_SHORT).show()
                    }
                },
                onError = { err ->
                    challengeTimerJob?.cancel()
                    challengeRemainingSecondsState = 0
                    activeChallengeEvent = null
                    Toast.makeText(this@MainActivity, "Biometric failed: $err", Toast.LENGTH_SHORT).show()
                }
            )
        } catch (e: Exception) {
            challengeTimerJob?.cancel()
            challengeRemainingSecondsState = 0
            activeChallengeEvent = null
            e.printStackTrace()
            Toast.makeText(this, "Biometric Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleChallengeCancelled(reason: String = "") {
        val hadActiveChallenge = challengeRemainingSecondsState > 0
        challengeTimerJob?.cancel()
        challengeRemainingSecondsState = 0
        activeChallengeEvent = null
        biometricPrompt.cancelAuthentication()
        if (hadActiveChallenge && (reason.contains("password", ignoreCase = true) || reason.contains("swapped", ignoreCase = true) || reason.contains("pam", ignoreCase = true))) {
            Toast.makeText(this@MainActivity, "⌨️ Swapped to Password on PC", Toast.LENGTH_SHORT).show()
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

    @Composable
    private fun PermissionOnboardingDialog(
        onGrantAllClicked: () -> Unit,
        onOpenSettingsClicked: () -> Unit,
        onDismiss: () -> Unit
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            containerColor = Color(0xFF0F172A),
            shape = RoundedCornerShape(20.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(Color(0xFF00FF88), Color(0xFF00D2FF))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Security,
                            contentDescription = null,
                            tint = Color(0xFF07090E),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Welcome to MsmConnect",
                            fontWeight = FontWeight.Bold,
                            fontSize = 17.sp,
                            color = Color.White
                        )
                        Text(
                            text = "Device Permissions Setup",
                            fontSize = 12.sp,
                            color = Color(0xFF00D2FF)
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "To pair with your PC and enable biometric unlock & wireless file transfer, MsmConnect requires the following permissions:",
                        fontSize = 13.sp,
                        color = Color(0xFF94A3B8),
                        lineHeight = 18.sp
                    )

                    PermissionItemRow(
                        icon = Icons.Default.CameraAlt,
                        iconTint = Color(0xFF38BDF8),
                        title = "Camera",
                        desc = "Scan PC pairing QR code from terminal"
                    )

                    PermissionItemRow(
                        icon = Icons.Default.Bluetooth,
                        iconTint = Color(0xFF818CF8),
                        title = "Bluetooth & Nearby",
                        desc = "Proximity detection & hardware unlock triggers"
                    )

                    PermissionItemRow(
                        icon = Icons.Default.Notifications,
                        iconTint = Color(0xFFFBBF24),
                        title = "Notifications",
                        desc = "Challenge alerts & background file transfer status"
                    )

                    PermissionItemRow(
                        icon = Icons.Default.Folder,
                        iconTint = Color(0xFF34D399),
                        title = "Photos & Storage",
                        desc = "Wireless AuraDrop file & photo sharing"
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = onGrantAllClicked,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF0284C7)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grant All Permissions", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onOpenSettingsClicked) {
                        Text("App Settings", color = Color(0xFF64748B), fontSize = 12.sp)
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Skip for Now", color = Color(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            }
        )
    }

    @Composable
    private fun PermissionItemRow(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        iconTint: Color,
        title: String,
        desc: String
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF1E293B).copy(alpha = 0.6f), RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(iconTint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = Color.White
                )
                Text(
                    text = desc,
                    fontSize = 11.sp,
                    color = Color(0xFF94A3B8)
                )
            }
        }
    }
}

@Composable
fun AuraLockTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF00FF88),
            secondary = Color(0xFF00D2FF),
            background = Color(0xFF070A13),
            surface = Color(0xFF0F172A)
        ),
        content = content
    )
}
