package com.example.dropboxsignal

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
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
import com.dropbox.core.v2.files.WriteMode
import com.example.dropboxsignal.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private val prefs by lazy { getSharedPreferences("dropboxSignal", Context.MODE_PRIVATE) }
    private val requestConfig = DbxRequestConfig.newBuilder("dropboxSignal/1.0").build()
    private val scopes = listOf("files.content.write", "files.metadata.read")

    // Fixed set of signal files, all under the detections/ directory at the Dropbox root.
    private val signalDir = "/detections/"
    private val signalFiles = listOf("shutup", "run", "shutdown", "quit")

    // Holds the PKCE code verifier between opening the browser and pasting the code back.
    private var pkceWebAuth: DbxPKCEWebAuth? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.connectButton.setOnClickListener { openDropboxLogin() }
        binding.finishButton.setOnClickListener { finishDropboxLogin() }
        buildFileRows()
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
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
                ).apply { topMargin = dp(8) }
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
        log("Browser opened. Approve access, copy the code, paste it below, then tap Finish connecting.")
    }

    // Step 2: exchange the pasted code (plus the stored verifier) for tokens.
    private fun finishDropboxLogin() {
        val code = binding.authCode.text.toString().trim()
        val webAuth = pkceWebAuth
        if (code.isEmpty()) { log("Paste the access code from the browser first."); return }
        if (webAuth == null) { log("Tap 'Open Dropbox login' first, then approve in the browser."); return }

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
            log(result)
            updateStatus()
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
            val result = withContext(Dispatchers.IO) {
                try {
                    val client = DbxClientV2(requestConfig, credential)
                    if (create) {
                        client.files().uploadBuilder(path)
                            .withMode(WriteMode.OVERWRITE)
                            .uploadAndFinish(ByteArrayInputStream(ByteArray(0)))
                        "Created: $path"
                    } else {
                        client.files().deleteV2(path)
                        "Deleted: $path"
                    }
                } catch (e: Exception) {
                    "Error on $path: ${e.message}"
                }
            }
            log(result)
        }
    }

    private fun log(message: String) {
        binding.log.text = message
    }
}
