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
            // If a color is provided, set it. Otherwise, let the SpannableString control the color.
            color?.let { textConversionStatus.setTextColor(it) }
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
                val statusText = "转换中 (${index + 1}/${filesToConvert.size}): ${fileItem.songTitle}"
                updateConversionStatus(statusText, -1, Color.GRAY)

                val result = processTask(fileItem, outputUri!!)
                if (result) {
                    successCount++
                    if (deleteSource) {
                        try {
                            DocumentsContract.deleteDocument(contentResolver, fileItem.uri)
                        } catch (e: Exception) {
                            Log.e(TAG, "删除源文件失败: ${fileItem.originalName}", e)
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
                    successText.setSpan(ForegroundColorSpan(Color.GREEN), 0, successText.length, 0)
                    builder.append(successText)

                    val failureText = SpannableString("失败: $failureCount\n")
                    failureText.setSpan(ForegroundColorSpan(Color.RED), 0, failureText.length, 0)
                    builder.append(failureText)
                    
                    // Reset text color for the list of failed files
                    textConversionStatus.setTextColor(Color.BLACK)
                    builder.append(failedFiles.joinToString("\n"))

                    updateConversionStatus(builder, 20000)
                } else {
                    val successMessage = "转换全部成功! (共 $successCount 个)"
                    updateConversionStatus(successMessage, 8000, Color.GRAY)
                }
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
            if (metadataJson.length <= 2) { // 检查是否为空JSON "{}"
                Log.e(TAG, "元数据为空，可能是个假ncm文件: ${inputItem.originalName}")
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
                    return exportToFixedDir(generatedFile, generatedFile.name, outputDirUri)
                } else {
                    Log.e(TAG, "转换成功但找不到输出文件: ${inputItem.originalName}")
                    return false
                }
            } else {
                Log.e(TAG, "unlockNcm 核心代码返回失败代码: $result, 文件: ${inputItem.originalName}")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理文件时发生致命错误: ${inputItem.originalName}", e)
            return false
        }
    }
    
    private fun loadSavedUris() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_SOURCE_URI, null)?.let {
            sourceUri = Uri.parse(it)
            updatePathTextView(textSourcePath, Uri.parse(it))
        }
        prefs.getString(KEY_OUTPUT_URI, null)?.let {
            outputUri = Uri.parse(it)
            updatePathTextView(textOutputPath, Uri.parse(it))
        }
    }

    private fun takePersistableUriPermission(uri: Uri) {
        val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        this.contentResolver.takePersistableUriPermission(uri, takeFlags)
    }

    private fun updatePathTextView(textView: TextView, uri: Uri) {
        try {
            var friendlyPath = ""
            val authority = uri.authority

            if (authority == "com.android.providers.downloads.documents") {
                val docId = DocumentsContract.getTreeDocumentId(uri)
                val docUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                contentResolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val displayName = cursor.getString(0)
                        friendlyPath = if (docId == "downloads") "下载" else "下载/$displayName"
                    }
                }
            } else {
                val path = uri.path
                if (path != null) {
                    val decodedPath = Uri.decode(path)
                    val tempPath = decodedPath.removePrefix("/tree/")

                    friendlyPath = when {
                        tempPath.startsWith("primary:") ->
                            tempPath.replaceFirst("primary:", "内部存储/")
                        tempPath.matches(Regex("^[0-9A-F]{4}-[0-9A-F]{4}:.*$")) ->
                            "SD卡/" + tempPath.substringAfter(":", "")
                        tempPath.contains(":") -> {
                            val parts = tempPath.split(":", limit = 2)
                            "存储设备 (${parts.getOrElse(0) { "" }})/${parts.getOrElse(1) { "" }}"
                        }
                        else -> tempPath
                    }
                }
            }

            textView.text = if (friendlyPath.isNotEmpty()) friendlyPath else "路径无效"

        } catch (e: Exception) {
            Log.e(TAG, "查询文件夹名称失败", e)
            textView.text = "无法显示路径"
        }
    }

    private fun exportToFixedDir(tempFile: File, targetName: String, rootUri: Uri): Boolean {
        return try {
            val parentUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, DocumentsContract.getTreeDocumentId(rootUri))
            val outUri = DocumentsContract.createDocument(this.contentResolver, parentUri, if (targetName.endsWith(".flac", true)) "audio/flac" else "audio/mpeg", targetName)
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