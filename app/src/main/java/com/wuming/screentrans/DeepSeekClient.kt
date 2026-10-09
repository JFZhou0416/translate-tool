package com.wuming.screentrans

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * DeepSeek 调用（OpenAI 兼容）。
 *
 * 规格来自无明实测（计划书 3.3），两个参数别动：
 *  - model = deepseek-v4-flash（服务端别名，实际落到 deepseek-flash）
 *  - thinking:{type:"disabled"} —— 漏了耗时翻倍（3.9s → 1.7s），内容是同一份
 */
object DeepSeekClient {

    private const val TAG = "DeepSeek"
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 15_000
    private const val MAX_LONG_EDGE = 1080

    /** 已实测有效的识图翻译 Prompt */
    private const val PROMPT_IMAGE = """你是屏幕翻译助手。识别图中所有可见文字（保持原有版式顺序），然后翻译成中文。输出格式：
【原文】...
【译文】...
要求：术语准确、简洁，不要任何解释。"""

    private const val PROMPT_TEXT_HEAD = "把下面的文本翻译成中文，术语准确、简洁，不要任何解释，只输出译文。"

    private const val PROMPT_TEXT_TAIL = "文本：\n"

    private val main = Handler(Looper.getMainLooper())

    sealed class Out {
        data class Ok(val text: String, val promptTokens: Int, val completionTokens: Int) : Out()
        data class Err(val message: String) : Out()
    }

