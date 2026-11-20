package vn.huskydg.cravesagax

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Lưu tài khoản đăng nhập (email + mật khẩu) của từng phiên bản game.
 * Mã hóa AES-256-GCM bằng khóa nằm trong Android Keystore (không xuất ra được khỏi máy);
 * file nằm trong noBackupFilesDir nên không bị sao lưu lên cloud.
 */
object CredentialStore {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val ALIAS = "cravesagax_login_key"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_LEN = 12

    class Cred(val user: String, val pass: String)

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private fun fileFor(ctx: Context, gameId: String) =
        File(ctx.noBackupFilesDir, "login_${gameId.replace(Regex("[^A-Za-z0-9_-]"), "_")}.bin")

    @Synchronized
    fun save(ctx: Context, gameId: String, user: String, pass: String): Boolean = try {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val plain = JSONObject().put("u", user).put("p", pass).toString().toByteArray(Charsets.UTF_8)
        fileFor(ctx, gameId).writeBytes(cipher.iv + cipher.doFinal(plain))
        true
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun load(ctx: Context, gameId: String): Cred? {
        val f = fileFor(ctx, gameId)
        if (!f.isFile) return null
        return try {
            val blob = f.readBytes()
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob, 0, IV_LEN))
            val json = JSONObject(String(cipher.doFinal(blob, IV_LEN, blob.size - IV_LEN), Charsets.UTF_8))
            Cred(json.getString("u"), json.getString("p"))
        } catch (_: Exception) {
            f.delete() // khóa đã đổi / file hỏng
            null
        }
    }

    @Synchronized
    fun clear(ctx: Context, gameId: String) {
        fileFor(ctx, gameId).delete()
    }
}
