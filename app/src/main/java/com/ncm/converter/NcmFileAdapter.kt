package com.ncm.converter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 数据模型
data class NcmFileItem(
    val originalName: String,
    val uri: android.net.Uri,
    val songTitle: String,
    val artistName: String,
    val lastModified: Long,
    var isSelected: Boolean = false // 确保 isSelected 是可变的
)

class NcmFileAdapter(
    private val fileList: MutableList<NcmFileItem>
) : RecyclerView.Adapter<NcmFileAdapter.FileViewHolder>() {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    // 选择状态变化监听器
    private var onSelectionChangedListener: ((Int, Int) -> Unit)? = null

    fun setOnSelectionChangedListener(listener: (selectedCount: Int, totalCount: Int) -> Unit) {
        this.onSelectionChangedListener = listener
    }

    private fun notifySelectionChanged() {
        onSelectionChangedListener?.invoke(getSelectedFiles().size, itemCount)
    }

    class FileViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val checkBox: CheckBox = itemView.findViewById(R.id.checkbox_file_item)
        val songTitle: TextView = itemView.findViewById(R.id.text_song_title)
        val artistName: TextView = itemView.findViewById(R.id.text_artist_name)
        val fileDate: TextView = itemView.findViewById(R.id.text_file_date)
        val fileTime: TextView = itemView.findViewById(R.id.text_file_time)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FileViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.list_item_file, parent, false)
        return FileViewHolder(view)
    }

    override fun onBindViewHolder(holder: FileViewHolder, position: Int) {
        val item = fileList[position]
        holder.songTitle.text = item.songTitle
        holder.artistName.text = item.artistName
        val date = Date(item.lastModified)
        holder.fileDate.text = dateFormat.format(date)
        holder.fileTime.text = timeFormat.format(date)
        holder.checkBox.isChecked = item.isSelected

        holder.itemView.setOnClickListener { holder.checkBox.toggle() }

        holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
            if (item.isSelected != isChecked) {
                item.isSelected = isChecked
                notifySelectionChanged() // 状态改变时通知监听器
            }
        }
    }

    override fun getItemCount(): Int = fileList.size

    fun setData(newFileList: List<NcmFileItem>) {
        fileList.clear()
        fileList.addAll(newFileList)
        notifyDataSetChanged()
        notifySelectionChanged() // 设置新数据后，也通知一次
    }

    fun selectAll(select: Boolean) {
        fileList.forEach { it.isSelected = select }
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    fun invertSelection() {
        fileList.forEach { it.isSelected = !it.isSelected }
        notifyDataSetChanged()
        notifySelectionChanged()
    }

    fun getSelectedFiles(): List<NcmFileItem> {
        return fileList.filter { it.isSelected }
    }
}