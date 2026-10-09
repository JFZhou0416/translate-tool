package com.wuming.screentrans

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设置项存储。
 *
 * API Key 单独用 AndroidKeyStore 里的 AES-GCM 密钥加密后再落盘。
 * 刻意不用 androidx.security:security-crypto —— 少一个依赖 = 少一个墙内拉包的风险，
 * 而且这里只要"密文不裸奔"这一个目标，60 行自己写更可控。
 */
object Prefs {

    private const val TAG = "Prefs"
    private const val FILE = "settings"

    private const val K_API_KEY = "api_key_enc"
    private const val K_MODEL = "model"
    private const val K_ENDPOINT = "endpoint"
    private const val K_MAX_TOKENS = "max_tokens"
    private const val K_TEMPERATURE = "temperature"
    private const val K_THINK_OFF = "thinking_disabled"
    private const val K_GLOSSARY = "glossary"
    private const val K_DAILY_CAP = "daily_cap"
    private const val K_USED_DATE = "used_date"
    private const val K_USED_COUNT = "used_count"
    private const val K_BALL_X = "ball_x"
    private const val K_BALL_Y = "ball_y"
    private const val K_PNG_DEBUG = "png_debug"

    // ---- v0.2 权限引导 ----
    private const val K_GUIDE_SEEN = "limit_guide_seen"
    private const val K_OVERLAY_PROBE = "overlay_probe_pending"
    private const val K_OVERLAY_RESTRICTED = "overlay_restricted_suspected"

    const val DEFAULT_MODEL = "deepseek-v4-flash"
    const val DEFAULT_ENDPOINT = "https://api.deepseek.com/v1/chat/completions"
    const val DEFAULT_MAX_TOKENS = 1500
    const val DEFAULT_TEMPERATURE = 0.1f

    /** 每天最多调用多少次，0 = 不限。防费用失控。 */
    const val DEFAULT_DAILY_CAP = 200

    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "screentrans_api_key"

    @Volatile
    private var sp: SharedPreferences? = null

