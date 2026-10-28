package com.example.dropboxsignal

import android.annotation.SuppressLint
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
import android.view.GestureDetector
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
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
import com.dropbox.core.v2.files.DeleteArg
import com.dropbox.core.v2.files.FileMetadata
import com.dropbox.core.v2.files.ListFolderErrorException
import com.dropbox.core.v2.files.WriteMode
import com.example.dropboxsignal.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import kotlin.math.abs

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

        binding.pruneVehWeek.setOnClickListener { pruneOlderThan(vehiclesDir, "vehicle", "1 week", Duration.ofDays(7)) }
        binding.pruneVehDay.setOnClickListener { pruneOlderThan(vehiclesDir, "vehicle", "1 day", Duration.ofDays(1)) }
        binding.pruneHumWeek.setOnClickListener { pruneOlderThan(humansDir, "human", "1 week", Duration.ofDays(7)) }
        binding.pruneHumDay.setOnClickListener { pruneOlderThan(humansDir, "human", "1 day", Duration.ofDays(1)) }

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
            R.id.menuMain -> { showPage(Page.MAIN); true }
            R.id.menuConnect -> { showPage(Page.CONNECT); true }
            R.id.menuPrune -> { showPage(Page.PRUNE); true }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // Filled dot when "Box only" is on, hollow when off.
    private fun updateBoxDot(checked: Boolean) {
        binding.boxOnlyDot.text = if (checked) "●" else "○"
    }

    private enum class Page { MAIN, CONNECT, PRUNE }

    private fun showPage(page: Page) {
        binding.mainPage.visibility = if (page == Page.MAIN) View.VISIBLE else View.GONE
        binding.connectPage.visibility = if (page == Page.CONNECT) View.VISIBLE else View.GONE
        binding.prunePage.visibility = if (page == Page.PRUNE) View.VISIBLE else View.GONE
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

    // A Dropbox client built from the saved credential, or null if not connected. Cheap to build
    // (no network); rebuilt per use so a refreshed credential is always picked up.
    private fun dbxClient(): DbxClientV2? = loadCredential()?.let { DbxClientV2(requestConfig, it) }

    private fun runFileOp(name: String, create: Boolean) {
        val client = dbxClient()
        if (client == null) { log("Not connected. Connect to Dropbox first."); return }
        val path = signalDir + name

        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
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
        val client = dbxClient() ?: return
        lifecycleScope.launch {
            val existing = withContext(Dispatchers.IO) {
                try {
                    listAllFiles(client, signalDir.trimEnd('/')).map { it.name }.toSet()
                } catch (e: Exception) {
                    null  // leave button states unchanged on a transient error
                }
            }
            if (existing != null) {
                signalFiles.forEach { name -> setFileExists(name, existing.contains(name)) }
            }
        }
    }

    private data class Loaded(val bytes: ByteArray?, val box: Rect?, val error: String?)

    // One on-screen detection image (vehicle or human): holds the folder's detection files newest
    // first, downloads the selected one, and renders either the whole image or just the box.
    // Swiping left shows an older image, right a newer one.
    @SuppressLint("ClickableViewAccessibility")
    private inner class ImagePanel(
        val dir: String,
        val caption: TextView,
        val image: ImageView,
        val timeView: TextView,
        val searchingView: TextView,
        val emptyMsg: String
    ) {
        private var entries: List<FileMetadata> = emptyList()  // detection images, newest first
        private var index = 0                                  // which entry is currently shown
        private var bytes: ByteArray? = null
        private var box: Rect? = null
        private var currentName: String? = null                // filename of the shown image, for its age
        private var onRefreshDone: (() -> Unit)? = null         // fired once when a refresh fully settles

        init {
            // Detect horizontal flings on the image: left -> older, right -> newer.
            val detector = GestureDetector(this@MainActivity, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    val start = e1 ?: return false
                    val dx = e2.x - start.x
                    val dy = e2.y - start.y
                    if (abs(dx) > abs(dy) && abs(dx) > dp(40)) {
                        navigate(if (dx < 0) 1 else -1)  // swipe left = older (+1), right = newer (-1)
                        return true
                    }
                    return false
                }
            })
            image.setOnTouchListener { _, event -> detector.onTouchEvent(event) }
        }

        // Reload the folder's detection files (newest first) and show the most recent one.
        // onDone fires exactly once when this refresh has fully settled (rendered, empty, or errored).
        fun refresh(onDone: () -> Unit = {}) {
            onRefreshDone = onDone
            val client = dbxClient()
            if (client == null) { clear(); showEmpty(caption, image, timeView, emptyMsg); finishRefresh(); return }
            lifecycleScope.launch {
                val outcome = withContext(Dispatchers.IO) {
                    runCatching { detectionImages(client, dir) }
                }
                val list = outcome.getOrNull()
                if (list == null) {
                    clear()
                    showEmpty(caption, image, timeView, emptyMsg)
                    log("Error loading $dir: ${outcome.exceptionOrNull()?.message}")
                    finishRefresh()
                    return@launch
                }
                entries = list
                index = 0
                if (entries.isEmpty()) {
                    bytes = null; box = null; currentName = null
                    showEmpty(caption, image, timeView, emptyMsg)
                    finishRefresh()
                } else showIndex(0)
            }
        }

        // Called at every terminal point of a load (rendered, empty, or errored): clears the swipe
        // "searching" marker, and fires the pending refresh callback once (null for swipe/toggle loads).
        private fun finishRefresh() {
            searchingView.visibility = View.GONE
            onRefreshDone?.invoke()
            onRefreshDone = null
        }

        // Move by delta positions if an entry exists there (older for +1, newer for -1); else ignore.
        fun navigate(delta: Int) {
            val target = index + delta
            if (target in entries.indices) {
                index = target
                searchingView.visibility = View.VISIBLE  // cleared when the load settles (finishRefresh)
                showIndex(target)
            }
        }

        // Download entries[i], cache its bytes + box, then render.
        private fun showIndex(i: Int) {
            val client = dbxClient() ?: run { clear(); showEmpty(caption, image, timeView, emptyMsg); finishRefresh(); return }
            val entry = entries[i]
            currentName = entry.name
            lifecycleScope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    try {
                        val out = ByteArrayOutputStream()
                        client.files().download(entry.pathLower).download(out)
                        Loaded(out.toByteArray(), parseBox(entry.name), null)
                    } catch (e: Exception) {
                        Loaded(null, null, e.message)
                    }
                }
                if (loaded.error != null) {
                    bytes = null; box = null
                    showEmpty(caption, image, timeView, emptyMsg)
                    log("Error loading $dir: ${loaded.error}")
                    finishRefresh()
                    return@launch
                }
                bytes = loaded.bytes
                box = loaded.box
                render()
            }
        }

        private fun clear() {
            entries = emptyList()
            index = 0
            bytes = null
            box = null
            currentName = null
        }

        // Re-decode the cached bytes in the current mode; no network, so toggling is instant.
        fun render() {
            val data = bytes ?: run { showEmpty(caption, image, timeView, emptyMsg); finishRefresh(); return }
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
                    val ago = currentName?.let { timeAgo(it) }
                    if (ago != null) { timeView.text = ago; timeView.visibility = View.VISIBLE }
                    else timeView.visibility = View.GONE
                } else {
                    showEmpty(caption, image, timeView, emptyMsg)
                }
                finishRefresh()
            }
        }
    }

    private val vehiclePanel by lazy {
        ImagePanel(vehiclesDir, binding.vehicleCaption, binding.vehicleImage, binding.vehicleTime, binding.vehicleSearching, "No vehicles detected")
    }
    private val humanPanel by lazy {
        ImagePanel(humansDir, binding.humanCaption, binding.humanImage, binding.humanTime, binding.humanSearching, "No humans detected")
    }

    // Refresh both images from Dropbox (newest vehicle, then newest human below it). The Refresh
    // button is greyed out until both panels have fully rendered, so its delay is visible.
    private fun refreshImages() {
        log("")  // drop any stale message; an error from this refresh will re-appear
        binding.refreshButton.isEnabled = false
        binding.refreshButton.text = "Refreshing"
        var remaining = 2
        val onPanelDone = {
            if (--remaining == 0) {
                binding.refreshButton.isEnabled = true
                binding.refreshButton.text = "Refresh"
            }
        }
        vehiclePanel.refresh(onPanelDone)
        humanPanel.refresh(onPanelDone)
    }

    // Re-render both already-downloaded images when the "Box only" toggle changes (no download).
    private fun rerenderImages() {
        vehiclePanel.render()
        humanPanel.render()
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
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(maxOf(rect.width(), rect.height()), maxDim)
            }
            return decoder.decodeRegion(rect, opts)
        } finally {
            decoder.recycle()
        }
    }

    private fun showEmpty(caption: TextView, image: ImageView, timeView: TextView, emptyMsg: String) {
        image.visibility = View.GONE
        image.setImageDrawable(null)
        timeView.visibility = View.GONE
        caption.visibility = View.VISIBLE
        caption.text = emptyMsg
    }

    // The camera always stamps filenames in Central Time (with DST), so interpret the time in that
    // zone; the difference to Instant.now() is then correct wherever the phone happens to be.
    private val cameraZone = ZoneId.of("America/Chicago")

    // "HH:MM ago" under a day, "one day, HH:MM ago" under two, else "X days, HH:MM ago".
    // The age comes from the filename's leading YYYYMMDD_HHMMSS creation time; null if unparseable.
    private fun timeAgo(name: String): String? {
        val parts = name.split("_")
        if (parts.size < 2) return null
        val fileTime = try {
            LocalDateTime.parse(parts[0] + "_" + parts[1], DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        } catch (e: Exception) {
            return null
        }
        val fileInstant = fileTime.atZone(cameraZone).toInstant()
        val minutes = Duration.between(fileInstant, Instant.now()).toMinutes().coerceAtLeast(0)
        val days = minutes / (24 * 60)
        val hh = minutes % (24 * 60) / 60
        val mm = minutes % 60
        return when (days) {
            0L -> "%02d:%02d ago".format(hh, mm)
            1L -> "one day, %02d:%02d ago".format(hh, mm)
            else -> "%d days, %02d:%02d ago".format(days, hh, mm)
        }
    }

    // List every file directly in a folder, paging through all results. A missing folder is empty.
    private fun listAllFiles(client: DbxClientV2, dir: String): List<FileMetadata> {
        val files = mutableListOf<FileMetadata>()
        var result = try {
            client.files().listFolder(dir)
        } catch (e: ListFolderErrorException) {
            if (e.errorValue.isPath && e.errorValue.pathValue.isNotFound) return emptyList()
            throw e
        }
        while (true) {
            for (entry in result.entries) if (entry is FileMetadata) files.add(entry)
            if (!result.hasMore) break
            result = client.files().listFolderContinue(result.cursor)
        }
        return files
    }

    // A folder's detection images (filenames with six underscores), newest upload first.
    private fun detectionImages(client: DbxClientV2, dir: String): List<FileMetadata> =
        listAllFiles(client, dir).filter { isDetectionImage(it.name) }.sortedByDescending { it.serverModified }

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
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(maxOf(bounds.outWidth, bounds.outHeight), maxDim)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    // Largest power-of-two inSampleSize that keeps the longer side at or under maxDim.
    private fun sampleSizeFor(largest: Int, maxDim: Int): Int {
        var sample = 1
        while (largest / sample > maxDim) sample *= 2
        return sample
    }

    private fun log(message: String) {
        binding.log.text = message
        binding.log.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun connectLog(message: String) {
        binding.connectLog.text = message
    }

    private val pruneButtons by lazy {
        listOf(binding.pruneVehWeek, binding.pruneVehDay, binding.pruneHumWeek, binding.pruneHumDay)
    }

    // Delete every file in dir whose Dropbox timestamp is older than maxAge (all files, regardless of
    // the 6-underscore detection-image naming). Deletes immediately, then reports how many went.
    private fun pruneOlderThan(dir: String, kind: String, period: String, maxAge: Duration) {
        val client = dbxClient()
        if (client == null) { binding.pruneLog.text = "Not connected. Connect to Dropbox first."; return }
        pruneButtons.forEach { it.isEnabled = false }
        binding.pruneLog.text = "Pruning $kind files older than $period…"
        val cutoff = Date(System.currentTimeMillis() - maxAge.toMillis())

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    // serverModified is Dropbox's own record of when the file arrived.
                    val old = listAllFiles(client, dir).filter { it.serverModified.before(cutoff) }
                    if (old.isEmpty()) "No $kind files older than $period."
                    else "Deleted ${deleteFiles(client, old.mapNotNull { it.pathLower })} $kind files older than $period."
                } catch (e: Exception) {
                    "Error pruning $kind files: ${e.message}"
                }
            }
            binding.pruneLog.text = result
            pruneButtons.forEach { it.isEnabled = true }
            refreshImages()  // the newest image may have just been deleted
        }
    }

    // Delete the given paths via Dropbox's batch API (chunked, since one batch is capped at 1000),
    // waiting for each batch to finish. Returns the number actually deleted.
    private fun deleteFiles(client: DbxClientV2, paths: List<String>): Int {
        var deleted = 0
        for (chunk in paths.chunked(1000)) {
            val launch = client.files().deleteBatch(chunk.map { DeleteArg(it) })
            val result = when {
                launch.isComplete -> launch.completeValue
                launch.isAsyncJobId -> {
                    var status = client.files().deleteBatchCheck(launch.asyncJobIdValue)
                    while (status.isInProgress) {
                        Thread.sleep(500)
                        status = client.files().deleteBatchCheck(launch.asyncJobIdValue)
                    }
                    if (status.isComplete) status.completeValue else null
                }
                else -> null
            }
            deleted += result?.entries?.count { it.isSuccess } ?: 0
        }
        return deleted
    }
}
