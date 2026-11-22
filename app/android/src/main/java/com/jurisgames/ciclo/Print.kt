package com.jurisgames.ciclo

import android.app.Activity
import android.print.PrintAttributes
import android.print.PrintManager
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * The report for a doctor: the page laid out by a WebView that never touches the network (the HTML is ours, made
 * on the phone, with nothing to load) and handed to Android's print service: a printer, or Save as PDF.
 */
object Print {
    // held until printing has the page, or the WebView is collected before it finishes
    private var view: WebView? = null

    fun page(activity: Activity, name: String, html: String) {
        val w = WebView(activity)
        w.settings.javaScriptEnabled = false
        w.settings.allowFileAccess = false
        w.settings.allowContentAccess = false
        w.settings.blockNetworkLoads = true
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView, r: android.webkit.WebResourceRequest) = true
            override fun onPageFinished(v: WebView, url: String) {
                val pm = activity.getSystemService(PrintManager::class.java)
                pm.print(name, v.createPrintDocumentAdapter(name), PrintAttributes.Builder().setMediaSize(PrintAttributes.MediaSize.ISO_A4).build())
                view = null
            }
        }
        view = w
        w.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }
}
