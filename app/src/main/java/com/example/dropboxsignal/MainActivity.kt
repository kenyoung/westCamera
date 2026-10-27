package com.example.dropboxsignal

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.dropbox.core.DbxAppInfo
import com.dropbox.core.DbxPKCEWebAuth
import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.DbxWebAuth
import com.dropbox.core.TokenAccessType
import com.dropbox.core.oauth.DbxCredential
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.ListFolderErrorException
import com.dropbox.core.v2.files.WriteMode
import com.example.dropboxsignal.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy { getSharedPreferences("dropboxSignal", Context.MODE_PRIVATE) }
    private val requestConfig = DbxRequestConfig.newBuilder("dropboxSignal/1.0").build()
    private val scopes = listOf("files.content.write", "files.content.read", "files.metadata.read")

    // Fixed set of signal files, all under the detections/ directory at the Dropbox root.
    private val signalDir = "/detections/"
    private val signalFiles = listOf("shutup", "run", "shutdown", "quit")

    // CREATE/DELETE buttons per signal file, so each can be greyed out when its action is a no-op.
    private val createButtons = mutableMapOf<String, Button>()
    private val deleteButtons = mutableMapOf<String, Button>()
    // The buttons' normal text color, captured so a greyed (no-op) button can be restored.
    private var defaultButtonTextColors: ColorStateList? = null

    // Detection photos the desktop program drops here; detection images have exactly six underscores.
    private val vehiclesDir = "/detections/vehicles"
    private val humansDir = "/detections/humans"

    // Holds the PKCE code verifier between opening the browser and pasting the code back.
    private var pkceWebAuth: DbxPKCEWebAuth? = null

    // When true, images are cropped to their bounding box instead of shown whole.
    private var boxOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)  // hosts the 3-dot overflow menu
        // Push the bar below the system status area by 1/12 of the screen height.
        val lp = binding.toolbar.layoutParams as ViewGroup.MarginLayoutParams
        lp.topMargin = resources.displayMetrics.heightPixels / 12
        binding.toolbar.layoutParams = lp

        binding.connectButton.setOnClickListener { openDropboxLogin() }
        binding.finishButton.setOnClickListener { finishDropboxLogin() }
        binding.refreshButton.setOnClickListener { refreshImages() }
        binding.boxOnlyButton.setOnClickListener {
            boxOnly = !boxOnly
            updateBoxDot(boxOnly)
            rerenderImages()
        }
        updateBoxDot(boxOnly)
        buildFileRows()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        refreshImages()
        refreshFileStates()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menuMain -> { showPage(main = true); true }
            R.id.menuConnect -> { showPage(main = false); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Filled dot when "Box only" is on, hollow when off.
    private fun updateBoxDot(checked: Boolean) {
        binding.boxOnlyDot.text = if (checked) "●" else "○"
    }

    private fun showPage(main: Boolean) {
        binding.mainPage.visibility = if (main) View.VISIBLE else View.GONE
        binding.connectPage.visibility = if (main) View.GONE else View.VISIBLE
    }

    // Build one row per signal file: [name]        [Create] [Delete]
    private fun buildFileRows() {
        signalFiles.forEach { name ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            val label = TextView(this).apply {
                text = name
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val createButton = Button(this).apply {
                text = "Create"
                setOnClickListener { runFileOp(name, create = true) }
            }
            val deleteButton = Button(this).apply {
                text = "Delete"
                setOnClickListener { runFileOp(name, create = false) }
            }
            createButtons[name] = createButton
            deleteButtons[name] = deleteButton
            if (defaultButtonTextColors == null) defaultButtonTextColors = createButton.textColors
            row.addView(label)
            row.addView(createButton)
            row.addView(deleteButton)
            binding.fileRows.addView(row)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    // Step 1: build a PKCE authorize URL and open it in the browser.
    private fun openDropboxLogin() {
        val appInfo = DbxAppInfo(BuildConfig.DROPBOX_APP_KEY)   // PKCE uses the app key only, no secret
        val webAuth = DbxPKCEWebAuth(requestConfig, appInfo)
        val authRequest = DbxWebAuth.newRequestBuilder()
            .withNoRedirect()
            .withTokenAccessType(TokenAccessType.OFFLINE)
            .withScope(scopes)
            .build()
        val url = webAuth.authorize(authRequest)
        pkceWebAuth = webAuth
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        connectLog("Browser opened. Approve access, copy the code, paste it below, then tap Finish connecting.")
    }

    // Step 2: exchange the pasted code (plus the stored verifier) for tokens.
    private fun finishDropboxLogin() {
        val code = binding.authCode.text.toString().trim()
        val webAuth = pkceWebAuth
        if (code.isEmpty()) { connectLog("Paste the access code from the browser first."); return }
        if (webAuth == null) { connectLog("Tap 'Open Dropbox login' first, then approve in the browser."); return }

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    val authFinish = webAuth.finishFromCode(code)
                    val credential = DbxCredential(
                        authFinish.accessToken,
                        authFinish.expiresAt,
                        authFinish.refreshToken,
                        BuildConfig.DROPBOX_APP_KEY
                    )
                    prefs.edit().putString("credential", credential.toString()).apply()
                    "Connected to Dropbox."
                } catch (e: Exception) {
                    "Error finishing connection: ${e.message}"
                }
            }
            connectLog(result)
            updateStatus()
            refreshImages()
            refreshFileStates()
        }
    }

    private fun loadCredential(): DbxCredential? {
        val serialized = prefs.getString("credential", null) ?: return null
        return DbxCredential.Reader.readFully(serialized)
    }

    private fun updateStatus() {
        binding.status.text =
            if (loadCredential() != null) "Status: connected" else "Status: not connected"
    }

    private fun runFileOp(name: String, create: Boolean) {
        val credential = loadCredential()
        if (credential == null) { log("Not connected. Connect to Dropbox first."); return }
        val path = signalDir + name

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    val client = DbxClientV2(requestConfig, credential)
                    if (create) {
                        client.files().uploadBuilder(path)
                            .withMode(WriteMode.OVERWRITE)
                            .uploadAndFinish(ByteArrayInputStream(ByteArray(0)))
                    } else {
                        client.files().deleteV2(path)
                    }
                    null  // success: say nothing
                } catch (e: Exception) {
                    "Error on $path: ${e.message}"
                }
            }
            log(error ?: "")  // clear any prior message on success, show the error on failure
            if (error == null) setFileExists(name, exists = create)
        }
    }

    private fun setFileExists(name: String, exists: Boolean) {
        createButtons[name]?.let { greyIfNoop(it, noop = exists) }    // creating an existing file is a no-op
        deleteButtons[name]?.let { greyIfNoop(it, noop = !exists) }   // deleting a missing file is a no-op
    }

    // Grey the button's text when its action would be a no-op; the button stays tappable.
    private fun greyIfNoop(button: Button, noop: Boolean) {
        if (noop) button.setTextColor(Color.GRAY)
        else defaultButtonTextColors?.let { button.setTextColor(it) }
    }

    // Check which signal files currently exist (one folder listing) and grey out the no-op buttons.
    private fun refreshFileStates() {
        val credential = loadCredential() ?: return
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                try {
                    val client = DbxClientV2(requestConfig, credential)
                    val names = mutableSetOf<String>()
                    var result = client.files().listFolder(signalDir.trimEnd('/'))
                    while (true) {
                        for (entry in result.entries) {
                            if (entry is FileMetadata) names.add(entry.name)
                        }
                        if (!result.hasMore) break
                        result = client.files().listFolderContinue(result.cursor)
                    }
                    names
                } catch (e: ListFolderErrorException) {
                    // Missing folder means none of the signal files exist.
                    if (e.errorValue.isPath && e.errorValue.pathValue.isNotFound) emptySet()
                    else null
                } catch (e: Exception) {
                    null
                }
            }
            if (existing != null) {
                signalFiles.forEach { name -> setFileExists(name, existing.contains(name)) }
            }
        }
    }

    private data class Loaded(val name: String?, val bytes: ByteArray?, val box: Rect?, val error: String?)

    // One on-screen detection image (vehicle or human): downloads the newest file, caches its
    // bytes and bounding box, and renders either the whole image or just the box.
    private inner class ImagePanel(
        val dir: String,
        val caption: TextView,
        val image: ImageView,
        val emptyMsg: String
    ) {
        private var bytes: ByteArray? = null
        private var box: Rect? = null
        var shown = false  // true while this panel is displaying an actual image

        private fun empty() {
            shown = false
            showEmpty(caption, image, emptyMsg)
        }

        // Download the newest detection image, cache its bytes + box, then render.
        fun refresh() {
            val credential = loadCredential()
            if (credential == null) { bytes = null; box = null; empty(); return }
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    try {
                        val client = DbxClientV2(requestConfig, credential)
                        val latest = findLatestFile(client, dir)
                            ?: return@withContext Loaded(null, null, null, null)
                        val out = ByteArrayOutputStream()
                        client.files().download(latest.pathLower).download(out)
                        Loaded(latest.name, out.toByteArray(), parseBox(latest.name), null)
                    } catch (e: ListFolderErrorException) {
                        // A missing folder just means nothing has been detected yet.
                        if (e.errorValue.isPath && e.errorValue.pathValue.isNotFound)
                            Loaded(null, null, null, null)
                        else Loaded(null, null, null, e.message)
                    } catch (e: Exception) {
                        Loaded(null, null, null, e.message)
                    }
                }
                if (loaded.error != null) {
                    bytes = null; box = null
                    empty()
                    log("Error loading $dir: ${loaded.error}")
                    return@launch
                }
                bytes = loaded.bytes
                box = loaded.box
                render()
            }
        }

        // Re-decode the cached bytes in the current mode; no network, so toggling is instant.
        fun render() {
            val data = bytes ?: run { empty(); return }
            val useBox = boxOnly
            val rect = box
            lifecycleScope.launch {
                val bmp = withContext(Dispatchers.Default) {
                    try {
                        if (useBox && rect != null) decodeRegion(data, rect, 1400)
                        else decodeSampled(data, 1400)
                    } catch (e: Exception) { null }
                }
                if (bmp != null) {
                    caption.visibility = View.GONE
                    image.setImageBitmap(bmp)
                    image.visibility = View.VISIBLE
                    shown = true
                    clearLogIfAllShown()
                } else {
                    empty()
                }
            }
        }
    }

    private val vehiclePanel by lazy {
        ImagePanel(vehiclesDir, binding.vehicleCaption, binding.vehicleImage, "No vehicles detected")
    }
    private val humanPanel by lazy {
        ImagePanel(humansDir, binding.humanCaption, binding.humanImage, "No humans detected")
    }

    // Refresh both images from Dropbox (newest vehicle, then newest human below it).
    private fun refreshImages() {
        vehiclePanel.refresh()
        humanPanel.refresh()
    }

    // Re-render both already-downloaded images when the "Box only" toggle changes (no download).
    private fun rerenderImages() {
        vehiclePanel.render()
        humanPanel.render()
    }

    // Once both panels show real images, wipe any stale error left in the log line.
    private fun clearLogIfAllShown() {
        if (vehiclePanel.shown && humanPanel.shown) log("")
    }

    // Bounding box encoded in the filename: ..._<x1>_<y1>_<x2>_<y2>_... (pixels, upper-left corner
    // then lower-right corner). Returns null if the numbers are missing or don't form a valid box.
    private fun parseBox(name: String): Rect? {
        val parts = name.split("_")
        if (parts.size < 6) return null
        val x1 = parts[2].toIntOrNull() ?: return null
        val y1 = parts[3].toIntOrNull() ?: return null
        val x2 = parts[4].toIntOrNull() ?: return null
        val y2 = parts[5].toIntOrNull() ?: return null
        if (x2 <= x1 || y2 <= y1) return null
        return Rect(x1, y1, x2, y2)
    }

    // Decode only the bounding-box region, downsampled to fit, straight from the encoded bytes.
    private fun decodeRegion(bytes: ByteArray, box: Rect, maxDim: Int): Bitmap? {
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false) ?: return null
        try {
            val rect = Rect(
                box.left.coerceIn(0, decoder.width),
                box.top.coerceIn(0, decoder.height),
                box.right.coerceIn(0, decoder.width),
                box.bottom.coerceIn(0, decoder.height)
            )
            if (rect.width() <= 0 || rect.height() <= 0) return null
            var sample = 1
            val largest = maxOf(rect.width(), rect.height())
            while (largest / sample > maxDim) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            return decoder.decodeRegion(rect, opts)
        } finally {
            decoder.recycle()
        }
    }

    private fun showEmpty(caption: TextView, image: ImageView, emptyMsg: String) {
        image.visibility = View.GONE
        image.setImageDrawable(null)
        caption.visibility = View.VISIBLE
        caption.text = emptyMsg
    }

    // Walk the whole folder (paginated) and keep the newest matching image by upload time.
    private fun findLatestFile(client: DbxClientV2, dir: String): FileMetadata? {
        var latest: FileMetadata? = null
        var result = client.files().listFolder(dir)
        while (true) {
            for (entry in result.entries) {
                if (entry is FileMetadata && isDetectionImage(entry.name)) {
                    if (latest == null || entry.serverModified.after(latest!!.serverModified)) {
                        latest = entry
                    }
                }
            }
            if (!result.hasMore) break
            result = client.files().listFolderContinue(result.cursor)
        }
        return latest
    }

    private fun isDetectionImage(name: String): Boolean {
        if (name.count { it == '_' } != 6) return false
        val lower = name.lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp")
    }

    // Decode downsampled so large photos don't exhaust memory.
    private fun decodeSampled(bytes: ByteArray, maxDim: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        val largest = maxOf(bounds.outWidth, bounds.outHeight)
        while (largest / sample > maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    private fun log(message: String) {
        binding.log.text = message
        binding.log.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun connectLog(message: String) {
        binding.connectLog.text = message
    }
}
