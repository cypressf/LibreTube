package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.databinding.TranscriptRowBinding
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.obj.TranscriptLine
import com.github.libretube.ui.viewholders.TranscriptViewHolder

class TranscriptAdapter(
    private val seekTo: (positionMs: Long) -> Unit
) : RecyclerView.Adapter<TranscriptViewHolder>() {
    var lines: List<TranscriptLine> = emptyList()
        private set
    var selectedPosition = -1
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submitLines(newLines: List<TranscriptLine>) {
        lines = newLines
        selectedPosition = -1
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TranscriptViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val binding = TranscriptRowBinding.inflate(layoutInflater, parent, false)
        return TranscriptViewHolder(binding)
    }

    override fun onBindViewHolder(holder: TranscriptViewHolder, position: Int) {
        val line = lines[position]
        holder.binding.apply {
            timeStamp.text = DateUtils.formatElapsedTime(line.startMs / 1000)
            transcriptText.text = line.text

            val color = if (position == selectedPosition) {
                ThemeHelper.getThemeColor(root.context, android.R.attr.colorControlHighlight)
            } else {
                Color.TRANSPARENT
            }
            transcriptLL.setBackgroundColor(color)

            root.setOnClickListener {
                val clickedPosition = holder.bindingAdapterPosition
                if (clickedPosition == RecyclerView.NO_POSITION) return@setOnClickListener

                updateSelectedPosition(clickedPosition)
                seekTo(lines[clickedPosition].startMs)
            }
        }
    }

    fun updateSelectedPosition(newPosition: Int) {
        if (selectedPosition == newPosition) return

        val oldPosition = selectedPosition
        selectedPosition = newPosition
        if (oldPosition in lines.indices) notifyItemChanged(oldPosition)
        if (newPosition in lines.indices) notifyItemChanged(newPosition)
    }

    override fun getItemCount() = lines.size
}