    private fun sp(ctx: Context): SharedPreferences =
        sp ?: synchronized(this) {
            sp ?: ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).also { sp = it }
        }

    // ---------------- API Key（加密存储） ----------------

    fun setApiKey(ctx: Context, key: String) {
        val enc = if (key.isBlank()) "" else encrypt(key.trim())
        sp(ctx).edit().putString(K_API_KEY, enc).apply()
    }

    fun apiKey(ctx: Context): String {
        val enc = sp(ctx).getString(K_API_KEY, "") ?: ""
        if (enc.isEmpty()) return ""
        return try {
            decrypt(enc)
        } catch (e: Exception) {
            // 密钥失效（换设备/清数据/系统换了 key）→ 当作没填，让用户重填
            Log.w(TAG, "解密 API Key 失败，按未填写处理: ${e.message}")
            ""
        }
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gen.generateKey()
    }

    /** 存储格式：base64( IV(12B) + ciphertext ) */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val iv = cipher.iv
        return Base64.encodeToString(iv + body, Base64.NO_WRAP)
    }

    private fun decrypt(enc: String): String {
        val all = Base64.decode(enc, Base64.NO_WRAP)
        require(all.size > 12) { "密文长度异常" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, all, 0, 12))
        return String(cipher.doFinal(all, 12, all.size - 12), Charsets.UTF_8)
    }

    // ---------------- 其它设置 ----------------

    fun model(ctx: Context) = sp(ctx).getString(K_MODEL, DEFAULT_MODEL)!!.ifBlank { DEFAULT_MODEL }
    fun setModel(ctx: Context, v: String) = sp(ctx).edit().putString(K_MODEL, v.trim()).apply()

    fun endpoint(ctx: Context) =
        sp(ctx).getString(K_ENDPOINT, DEFAULT_ENDPOINT)!!.ifBlank { DEFAULT_ENDPOINT }

    fun setEndpoint(ctx: Context, v: String) = sp(ctx).edit().putString(K_ENDPOINT, v.trim()).apply()

    fun maxTokens(ctx: Context) = sp(ctx).getInt(K_MAX_TOKENS, DEFAULT_MAX_TOKENS)
    fun setMaxTokens(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_MAX_TOKENS, v).apply()

    fun temperature(ctx: Context) = sp(ctx).getFloat(K_TEMPERATURE, DEFAULT_TEMPERATURE)
    fun setTemperature(ctx: Context, v: Float) = sp(ctx).edit().putFloat(K_TEMPERATURE, v).apply()

    /**
     * 术语表：一行一条「英文=中文」，会追加进 Prompt 强制约束。
     *
     * 为什么一期就得有：实测同一个 `vault`，v2 基线记录是「仓库」，
     * 现在连跑三次都稳定输出「库」——模型靠不住，术语要自己钉。
     * 加上 `vault=仓库` 后立刻稳定复现基线（+16 输入 token）。
     */
    fun glossary(ctx: Context) = sp(ctx).getString(K_GLOSSARY, "")!!
    fun setGlossary(ctx: Context, v: String) = sp(ctx).edit().putString(K_GLOSSARY, v).apply()

    /** 关思考：漏了它耗时会翻倍（实测 3.9s → 1.7s） */
    fun thinkingDisabled(ctx: Context) = sp(ctx).getBoolean(K_THINK_OFF, true)
    fun setThinkingDisabled(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_THINK_OFF, v).apply()

    fun dailyCap(ctx: Context) = sp(ctx).getInt(K_DAILY_CAP, DEFAULT_DAILY_CAP)
    fun setDailyCap(ctx: Context, v: Int) = sp(ctx).edit().putInt(K_DAILY_CAP, v).apply()

    /** debug 用：把截图落到 App 私有目录，方便 adb pull 出来人眼检查（截图只到本机，不上传别处） */
    fun pngDebug(ctx: Context) = sp(ctx).getBoolean(K_PNG_DEBUG, false)
    fun setPngDebug(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(K_PNG_DEBUG, v).apply()

    fun ballX(ctx: Context) = sp(ctx).getInt(K_BALL_X, -1)
    fun ballY(ctx: Context) = sp(ctx).getInt(K_BALL_Y, -1)
    fun setBallPos(ctx: Context, x: Int, y: Int) = sp(ctx).edit().putInt(K_BALL_X, x).putInt(K_BALL_Y, y).apply()

    // ---------------- 权限引导（v0.2） ----------------

    /** 首次启动自动进过引导页了（只自动进一次，别每次开 App 都怼脸；首页常驻入口不受影响） */
    fun limitGuideSeen(ctx: Context) = sp(ctx).getBoolean(K_GUIDE_SEEN, false)
    fun setLimitGuideSeen(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_GUIDE_SEEN, v).apply()

    /** 用户已经点过「检查悬浮窗权限」，去过一次悬浮窗授权页 */
    fun overlayProbePending(ctx: Context) = sp(ctx).getBoolean(K_OVERLAY_PROBE, false)
    fun setOverlayProbePending(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_OVERLAY_PROBE, v).apply()

    /**
     * 推断「悬浮窗权限被系统限制」：去过授权页 + 回来 canDrawOverlays() 还是 false
     * + 用户点了「我打不开这个开关」。
     *
     * 故意不读任何系统私有标志位（换机型/换版本就失效），只认这三个事实，稳。
     */
    fun overlayRestrictedSuspected(ctx: Context) = sp(ctx).getBoolean(K_OVERLAY_RESTRICTED, false)
    fun setOverlayRestrictedSuspected(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(K_OVERLAY_RESTRICTED, v).apply()
    // ---------------- 每日用量 ----------------

    private fun today(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    /** 返回 true = 允许调用；false = 已超当日上限 */
    fun consumeQuota(ctx: Context): Boolean {
        val cap = dailyCap(ctx)
        if (cap <= 0) return true
        val p = sp(ctx)
        val day = today()
        val used = if (p.getString(K_USED_DATE, "") == day) p.getInt(K_USED_COUNT, 0) else 0
        if (used >= cap) return false
        p.edit().putString(K_USED_DATE, day).putInt(K_USED_COUNT, used + 1).apply()
        return true
    }

    fun usedToday(ctx: Context): Int {
        val p = sp(ctx)
        return if (p.getString(K_USED_DATE, "") == today()) p.getInt(K_USED_COUNT, 0) else 0
    }
}
