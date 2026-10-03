package dev.cameronpak.muser1

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** All tokens stay in app-private, non-backed-up storage, encrypted by Android Keystore. */
class CredentialStore(private val context: Context) {
    private val state = AtomicFile(File(context.noBackupFilesDir, "credentials.enc"))
    private val preferences = context.getSharedPreferences("identity", Context.MODE_PRIVATE)
    val identity: DeviceIdentity by lazy {
        var mac = preferences.getString("mac", null)
        if (mac == null) {
            val bytes = ByteArray(6).also(SecureRandom()::nextBytes)
            bytes[0] = ((bytes[0].toInt() or 2) and 0xfe).toByte()
            mac = bytes.joinToString(":") { "%02x".format(it.toInt() and 255) }
            check(preferences.edit().putString("mac", mac).commit())
        }
        DeviceIdentity(mac!!)
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("muse-r1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder("muse-r1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    @Synchronized private fun read(): JSONObject {
        if (!state.baseFile.exists()) return JSONObject()
        val bytes = state.readFully()
        require(bytes.size >= 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
    }

    @Synchronized private fun write(value: JSONObject) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val output = state.startWrite()
        try {
            output.write(cipher.iv + cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)))
            state.finishWrite(output)
        } catch (error: Exception) { state.failWrite(output); throw error }
    }

    /** USB provisioning input is never baked into the APK. Delete it immediately after import. */
    @Synchronized fun importPendingToken() {
        val pending = File(context.filesDir, "pending-sdk-token")
        if (!pending.exists()) return
        try {
            val token = pending.readText().trim()
            require(token.isNotBlank() && token.length <= 16384)
            write(read().put("sdk_token", token))
        } finally { pending.delete() }
    }

    @Synchronized fun sdkToken(): String? = read().optString("sdk_token").takeIf { it.isNotBlank() }
    @Synchronized fun credentials(): DeviceCredentials? {
        val j = read().optJSONObject("device") ?: return null
        return DeviceCredentials(j.getString("device_id"), j.getString("access_token"), j.getString("refresh_token"),
            j.getString("api_url_v2"), j.getString("noise_host"))
    }
    @Synchronized fun save(value: DeviceCredentials) {
        write(read().put("device", JSONObject().put("device_id", value.deviceId).put("access_token", value.accessToken)
            .put("refresh_token", value.refreshToken).put("api_url_v2", value.apiUrlV2).put("noise_host", value.noiseHost)))
    }
}
