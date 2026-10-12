package com.reelsaver

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var status: TextView
    private lateinit var bar: ProgressBar
    private lateinit var btn: Button
    private lateinit var updBtn: Button
    private var ready = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (20 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        root.addView(TextView(this).apply { text = "Reel Saver"; textSize = 28f })
        input = EditText(this).apply { hint = "Paste Instagram reel link" ; setSingleLine(true) }
        root.addView(input)
        btn = Button(this).apply { text = "Download"; isEnabled = false; setOnClickListener { download() } }
        root.addView(btn)
        bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        root.addView(bar)
        status = TextView(this).apply { text = "Starting engine…"; gravity = Gravity.START; setPadding(0, pad / 2, 0, pad / 2) }
        root.addView(status)
        updBtn = Button(this).apply { text = "Update engine (if downloads fail)"; isEnabled = false; setOnClickListener { updateEngine() } }
        root.addView(updBtn)
        setContentView(root)

        handleIntent(intent)
        Thread {
            try {
                YoutubeDL.getInstance().init(application)
                FFmpeg.getInstance().init(application)
                ready = true
                ui { status.text = "Ready"; btn.isEnabled = true; updBtn.isEnabled = true }
            } catch (e: Exception) {
                ui { status.text = "Engine failed: ${e.message}" }
            }
        }.start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        if (i?.action == Intent.ACTION_SEND) {
            val t = i.getStringExtra(Intent.EXTRA_TEXT) ?: return
            Regex("https?://\\S+").find(t)?.let { input.setText(it.value) }
        }
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun updateEngine() {
        btn.isEnabled = false; updBtn.isEnabled = false
        status.text = "Updating engine…"
        Thread {
            val msg = try {
                val r = YoutubeDL.getInstance().updateYoutubeDL(application, YoutubeDL.UpdateChannel.NIGHTLY)
                "Update: ${r?.name ?: "done"}"
            } catch (e: Exception) { "Update failed: ${e.message}" }
            ui { status.text = msg; btn.isEnabled = true; updBtn.isEnabled = true }
        }.start()
    }

    private fun download() {
        val url = input.text.toString().trim()
        if (!ready || url.isEmpty()) return
        btn.isEnabled = false
        bar.progress = 0
        status.text = "Downloading…"
        Thread {
            val tmp = File(getExternalFilesDir(null), "tmp").apply { deleteRecursively(); mkdirs() }
            try {
                val req = YoutubeDLRequest(url).apply {
                    addOption("-o", "${tmp.absolutePath}/reel_%(id)s.%(ext)s")
                    addOption("-f", "bv*+ba/b")
                    addOption("--merge-output-format", "mp4")
                    addOption("--no-playlist")
                }
                YoutubeDL.getInstance().execute(req, "reel") { p, _, _ ->
                    ui { bar.progress = p.toInt().coerceIn(0, 100) }
                }
                val f = tmp.listFiles()?.firstOrNull { it.extension == "mp4" } ?: tmp.listFiles()?.firstOrNull()
                    ?: throw Exception("No file produced")
                saveToGallery(f)
                ui { bar.progress = 100; status.text = "Saved to Movies/ReelSaver: ${f.name}" }
            } catch (e: Exception) {
                ui { status.text = "Failed: ${e.message?.take(300)}" }
            } finally {
                tmp.deleteRecursively()
                ui { btn.isEnabled = true }
            }
        }.start()
    }

    private fun saveToGallery(f: File) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ReelSaver")
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw Exception("Could not create gallery entry")
        contentResolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
    }
}
