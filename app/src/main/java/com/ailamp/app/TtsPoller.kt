package com.ailamp.app

import android.webkit.WebView

class TtsPoller(
    private val webView: WebView,
    private val ttsBridge: TtsBridge
) {
    private var running = false

    fun start() {
        running = true
    }

    fun stop() {
        running = false
    }
}
