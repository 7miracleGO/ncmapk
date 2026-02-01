package com.ncm.converter

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import android.content.Intent
import java.io.FileInputStream

class MainActivity : AppCompatActivity(), FileListDialogFragment.OnConvertListener {

    private val TAG = "NCM_DEBUG"
    private val PREFS_NAME = "ncm_settings"
    private val KEY_SOURCE_URI = "permanent_source_uri"
    private val KEY_OUTPUT_URI = "permanent_output_uri"

    private lateinit var btnSelectSource: Button
    private lateinit var btnSelectOutput: Button
    private lateinit var btnStartConversion: Button
    private lateinit var textSourcePath: TextView
    private lateinit var textOutputPath: TextView
    private lateinit var textConversionStatus: TextView

    private var sourceUri: Uri? = null
    private var outputUri: Uri? = null

    private val statusClearHandler = Handler(Looper.getMainLooper())

    private val sourceDirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            takePersistableUriPermission(it)
            sourceUri = it
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_SOURCE_URI, it.toString()).apply()
            updatePathTextView(textSourcePath, it)
            Toast.makeText(this, "源目录已设定", Toast.LENGTH_SHORT).show()
        }
    }

    private val outputDirPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            takePersistableUriPermission(it)
            outputUri = it
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_OUTPUT_URI, it.toString()).apply()
            updatePathTextView(textOutputPath, it)
            Toast.makeText(this, "输出目录已设定", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnSelectSource = findViewById(R.id.button_select_source)
        btnSelectOutput = findViewById(R.id.button_select_output)
        btnStartConversion = findViewById(R.id.button_start_conversion)
        textSourcePath = findViewById(R.id.text_source_path)
        textOutputPath = findViewById(R.id.text_output_path)
        textConversionStatus = findViewById(R.id.text_conversion_status)

        loadSavedUris()

        btnSelectSource.setOnClickListener { sourceDirPickerLauncher.launch(null) }
        btnSelectOutput.setOnClickListener { outputDirPickerLauncher.launch(null) }

        btnStartConversion.setOnClickListener {
            val currentSourceUri = sourceUri
            if (currentSourceUri == null) {
                Toast.makeText(this, "请先选择源目录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (outputUri == null) {
                Toast.makeText(this, "请先选择输出目录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val dialog = FileListDialogFragment.newInstance(currentSourceUri)
            dialog.show(supportFragmentManager, "FileListDialog")
        }
    }

    override fun onConvert(selectedFiles: List<NcmFileItem>, deleteSource: Boolean) {
        startBatchConversion(selectedFiles, deleteSource)
    }

    private fun updateConversionStatus(message: CharSequence, duration: Long, color: Int? = null) {
        runOnUiThread {
            statusClearHandler.removeCallbacksAndMessages(null)
            textConversionStatus.text = message
            if (color != null) {
                textConversionStatus.setTextColor(color)
            }
            textConversionStatus.visibility = View.VISIBLE
            if (duration > 0) {
                statusClearHandler.postDelayed({ textConversionStatus.visibility = View.GONE }, duration)
            }
        }
    }

    private fun startBatchConversion(filesToConvert: List<NcmFileItem>, deleteSource: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            var successCount = 0
            var failureCount = 0
            val failedFiles = mutableListOf<String>()

            for ((index, fileItem) in filesToConvert.withIndex()) {
                val statusText = "正在转换 (${index + 1}/${filesToConvert.size}): ${fileItem.songTitle}"
                updateConversionStatus(statusText, -1, Color.GRAY)

                val result = processTask(fileItem, outputUri!!)
                if (result) {
                    successCount++
                    if (deleteSource) {
                        try {
                            DocumentsContract.deleteDocument(contentResolver, fileItem.uri)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deleting source", e)
                        }
                    }
                } else {
                    failureCount++
                    failedFiles.add(fileItem.originalName)
                }
            }

            withContext(Dispatchers.Main) {
                if (failureCount > 0) {
                    val builder = SpannableStringBuilder()
                    val successText = SpannableString("成功: $successCount\n")
                    successText.setSpan(ForegroundColorSpan(Color.GRAY), 0, successText.length, 0)
                    builder.append(successText)
                    val failureText = SpannableString("失败: $failureCount\n\n")
                    failureText.setSpan(ForegroundColorSpan(Color.RED), 0, failureText.length, 0)
                    builder.append(failureText)
                    failedFiles.forEach { fileName ->
                        val start = builder.length
                        builder.append("$fileName\n")
                        val end = builder.length
                        builder.setSpan(ForegroundColorSpan(Color.RED), start, end, 0)
                        builder.setSpan(RelativeSizeSpan(1.3f), start, end, 0)
                    }
                    updateConversionStatus(builder, 20000, null)
                } else {
                    val successMessage = "转换成功 (共 $successCount 个)"
                    updateConversionStatus(successMessage, 8000, Color.GRAY)
                }
                clearCache()
            }
        }
    }

    private fun processTask(inputItem: NcmFileItem, outputDirUri: Uri): Boolean {
        try {
            val cacheFile = File(this.cacheDir, inputItem.originalName)
            this.contentResolver.openInputStream(inputItem.uri)?.use { input ->
                FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
            }
            val metadataJson = getNcmMetadata(cacheFile.absolutePath)
            if (metadataJson.length <= 2) {
                cacheFile.delete()
                return false
            }
            val json = JSONObject(metadataJson)
            val title = json.optString("musicName", inputItem.songTitle)
            val album = json.optString("album", "Unknown Album")
            val artistsArray = json.optJSONArray("artist")
            val artist = artistsArray?.let {
                (0 until it.length()).joinToString(" / ") { i -> it.getJSONArray(i).getString(0) }
            } ?: inputItem.artistName

            val coverUrl = json.optString("albumPic", "")
            val coverData: ByteArray? = if (coverUrl.isNotEmpty()) {
                try { URL(coverUrl).readBytes() } catch (e: Exception) { null }
            } else null

            val result = unlockNcm(cacheFile.absolutePath, this.cacheDir.absolutePath + "/", title, artist, album, coverData)
            if (result == 1) {
                val outputNameWithoutExt = inputItem.originalName.removeSuffix(".ncm")
                val generatedFile = this.cacheDir.listFiles()?.find {
                    it.nameWithoutExtension == outputNameWithoutExt && (it.extension.lowercase() == "mp3" || it.extension.lowercase() == "flac")
                }
                if (generatedFile != null && generatedFile.exists()) {
                    val success = exportToFixedDir(generatedFile, generatedFile.name, outputDirUri)
                    generatedFile.delete()
                    cacheFile.delete()
                    return success
                }
            }
            return false
        } catch (e: Exception) {
            return false
        }
    }

    private fun loadSavedUris() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_SOURCE_URI, null)?.let {
            sourceUri = Uri.parse(it)
            updatePathTextView(textSourcePath, sourceUri!!)
        }
        prefs.getString(KEY_OUTPUT_URI, null)?.let {
            outputUri = Uri.parse(it)
            updatePathTextView(textOutputPath, outputUri!!)
        }
    }

    private fun takePersistableUriPermission(uri: Uri) {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        this.contentResolver.takePersistableUriPermission(uri, takeFlags)
    }

    private fun updatePathTextView(textView: TextView, uri: Uri) {
        val cryText = "侧边栏虚拟入口进入目录无法解析路径😭 我有强迫症我心里不得劲了 请从侧边栏选择 [手机型号/内部存储] 进入[download/下载]文件夹吧 求你了(颜文字特殊符号显示不对)呜呜呜呜"
        try {
            val docId = DocumentsContract.getTreeDocumentId(uri)

            val isVirtual = docId.matches(Regex("^\\d+$")) ||
                    docId.startsWith("msf:") ||
                    docId.startsWith("raw:") ||
                    !docId.contains(":")

            if (isVirtual) {
                textView.text = cryText
            } else {
                val decodedId = Uri.decode(docId)
                val friendlyPath = when {
                    decodedId.startsWith("primary:") -> decodedId.replaceFirst("primary:", "内部存储/")
                    else -> decodedId.substringAfter(":")
                }

                if (friendlyPath.matches(Regex("^\\d+$")) || friendlyPath.isEmpty()) {
                    textView.text = cryText
                } else {
                    textView.text = friendlyPath
                }
            }
        } catch (e: Exception) {
            textView.text = cryText
        }
    }

    private fun exportToFixedDir(tempFile: File, targetName: String, rootUri: Uri): Boolean {
        return try {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, DocumentsContract.getTreeDocumentId(rootUri))
            val mime = if (targetName.endsWith(".flac", true)) "audio/flac" else "audio/mpeg"
            val outUri = DocumentsContract.createDocument(this.contentResolver, parentUri, mime, targetName)
            outUri?.let {
                this.contentResolver.openOutputStream(it)?.use { output ->
                    FileInputStream(tempFile).use { input -> input.copyTo(output) }
                }
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun clearCache() {
        this.cacheDir.listFiles()?.forEach { it.delete() }
    }

    external fun getNcmMetadata(inputPath: String): String
    external fun unlockNcm(inputPath: String, outputDir: String, title: String, artist: String, album: String, coverArt: ByteArray?): Int

    companion object {
        init {
            System.loadLibrary("ncmtest")
        }
    }
}