    /**
     * 术语表非空时追加一段强约束。
     * 术语表为空时**逐字返回原 Prompt** —— 默认路径必须和实测规格一字不差，别在这里加料。
     */
    private fun promptWithGlossary(app: Context, base: String): String {
        val g = Prefs.glossary(app).trim()
        if (g.isEmpty()) return base
        val lines = g.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) return base
        return base +
            "\n\n术语对照（必须严格遵守，优先于你的默认译法）：\n" +
            lines.joinToString("\n") +
            // 人名/专有名词最容易"同一个人名译出好几种写法"（日文尤其明显）。
            // 表里没有的也得前后一致，不能让用户看到同一个人两个名字。
            "\n\n人名、地名、作品名等专有名词：上表里有的必须用表中译名；" +
            "上表没有的，用通行译名，并保证同一个名称前后译法一致，不要出现多种译法。"
    }

    fun translateImage(ctx: Context, bmp: Bitmap, cb: (Out) -> Unit) {
        val app = ctx.applicationContext
        request(app, contentBuilder = {
            // 编码放到后台线程做：大图 base64 会卡主线程
            val dataUrl = encodeImage(bmp)
            JSONArray().apply {
                put(JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", dataUrl))
                })
                put(JSONObject().put("type", "text").put("text", promptWithGlossary(app, PROMPT_IMAGE)))
            }
        }, cb = cb)
    }

    fun translateText(ctx: Context, text: String, cb: (Out) -> Unit) {
        val app = ctx.applicationContext
        if (text.isBlank()) {
            cb(Out.Err("没有可翻译的文本"))
            return
        }
        request(app, contentBuilder = {
            promptWithGlossary(app, PROMPT_TEXT_HEAD) + "\n\n" + PROMPT_TEXT_TAIL + text
        }, cb = cb)
    }

    // ---------------- 请求 ----------------

    private fun request(app: Context, contentBuilder: () -> Any, cb: (Out) -> Unit) {
        val key = Prefs.apiKey(app)
        if (key.isBlank()) {
            cb(Out.Err("还没填 API Key，点这儿去设置"))
            return
        }
        if (!Prefs.consumeQuota(app)) {
            cb(Out.Err("今日调用已达上限（${Prefs.dailyCap(app)} 次），可在设置里调"))
            return
        }

        Thread {
            val out = try {
                val content = contentBuilder()
                call(app, key, buildBody(app, content).toString(), retry = true)
            } catch (t: Throwable) {
                Out.Err(humanError(t))
            }
            main.post { cb(out) }
        }.apply { isDaemon = true }.start()
    }

    private fun buildBody(app: Context, content: Any): JSONObject = JSONObject().apply {
        put("model", Prefs.model(app))
        put("max_tokens", Prefs.maxTokens(app))
        put("temperature", Prefs.temperature(app).toDouble())
        if (Prefs.thinkingDisabled(app)) {
            put("thinking", JSONObject().put("type", "disabled"))
        }
        put("messages", JSONArray().put(JSONObject().apply {
            put("role", "user")
            put("content", content)
        }))
    }

    private fun call(app: Context, key: String, body: String, retry: Boolean): Out {
        val url = URL(Prefs.endpoint(app))
        var conn: HttpURLConnection? = null
        try {
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout = READ_TIMEOUT
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""

            if (code !in 200..299) {
                return Out.Err(mapHttpError(code, raw))
            }
            return parse(raw)
        } catch (e: IOException) {
            // 网络抖动重试一次
            if (retry) {
                Log.w(TAG, "网络异常，重试一次：${e.message}")
                return call(app, key, body, retry = false)
            }
            throw e
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    private fun parse(raw: String): Out {
        return try {
            val json = JSONObject(raw)
            val choices = json.optJSONArray("choices")
            val msg = choices?.optJSONObject(0)?.optJSONObject("message")
            val text = msg?.optString("content", "")?.trim().orEmpty()
            if (text.isEmpty()) {
                Out.Err("模型返回了空内容")
            } else {
                val usage = json.optJSONObject("usage")
                Out.Ok(
                    text,
                    usage?.optInt("prompt_tokens", 0) ?: 0,
                    usage?.optInt("completion_tokens", 0) ?: 0
                )
            }
        } catch (t: Throwable) {
            Out.Err("返回内容解析失败：${t.message}")
        }
    }

    private fun mapHttpError(code: Int, raw: String): String {
        val serverMsg = runCatching {
            JSONObject(raw).optJSONObject("error")?.optString("message").orEmpty()
        }.getOrDefault("")
        val base = when (code) {
            400 -> "请求被拒（参数问题）"
            401, 403 -> "API Key 无效或已过期"
            402 -> "账户余额不足"
            404 -> "接口地址或模型名不对"
            429 -> "请求太频繁，稍后再试"
            in 500..599 -> "服务端出错（$code）"
            else -> "请求失败（HTTP $code）"
        }
        return if (serverMsg.isBlank()) base else "$base：${serverMsg.take(120)}"
    }

    private fun humanError(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "网络超时（${READ_TIMEOUT / 1000}s），检查网络后重试"
        is java.net.UnknownHostException -> "连不上服务器，检查网络"
        is javax.net.ssl.SSLException -> "HTTPS 握手失败，可能是网络代理问题"
        is IOException -> "网络错误：${t.message}"
        else -> "出错了：${t.message}"
    }

    // ---------------- 图片编码 ----------------

    /**
     * 小图走 PNG（文字更锐），大图压到长边 1080 走 JPEG q85（省 token、上传快）。
     */
    private fun encodeImage(src: Bitmap): String {
        val longEdge = maxOf(src.width, src.height)
        val needScale = longEdge > MAX_LONG_EDGE
        val big = src.width.toLong() * src.height > 1_000_000L

        val bmp = if (needScale) {
            val scale = MAX_LONG_EDGE.toFloat() / longEdge
            Bitmap.createScaledBitmap(
                src,
                (src.width * scale).toInt().coerceAtLeast(1),
                (src.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else src

        val bytes = ByteArrayOutputStream()
        val mime: String
        if (big || needScale) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bytes)
            mime = "image/jpeg"
        } else {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, bytes)
            mime = "image/png"
        }
        if (bmp !== src) bmp.recycle()

        val b64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        Log.i(TAG, "上传图片 ${src.width}x${src.height} → ${mime}，base64 ${b64.length / 1024}KB")
        return "data:$mime;base64,$b64"
    }
}
