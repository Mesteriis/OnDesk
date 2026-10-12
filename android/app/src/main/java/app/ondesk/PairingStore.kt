package app.ondesk

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class PairedMac(val serverID: String, val host: String, val port: Int, val fingerprint: String, val token: String)
class PairingStore(context: Context) {
    private val prefs = context.getSharedPreferences("pairing", Context.MODE_PRIVATE)
    val deviceID: String
        @Synchronized get() {
            prefs.getString("deviceID", null)?.let { return it }
            val id = UUID.randomUUID().toString()
            check(prefs.edit().putString("deviceID", id).commit())
            return id
        }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("OnDeskPairingV1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("OnDeskPairingV1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    @Synchronized fun read(): PairedMac? {
        val encoded = prefs.getString("mac", null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0,12)))
        val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)), Charsets.UTF_8))
        return PairedMac(json.getString("serverID"), json.getString("host"), json.getInt("port"), json.getString("fingerprint"), json.getString("token"))
    }
    @Synchronized fun save(mac: PairedMac) {
        val json = JSONObject().put("serverID", mac.serverID).put("host", mac.host).put("port", mac.port).put("fingerprint", mac.fingerprint).put("token", mac.token)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key())
        val bytes = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("mac", Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) { "Не удалось сохранить сопряжение" }
    }
    @Synchronized fun clear() { check(prefs.edit().remove("mac").commit()) }
}
