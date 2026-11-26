package com.ailamp.app

import android.content.Context
import android.util.AttributeSet
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView

class ChatWebView(context: Context, attrs: AttributeSet) : WebView(context, attrs) {
    private val keyboardScript by lazy {
        context.assets.open("chat_keyboard.js").bufferedReader().use { it.readText() }
    }
    private var keyboardHidden = false

    fun setKeyboardHidden(hidden: Boolean) {
        if (keyboardHidden == hidden) return
        keyboardHidden = hidden
        applyKeyboardSetting()
        if (hidden) {
            val inputMethods = context.getSystemService(InputMethodManager::class.java)
            windowToken?.let { inputMethods?.hideSoftInputFromWindow(it, 0) }
        }
    }

    fun applyKeyboardSetting() {
        evaluateJavascript("$keyboardScript($keyboardHidden);", null)
    }
}
