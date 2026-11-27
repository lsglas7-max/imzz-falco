package id.imzyy.xl

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Device-local IMZYY launcher accounts.
 * Passwords are never stored in plain text; each account has a salted PBKDF2 hash.
 * This is intentionally not presented as a cloud account service.
 */
object ImzyyAccountStore {
    private const val PREFS = "imzyy_local_accounts"
    private const val ACTIVE = "active_user"
    private const val PREFIX = "account_"
    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16

    data class Account(val username: String, val displayName: String)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun current(context: Context): Account? {
        val p = prefs(context)
        val key = p.getString(ACTIVE, null) ?: return null
        val raw = p.getString(PREFIX + key, null) ?: return null
        return runCatching {
            val record = JSONObject(raw)
            Account(key, record.optString("display", key))
        }.getOrNull()
    }

    fun hasAccounts(context: Context): Boolean = prefs(context).all.keys.any { it.startsWith(PREFIX) }

    fun register(context: Context, usernameInput: String, passwordInput: String): Account {
        val display = usernameInput.trim()
        require(display.matches(Regex("[A-Za-z0-9_]{3,16}"))) {
            "Username harus 3–16 karakter: huruf, angka, atau underscore."
        }
        require(passwordInput.length in 8..64) { "Password harus 8–64 karakter." }

        val key = display.lowercase(Locale.ROOT)
        val p = prefs(context)
        require(!p.contains(PREFIX + key)) { "Username sudah terdaftar di perangkat ini." }

        val salt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        val hash = derive(passwordInput.toCharArray(), salt, ITERATIONS)
        val record = JSONObject()
            .put("display", display)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("hash", Base64.encodeToString(hash, Base64.NO_WRAP))
            .put("iterations", ITERATIONS)
            .put("created_at", System.currentTimeMillis())

        p.edit()
            .putString(PREFIX + key, record.toString())
            .putString(ACTIVE, key)
            .apply()
        return Account(key, display)
    }

    fun login(context: Context, usernameInput: String, passwordInput: String): Account {
        val display = usernameInput.trim()
        require(display.matches(Regex("[A-Za-z0-9_]{3,16}"))) { "Username atau password salah." }
        val key = display.lowercase(Locale.ROOT)
        val raw = prefs(context).getString(PREFIX + key, null) ?: error("Username atau password salah.")
        val record = JSONObject(raw)
        val salt = Base64.decode(record.getString("salt"), Base64.NO_WRAP)
        val expected = Base64.decode(record.getString("hash"), Base64.NO_WRAP)
        val iterations = record.optInt("iterations", ITERATIONS).coerceIn(80_000, 500_000)
        val actual = derive(passwordInput.toCharArray(), salt, iterations)
        require(MessageDigest.isEqual(expected, actual)) { "Username atau password salah." }
        prefs(context).edit().putString(ACTIVE, key).apply()
        return Account(key, record.optString("display", display))
    }

    fun logout(context: Context) {
        prefs(context).edit().remove(ACTIVE).apply()
    }

    private fun derive(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
            password.fill('\u0000')
        }
    }
}
