package com.ncm.converter

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.Collator

class FileListDialogFragment : DialogFragment() {

    private val TAG = "NCM_DIALOG_DEBUG"

    interface OnConvertListener {
        fun onConvert(selectedFiles: List<NcmFileItem>, deleteSource: Boolean)
    }

    private var listener: OnConvertListener? = null

    private lateinit var adapter: NcmFileAdapter
    private val fileListCache = mutableListOf<NcmFileItem>()
    private var sourceUri: Uri? = null

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var radioGroupSort: RadioGroup
    private lateinit var btnSelectAll: Button
    private lateinit var btnInvertSelection: Button
    private lateinit var checkboxDeleteSource: CheckBox

    external fun getNcmMetadata(inputPath: String): String

    companion object {
        private const val SORT_BY_NAME = 0
        private const val SORT_BY_DATE = 1
        private var lastSortMode: Int = SORT_BY_NAME
        private var lastDeleteSource: Boolean = false

        init {
            System.loadLibrary("ncmtest")
        }

        private const val ARG_SOURCE_URI = "source_uri"
        fun newInstance(sourceUri: Uri): FileListDialogFragment {
            val fragment = FileListDialogFragment()
            val args = Bundle()
            args.putString(ARG_SOURCE_URI, sourceUri.toString())
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.getString(ARG_SOURCE_URI)?.let {
            sourceUri = Uri.parse(it)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.dialog_file_list, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        recyclerView = view.findViewById(R.id.recycler_view_files)
        emptyView = view.findViewById(R.id.text_empty_view)
        radioGroupSort = view.findViewById(R.id.radiogroup_sort)
        btnSelectAll = view.findViewById(R.id.button_select_all)
        btnInvertSelection = view.findViewById(R.id.button_invert_selection)
        checkboxDeleteSource = view.findViewById(R.id.checkbox_delete_source)
        val btnCancel = view.findViewById<Button>(R.id.button_dialog_cancel)
        val btnStart = view.findViewById<Button>(R.id.button_dialog_start)

        adapter = NcmFileAdapter(mutableListOf())
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = adapter

        adapter.setOnSelectionChangedListener { selectedCount, totalCount ->
            if (totalCount > 0 && selectedCount == totalCount) {
                btnSelectAll.text = "取消全选"
            } else {
                btnSelectAll.text = "全选"
            }
        }

        loadAndApplyUserSettings()
        scanAndPreProcessFiles()

        radioGroupSort.setOnCheckedChangeListener { _, _ -> processAndDisplayFiles() }
        btnInvertSelection.setOnClickListener { adapter.invertSelection() }

        btnSelectAll.setOnClickListener {
            if (adapter.getSelectedFiles().size == adapter.itemCount) {
                adapter.selectAll(false) // 已全选，则取消
            } else {
                adapter.selectAll(true) // 未全选，则全选
            }
        }

        btnCancel.setOnClickListener { dismiss() }
        btnStart.setOnClickListener {
            val selected = adapter.getSelectedFiles()
            if (selected.isEmpty()) return@setOnClickListener
            saveUserSettings()
            listener?.onConvert(selected, checkboxDeleteSource.isChecked)
            dismiss()
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.let {
            val width = (resources.displayMetrics.widthPixels * 0.90).toInt()
            val height = (resources.displayMetrics.heightPixels * 0.85).toInt()
            it.setLayout(width, height)
        }
    }

    private fun scanAndPreProcessFiles() {
        val uriToScan = sourceUri ?: return
        emptyView.text = "正在扫描和解析文件..."
        emptyView.visibility = View.VISIBLE
        recyclerView.visibility = View.GONE

        CoroutineScope(Dispatchers.IO).launch {
            val documents = mutableListOf<NcmFileItem>()
            try {
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uriToScan, DocumentsContract.getTreeDocumentId(uriToScan))
                val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val cursor = requireContext().contentResolver.query(childrenUri, projection, null, null, null)

                cursor?.use {
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(0)
                        if (name.endsWith(".ncm")) {
                            val docId = cursor.getString(1)
                            val lastModified = cursor.getLong(2)
                            val fileUri = DocumentsContract.buildDocumentUriUsingTree(uriToScan, docId)

                            try {
                                val cacheFile = File(requireContext().cacheDir, name)
                                requireContext().contentResolver.openInputStream(fileUri)?.use { input ->
                                    FileOutputStream(cacheFile).use { output -> input.copyTo(output) }
                                }
                                val metadataJson = getNcmMetadata(cacheFile.absolutePath)
                                val json = JSONObject(metadataJson)
                                val songTitle = json.optString("musicName", name)
                                val artistsArray = json.optJSONArray("artist")
                                val artistName = artistsArray?.let {
                                    (0 until it.length()).joinToString(" / ") { i -> it.getJSONArray(i).getString(0) }
                                } ?: "未知艺术家"
                                documents.add(NcmFileItem(name, fileUri, songTitle, artistName, lastModified))
                                cacheFile.delete()
                            } catch (e: Exception) {
                                documents.add(NcmFileItem(name, fileUri, name, "元数据读取失败", lastModified))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "扫描文件时发生严重错误!", e)
            }

            withContext(Dispatchers.Main) {
                fileListCache.clear()
                fileListCache.addAll(documents)
                processAndDisplayFiles()
            }
        }
    }

    private fun processAndDisplayFiles() {
        if (fileListCache.isEmpty()) {
            emptyView.text = "无ncm文件可转换"
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            val sortedList = fileListCache.toMutableList()
            val checkedId = radioGroupSort.checkedRadioButtonId
            if (checkedId == R.id.radio_sort_by_name) {
                val collator = Collator.getInstance()
                sortedList.sortWith(compareBy(collator) { it.songTitle })
            } else {
                sortedList.sortByDescending { it.lastModified }
            }
            adapter.setData(sortedList)
        }
    }

    private fun loadAndApplyUserSettings() {
        val idToCheck = if (lastSortMode == SORT_BY_NAME) R.id.radio_sort_by_name else R.id.radio_sort_by_date
        radioGroupSort.check(idToCheck)
        checkboxDeleteSource.isChecked = lastDeleteSource
    }

    private fun saveUserSettings() {
        val sortMode = if (radioGroupSort.checkedRadioButtonId == R.id.radio_sort_by_name) SORT_BY_NAME else SORT_BY_DATE
        lastSortMode = sortMode
        lastDeleteSource = checkboxDeleteSource.isChecked
    }

    override fun onAttach(context: Context) {
        super.onAttach(context)
        if (context is OnConvertListener) {
            listener = context
        } else {
            throw RuntimeException("$context must implement OnConvertListener")
        }
    }

    override fun onDetach() {
        super.onDetach()
        listener = null
    }
}