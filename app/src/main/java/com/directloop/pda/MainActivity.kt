package com.directloop.pda

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var rootLayout: FrameLayout
    private lateinit var webView: WebView
    private lateinit var loadingContainer: LinearLayout
    private lateinit var offlineContainer: LinearLayout
    private lateinit var currentUrlText: TextView

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null
    private var pendingWebPermissionRequest: PermissionRequest? = null
    private var lastRequestedUrl = BuildConfig.FW_ERP_APP_URL
    private var lastFailedUrl = BuildConfig.FW_ERP_APP_URL
    private var mainFrameLoadFailed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureWindow()

        rootLayout = FrameLayout(this)
        configureKeyboardInsets()
        webView = WebView(this)
        loadingContainer = buildLoadingView()
        offlineContainer = buildOfflineView()

        rootLayout.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        rootLayout.addView(
            offlineContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        rootLayout.addView(
            loadingContainer,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        setContentView(rootLayout)
        hideSystemUi()
        configureWebView()

        if (savedInstanceState == null) {
            webView.loadUrl(BuildConfig.FW_ERP_APP_URL)
        } else {
            webView.restoreState(savedInstanceState)
            hideLoadingScreen()
        }
    }

    private fun configureWindow() {
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
    }

    private fun configureKeyboardInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }

        rootLayout.setOnApplyWindowInsetsListener { view, insets ->
            val imeBottom = if (insets.isVisible(WindowInsets.Type.ime())) {
                insets.getInsets(WindowInsets.Type.ime()).bottom
            } else {
                0
            }
            view.setPadding(0, 0, 0, imeBottom)
            insets
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            return
        }

        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }

    private fun configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = true
            userAgentString = "$userAgentString DirectLoopPDA/1.0"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // Production /app/ is HTTPS. Allow mixed content only if a future deployment requires it.
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            }
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                setAcceptThirdPartyCookies(webView, true)
            }
        }

        webView.webViewClient = DirectLoopWebViewClient()
        webView.webChromeClient = DirectLoopWebChromeClient()
    }

    private fun buildLoadingView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.rgb(15, 23, 42))
            visibility = View.VISIBLE
        }

        val title = TextView(this).apply {
            text = SPLASH_TITLE
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val progressBar = ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(0, 28, 0, 12)
        }

        val status = TextView(this).apply {
            text = getString(R.string.loading)
            textSize = 15f
            setTextColor(Color.rgb(203, 213, 225))
            gravity = Gravity.CENTER
        }

        container.addView(title)
        container.addView(progressBar)
        container.addView(status)
        return container
    }

    private fun buildOfflineView(): LinearLayout {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
            setBackgroundColor(Color.WHITE)
            visibility = View.GONE
        }

        val title = TextView(this).apply {
            text = getString(R.string.offline_title)
            textSize = 24f
            setTextColor(Color.rgb(15, 23, 42))
            gravity = Gravity.CENTER
        }

        val body = TextView(this).apply {
            text = getString(R.string.offline_body)
            textSize = 15f
            setTextColor(Color.rgb(71, 85, 105))
            gravity = Gravity.CENTER
            setPadding(0, 18, 0, 24)
        }

        currentUrlText = TextView(this).apply {
            text = getString(R.string.current_url, BuildConfig.FW_ERP_APP_URL)
            textSize = 13f
            setTextColor(Color.rgb(100, 116, 139))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 24)
        }

        val retryButton = Button(this).apply {
            text = getString(R.string.retry)
            setOnClickListener {
                retryLastUrl()
            }
        }

        container.addView(title)
        container.addView(body)
        container.addView(currentUrlText)
        container.addView(retryButton)
        return container
    }

    private fun showLoadingScreen() {
        loadingContainer.visibility = View.VISIBLE
        loadingContainer.bringToFront()
    }

    private fun hideLoadingScreen() {
        loadingContainer.visibility = View.GONE
    }

    private fun showOfflineScreen(url: String = lastRequestedUrl) {
        lastFailedUrl = url.ifBlank { BuildConfig.FW_ERP_APP_URL }
        currentUrlText.text = getString(R.string.current_url, lastFailedUrl)
        offlineContainer.visibility = View.VISIBLE
        offlineContainer.bringToFront()
    }

    private fun hideOfflineScreen() {
        offlineContainer.visibility = View.GONE
    }

    private fun retryLastUrl() {
        hideOfflineScreen()
        showLoadingScreen()
        webView.loadUrl(lastFailedUrl.ifBlank { BuildConfig.FW_ERP_APP_URL })
    }

    private inner class DirectLoopWebViewClient : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            if (uri.scheme == "http" || uri.scheme == "https") {
                if (uri.host == BuildConfig.FW_ERP_HOST) {
                    return false
                }
                openExternalUrl(uri)
                return true
            }
            openExternalUrl(uri)
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            lastRequestedUrl = url
            mainFrameLoadFailed = false
            hideOfflineScreen()
            showLoadingScreen()
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView, url: String) {
            CookieManager.getInstance().flush()
            if (!mainFrameLoadFailed) {
                hideOfflineScreen()
                hideLoadingScreen()
            }
            super.onPageFinished(view, url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) {
                mainFrameLoadFailed = true
                hideLoadingScreen()
                showOfflineScreen(request.url.toString())
            }
            super.onReceivedError(view, request, error)
        }

        override fun onReceivedHttpError(
            view: WebView,
            request: WebResourceRequest,
            errorResponse: WebResourceResponse,
        ) {
            if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                mainFrameLoadFailed = true
                hideLoadingScreen()
                showOfflineScreen(request.url.toString())
            }
            super.onReceivedHttpError(view, request, errorResponse)
        }
    }

    private inner class DirectLoopWebChromeClient : WebChromeClient() {
        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean {
            this@MainActivity.filePathCallback?.onReceiveValue(null)
            this@MainActivity.filePathCallback = filePathCallback

            if (hasCameraPermission()) {
                launchFileChooser(includeCamera = true, fileChooserParams = fileChooserParams)
            } else {
                requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQUEST_CODE)
            }
            return true
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (!isTrustedOrigin(request.origin)) {
                request.deny()
                return
            }

            val wantsCamera = request.resources.contains(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
            if (wantsCamera && !hasCameraPermission()) {
                pendingWebPermissionRequest = request
                requestPermissions(arrayOf(Manifest.permission.CAMERA), WEB_CAMERA_PERMISSION_REQUEST_CODE)
                return
            }

            request.grant(request.resources)
        }
    }

    private fun launchFileChooser(
        includeCamera: Boolean,
        fileChooserParams: WebChromeClient.FileChooserParams? = null,
    ) {
        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = fileChooserParams?.acceptTypes?.firstOrNull { it.isNotBlank() } ?: "image/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams?.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
        }

        val chooserIntents = if (includeCamera) {
            createCameraIntent()?.let { arrayOf(it) } ?: emptyArray()
        } else {
            emptyArray()
        }

        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentIntent)
            putExtra(Intent.EXTRA_INITIAL_INTENTS, chooserIntents)
        }

        try {
            startActivityForResult(chooser, FILE_CHOOSER_REQUEST_CODE)
        } catch (_: ActivityNotFoundException) {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
        }
    }

    private fun createCameraIntent(): Intent? {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(packageManager) == null) {
            return null
        }

        val photoFile = createCameraFile()
        val photoUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", photoFile)
        pendingCameraUri = photoUri

        return cameraIntent.apply {
            putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun createCameraFile(): File {
        val cameraDir = File(cacheDir, "camera").apply { mkdirs() }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return File(cameraDir, "direct_loop_pda_$timestamp.jpg")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != FILE_CHOOSER_REQUEST_CODE) {
            return
        }

        val callback = filePathCallback ?: return
        val result = if (resultCode == RESULT_OK) {
            parseFileChooserResult(data)
        } else {
            null
        }
        callback.onReceiveValue(result)
        filePathCallback = null
        pendingCameraUri = null
    }

    private fun parseFileChooserResult(data: Intent?): Array<Uri>? {
        val clipData = data?.clipData
        if (clipData != null) {
            return Array(clipData.itemCount) { index -> clipData.getItemAt(index).uri }
        }

        data?.data?.let { return arrayOf(it) }
        pendingCameraUri?.let { return arrayOf(it) }
        return null
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val cameraGranted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED

        when (requestCode) {
            CAMERA_PERMISSION_REQUEST_CODE -> {
                if (filePathCallback != null) {
                    launchFileChooser(includeCamera = cameraGranted)
                }
            }

            WEB_CAMERA_PERMISSION_REQUEST_CODE -> {
                pendingWebPermissionRequest?.let { request ->
                    if (cameraGranted) request.grant(request.resources) else request.deny()
                    pendingWebPermissionRequest = null
                }
            }
        }
    }

    private fun hasCameraPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun isTrustedOrigin(origin: Uri): Boolean {
        return origin.scheme == "https" && origin.host == BuildConfig.FW_ERP_HOST
    }

    private fun openExternalUrl(uri: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (_: ActivityNotFoundException) {
            // Keep the PDA shell focused if the device has no handler for an external URL.
        }
    }

    override fun onBackPressed() {
        // WebView canGoBack before exiting the Android shell.
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUi()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        rootLayout.removeView(webView)
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        private const val SPLASH_TITLE = "Direct Loop PDA"
        private const val FILE_CHOOSER_REQUEST_CODE = 1001
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1002
        private const val WEB_CAMERA_PERMISSION_REQUEST_CODE = 1003
    }
}
