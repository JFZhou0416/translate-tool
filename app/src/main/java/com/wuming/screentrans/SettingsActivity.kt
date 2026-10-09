package com.wuming.screentrans

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** 设置页：填 key、调模型参数、看用量。全部落 Prefs。 */
class SettingsActivity : Activity() {

    private lateinit var keyField: EditText
    private lateinit var modelField: EditText
    private lateinit var endpointField: EditText
    private lateinit var maxTokensField: EditText
    private lateinit var temperatureField: EditText
    private lateinit var capField: EditText
    private lateinit var glossaryField: EditText
    private lateinit var thinkingCheck: CheckBox
    private lateinit var debugCheck: CheckBox
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = Ui.column(this, 18f)
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(Ui.text(this, "设置", 22f, bold = true))
        root.addView(Ui.space(this, 14f))

        val card = Ui.card(this)
        keyField = field(card, "API Key", Prefs.apiKey(this), password = true)
        modelField = field(card, "模型", Prefs.model(this))
        endpointField = field(card, "接口地址", Prefs.endpoint(this))
        maxTokensField = field(card, "max_tokens", Prefs.maxTokens(this).toString(), number = true)
        temperatureField = field(card, "temperature", Prefs.temperature(this).toString(), decimal = true)
        capField = field(
            card, "每日调用上限（0 = 不限）", Prefs.dailyCap(this).toString(), number = true
        )

        // 术语表：实测模型对同一个词会飘（vault 在「库 / 仓库」之间），人名尤其容易一个人名几种写法
        card.addView(Ui.text(this, "术语表（一行一条，原文=译名）", 13f, Ui.GRAY))
        glossaryField = EditText(this).apply {
            setText(Prefs.glossary(this@SettingsActivity))
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            hint = "vault=仓库\n五条悟=五条悟\n宿儺=宿傩"
            minLines = 3
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        card.addView(glossaryField)
        card.addView(Ui.space(this, 4f))
        card.addView(
            Ui.text(
                this,
                "留空则用原始 Prompt。各语言通用（英文术语、日文人名都行），想固定成什么就写什么。\n" +
                    "人多的话在别处把清单写好，长按输入框粘贴进来即可（支持多行）。\n" +
                    "实测：不加表时 `vault` 稳定译成「库」，加上 `vault=仓库` 立刻稳定复现基线，代价约 +16 输入 token。",
                11f, Ui.GRAY
            )
        )
        card.addView(Ui.space(this, 8f))

        thinkingCheck = CheckBox(this).apply {
            text = "关闭思考（强烈建议开，实测 3.9s → 1.7s）"
            textSize = 14f
            isChecked = Prefs.thinkingDisabled(this@SettingsActivity)
        }
        card.addView(thinkingCheck)

        debugCheck = CheckBox(this).apply {
            text = "调试：把截图存到 App 目录（只存本机，供 adb pull 检查）"
            textSize = 14f
            isChecked = Prefs.pngDebug(this@SettingsActivity)
        }
        card.addView(debugCheck)
        card.addView(Ui.space(this, 6f))
        card.addView(
            Ui.text(
                this,
                "key 用 AndroidKeyStore 的 AES-GCM 加密后存在本机，不会进安装包。",
                12f, Ui.GRAY
            )
        )
        root.addView(card)

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btnRow.addView(Ui.button(this, "保存", primary = true) { save() })
        btnRow.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(Ui.dp(this@SettingsActivity, 8f), 1) })
        btnRow.addView(Ui.button(this, "测试连接") { test() })
        root.addView(btnRow)

        root.addView(Ui.space(this, 12f))
        statusView = Ui.text(this, "", 13f, Ui.GRAY)
        root.addView(statusView)

        root.addView(Ui.space(this, 18f))
        root.addView(
            Ui.text(
                this,
                "今日已用 ${Prefs.usedToday(this)} 次。\n" +
                    "识图 Prompt 已内置（保持原版式顺序 + 输出【原文】/【译文】），术语按上下文理解。",
                12f, Ui.GRAY
            )
        )
    }

    private fun field(
        parent: LinearLayout,
        label: String,
        value: String,
        password: Boolean = false,
        number: Boolean = false,
        decimal: Boolean = false
    ): EditText {
        parent.addView(Ui.text(this, label, 13f, Ui.GRAY))
        val et = EditText(this).apply {
            setText(value)
            textSize = 14f
            inputType = when {
                password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                decimal -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                number -> InputType.TYPE_CLASS_NUMBER
                else -> InputType.TYPE_CLASS_TEXT
            }
            setSingleLine()
        }
        parent.addView(et)
        parent.addView(Ui.space(this, 6f))
        return et
    }

    private fun save() {
        Prefs.setApiKey(this, keyField.text.toString())
        Prefs.setModel(this, modelField.text.toString())
        Prefs.setEndpoint(this, endpointField.text.toString())
        Prefs.setMaxTokens(this, maxTokensField.text.toString().toIntOrNull() ?: Prefs.DEFAULT_MAX_TOKENS)
        Prefs.setTemperature(this, temperatureField.text.toString().toFloatOrNull() ?: Prefs.DEFAULT_TEMPERATURE)
        Prefs.setDailyCap(this, capField.text.toString().toIntOrNull() ?: Prefs.DEFAULT_DAILY_CAP)
        Prefs.setGlossary(this, glossaryField.text.toString())
        Prefs.setThinkingDisabled(this, thinkingCheck.isChecked)
        Prefs.setPngDebug(this, debugCheck.isChecked)
        toast("已保存")
        statusView.text = "已保存。模型 ${Prefs.model(this)}，关思考 ${if (Prefs.thinkingDisabled(this)) "开" else "关"}"
    }

    private fun test() {
        save()
        if (Prefs.apiKey(this).isBlank()) {
            statusView.text = "还没填 API Key"
            statusView.setTextColor(Ui.RED)
            return
        }
        statusView.setTextColor(Ui.GRAY)
        statusView.text = "测试中…（会真实消耗一次调用）"
        val t0 = android.os.SystemClock.uptimeMillis()
        DeepSeekClient.translateText(this, "Hello, world.") { out ->
            val ms = android.os.SystemClock.uptimeMillis() - t0
            when (out) {
                is DeepSeekClient.Out.Ok -> {
                    statusView.setTextColor(Ui.GREEN)
                    statusView.text = "连通 OK（${ms}ms，输入 ${out.promptTokens} / 输出 ${out.completionTokens} tokens）\n${out.text}"
                }

                is DeepSeekClient.Out.Err -> {
                    statusView.setTextColor(Ui.RED)
                    statusView.text = "失败：${out.message}"
                }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
