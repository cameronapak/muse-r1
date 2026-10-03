/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * Licensed under the Apache License, Version 2.0.
 */
package dev.cameronpak.muser1.pairing

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.ParcelUuid
import dev.cameronpak.muser1.DeviceCredentials
import dev.cameronpak.muser1.DeviceIdentity
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

class PairingServer(
    private val context: Context,
    val identity: DeviceIdentity,
    private val sdkTokenProvider: () -> String?,
    private val onProvisioned: (DeviceCredentials) -> Boolean,
    private val onStatus: (String) -> Unit,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val assembler = BleFraming.Assembler()
    private val crypto = PairingCrypto(identity.deviceId, identity.nodeId, identity.mac)
    private val outgoing = ConcurrentLinkedQueue<ByteArray>()
    private var manager: BluetoothManager? = null
    private var server: BluetoothGattServer? = null
    private var advertiser: android.bluetooth.le.BluetoothLeAdvertiser? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var peer: BluetoothDevice? = null
    private var mtu = 23
    private var notifying = false
    private var closeAfterDrain = false
    private var generation = 0L
    private var provisioning = false
    private var subscribed = false
    private val namePreferences = context.getSharedPreferences("pairing-bluetooth", Context.MODE_PRIVATE)
    @Volatile private var running = false

    @Synchronized fun start(): Boolean {
        if (running) return true
        if (Build.VERSION.SDK_INT >= 31 &&
            (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
             context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED)) {
            onStatus("Bluetooth permissions are required")
            return false
        }
        return try {
            manager = context.getSystemService(BluetoothManager::class.java)
            val adapter = manager?.adapter
            if (adapter == null || !adapter.isEnabled || !adapter.isMultipleAdvertisementSupported) {
                onStatus("Bluetooth LE peripheral mode is unavailable")
                false
            } else {
                server = manager!!.openGattServer(context, callback) ?: return false
                val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
                val rx = BluetoothGattCharacteristic(RX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE)
                tx = BluetoothGattCharacteristic(TX_UUID,
                    BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_READ).also {
                    it.addDescriptor(BluetoothGattDescriptor(CCCD_UUID,
                        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
                }
                service.addCharacteristic(rx); service.addCharacteristic(tx)
                advertiser = adapter.bluetoothLeAdvertiser ?: run { close(); return false }
                // Android advertises only the adapter's local name. Restore it after the pairing window.
                if (!namePreferences.contains("previous")) namePreferences.edit().putString("previous", adapter.name).commit()
                if (!adapter.setName(identity.bleName)) { close(); return false }
                running = true
                if (!server!!.addService(service)) { close(); return false }
                onStatus("Opening pairing")
                true
            }
        } catch (_: SecurityException) { close(); onStatus("Bluetooth permissions are required"); false }
          catch (_: Exception) { close(); onStatus("Bluetooth setup failed"); false }
    }

    @Synchronized override fun close() {
        running = false
        generation++
        provisioning = false
        scope.coroutineContext.cancelChildren()
        try { advertiser?.stopAdvertising(advertiseCallback) } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        try {
            val previous = namePreferences.getString("previous", null)
            if (previous != null) {
                if (manager?.adapter?.name == identity.bleName) manager?.adapter?.setName(previous)
                namePreferences.edit().remove("previous").commit()
            }
        } catch (_: Exception) {}
        advertiser = null; server = null; peer = null; tx = null
        outgoing.clear(); notifying = false; closeAfterDrain = false; assembler.reset(); crypto.reset()
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { onStatus("Bluetooth advertising failed ($errorCode)"); close() }
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) { onStatus("Pairing as ${identity.bleName}") }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            if (status != BluetoothGatt.GATT_SUCCESS) { close(); onStatus("Bluetooth service failed"); return }
            scope.launch {
                repeat(20) { if (manager?.adapter?.name == identity.bleName) return@repeat; delay(50) }
                synchronized(this@PairingServer) {
                    if (!running) return@launch
                    val settings = AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                        .setConnectable(true).setTimeout(0).setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).build()
                    val data = AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE_UUID)).setIncludeDeviceName(false).build()
                    val scan = AdvertiseData.Builder().setIncludeDeviceName(true).build()
                    advertiser?.startAdvertising(settings, data, scan, advertiseCallback)
                }
            }
        }
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            synchronized(this@PairingServer) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    if (peer != null && peer != device) server?.cancelConnection(device) else peer = device
                } else if (device == peer) clearSession()
            }
        }
        override fun onMtuChanged(device: BluetoothDevice, value: Int) { if (device == peer) mtu = value.coerceIn(23, 512) }
        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, characteristic: BluetoothGattCharacteristic) {
            if (device != peer || characteristic.uuid != TX_UUID || offset != 0) respond(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET)
            else server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
        }
        override fun onCharacteristicWriteRequest(device: BluetoothDevice, requestId: Int, characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val valid = device == peer && characteristic.uuid == RX_UUID && !preparedWrite && offset == 0 && value.size <= 512
            if (responseNeeded) respond(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE)
            synchronized(this@PairingServer) { if (valid && subscribed) assembler.feed(value)?.let(::handle) }
        }
        override fun onDescriptorWriteRequest(device: BluetoothDevice, requestId: Int, descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            val valid = device == peer && descriptor.uuid == CCCD_UUID && !preparedWrite && offset == 0 &&
                (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) || value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE))
            if (valid) subscribed = value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (responseNeeded) respond(device, requestId, if (valid) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_FAILURE)
        }
        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            synchronized(this@PairingServer) {
                if (device != peer) return
                notifying = false
                if (status != BluetoothGatt.GATT_SUCCESS) { clearSession(); return }
                notifyNext()
            }
        }
    }

    private fun respond(device: BluetoothDevice, id: Int, status: Int) { server?.sendResponse(device, id, status, 0, null) }
    @Synchronized private fun clearSession() { generation++; provisioning = false; subscribed = false; peer = null; mtu = 23; assembler.reset(); crypto.reset(); outgoing.clear(); notifying = false; closeAfterDrain = false }

    private fun handle(bytes: ByteArray) {
        val command = try { JSONObject(String(bytes, UTF_8)) } catch (_: Exception) { sendPlain("error_invalid_command"); return }
        when (val action = command.optString("action")) {
            "get_device_info" -> send(JSONObject().put("type", "device_info").put("node_id", identity.nodeId)
                .put("device_id", identity.deviceId).put("mac", identity.mac).put("model", "hatch_link")
                .put("version", "1.0.0").put("build_sha", "").put("network_ready", online())
                .put("pairing_protocol", 5).put("pairing_auth", "none").put("pairing_auth_epoch", 0).put("pairing_policy", "confirm_app"))
            "pairing_client_hello" -> try { generation++; provisioning = false; outgoing.clear(); send(crypto.hello(command)) } catch (_: PairingException) { sendPlain("error_pairing_invalid_hello") }
            "pairing_encrypted" -> try { handleEncrypted(crypto.decrypt(command)) } catch (_: PairingException) {
                sendPlain("error_pairing_decrypt"); peer?.let { server?.cancelConnection(it) }
            }
            "wifi_scan", "provision", "provision_v2", "set_wifi", "set_auth", "ota", "device.ota", "unpair" -> sendPlain("error_encryption_required")
            else -> if (!crypto.confirmed) sendPlain("error_unknown_action")
        }
    }

    private fun handleEncrypted(command: JSONObject) {
        if (!crypto.confirmed && command.optString("action") != "pairing_client_finished") {
            resetWithError()
            return
        }
        when (command.optString("action")) {
            "pairing_client_finished" -> if (crypto.confirm(command)) {
                val status = JSONObject().put("type", "status").put("status", "pairing_confirmed")
                sdkTokenProvider()?.takeIf { it.isNotBlank() }?.let { status.put("sdk_token", it) }
                sendEncrypted(status)
            } else resetWithError()
            "wifi_scan" -> if (crypto.confirmed) sendEncrypted(JSONObject().put("type", "wifi_scan_result").put("networks",
                if (online()) JSONArray().put(JSONObject().put("ssid", "Current connection").put("rssi", -30).put("auth", "open")) else JSONArray()))
            "provision_v2" -> if (crypto.confirmed) provision(command) else sendEncryptedStatus("error_pairing_confirm_required")
            else -> sendEncryptedStatus("error_unknown_action")
        }
    }

    @Synchronized private fun provision(command: JSONObject) {
        if (provisioning) { sendEncryptedStatus("error_operation_in_progress"); return }
        val access = command.optString("access_token"); val refresh = command.optString("refresh_token")
        val api = command.optString("api_url_v2").ifBlank { "https://api.muse.ai" }.trimEnd('/')
        val noise = command.optString("noise_host").ifBlank { "hatch.metaaivm.com" }
        if (command.optString("token_type") != "device" || access.isBlank() || refresh.isBlank() ||
            !firstPartyApi(api) || !firstPartyNoise(noise)) { sendEncryptedStatus("error_missing_credentials"); return }
        crypto.extend()
        provisioning = true
        val stamp = generation
        scope.launch {
            try {
                synchronized(this@PairingServer) {
                    if (stamp != generation) return@launch
                    sendEncryptedStatus("wifi_connecting")
                    if (!online()) { sendEncryptedStatus("wifi_failed"); return@launch }
                    sendEncryptedStatus("wifi_connected")
                }
                val credentials = DeviceCredentials(identity.deviceId, access, refresh, api, noise)
                val valid = verify(credentials)
                synchronized(this@PairingServer) {
                    if (stamp != generation || !running || !crypto.confirmed) return@launch
                    if (!valid) { sendEncryptedStatus("auth_failed"); return@launch }
                    if (!onProvisioned(credentials)) { sendEncryptedStatus("error_storage"); return@launch }
                    sendEncryptedStatus("auth_ok")
                    closeAfterDrain = true
                    notifyNext()
                }
            } finally { synchronized(this@PairingServer) { if (stamp == generation) provisioning = false } }
        }
    }

    private fun verify(value: DeviceCredentials): Boolean = try {
        val response = OkHttpClient.Builder().followRedirects(false).callTimeout(java.time.Duration.ofSeconds(15)).build().newCall(
            Request.Builder().url("${value.apiUrlV2}/fetch_vms").header("Authorization", "Bearer ${value.accessToken}")
                .header("X-API-Version", "1.0.0").get().build()).execute()
        response.use { it.isSuccessful && (JSONObject(it.body?.string().orEmpty()).optJSONArray("vm_list")?.length() ?: 0) > 0 }
    } catch (_: Exception) { false }
    private fun firstPartyApi(value: String) = try { java.net.URI(value).let { uri ->
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return@let false
        uri.scheme.equals("https", true) && uri.userInfo == null && (host == "api.muse.ai" || host.endsWith(".api.muse.ai"))
    } } catch (_: Exception) { false }
    private fun firstPartyNoise(value: String) = value.lowercase().trimEnd('.').let { host -> host == "metaaivm.com" || host.endsWith(".metaaivm.com") || host == "muse.ai" || host.endsWith(".muse.ai") }
    private fun online(): Boolean = context.getSystemService(ConnectivityManager::class.java).let { cm -> cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true }
    private fun resetWithError() { crypto.reset(); sendPlain("error_pairing_decrypt"); peer?.let { server?.cancelConnection(it) } }
    private fun sendEncryptedStatus(status: String) { onStatus(status); sendEncrypted(JSONObject().put("type", "status").put("status", status)) }
    @Synchronized private fun sendEncrypted(value: JSONObject) { crypto.encrypt(value)?.let(::send) }
    private fun sendPlain(value: String) = queue(value.toByteArray(UTF_8))
    private fun send(value: JSONObject) = queue(value.toString().toByteArray(UTF_8))
    @Synchronized private fun queue(value: ByteArray) { BleFraming.chunks(value, mtu).forEach(outgoing::add); notifyNext() }
    @Synchronized private fun notifyNext() {
        if (notifying) return
        val packet = outgoing.poll()
        if (packet == null) { if (closeAfterDrain) close(); return }
        val device = peer ?: return; val characteristic = tx ?: return
        notifying = true
        val ok = if (Build.VERSION.SDK_INT >= 33) server?.notifyCharacteristicChanged(device, characteristic, false, packet) == BluetoothStatusCodes.SUCCESS
            else { @Suppress("DEPRECATION") characteristic.value = packet; @Suppress("DEPRECATION") server?.notifyCharacteristicChanged(device, characteristic, false) == true }
        if (!ok) { notifying = false; clearSession() }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("7fdd3d1c-38ea-46cf-8b46-314ecf5f240c")
        val RX_UUID: UUID = UUID.fromString("4d593029-28a2-4a6e-a1f0-3c2d5e8f9b01")
        val TX_UUID: UUID = UUID.fromString("d75dc4ca-7b2b-4e9c-8f0a-1d2e3f4a5b6c")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
