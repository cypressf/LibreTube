package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.text.Spannable
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.databinding.ArticleHeaderRowBinding
import com.github.libretube.databinding.ArticleHeadingRowBinding
import com.github.libretube.databinding.ArticleParagraphRowBinding
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.util.ArticleFormatter

/**
 * Shows a video transcript formatted as an article: a header, chapter headings and paragraphs.
 *
 * The first item is always the header, so the block at index i is at adapter position i + 1.
 */
class ArticleAdapter(
    private val onSeek: (positionMs: Long) -> Unit,
    private val onLanguageClick: () -> Unit,
    private val onRetry: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    data class Header(
        val title: String,
        val author: String,
        val meta: String,
        val language: String?,
        val canChangeLanguage: Boolean,
        val showNoPunctuationNote: Boolean,
        val status: Status = Status.None
    )

    /**
     * Shown below the header while there's no article to show.
     */
    sealed interface Status {
        data object None : Status
        data object Loading : Status
        data class Message(val text: String, val canRetry: Boolean) : Status
    }

    var header: Header? = null
        private set
    var blocks: List<ArticleFormatter.Block> = emptyList()
        private set
    var currentLocation: ArticleFormatter.Location? = null
        private set

    @SuppressLint("NotifyDataSetChanged")
    fun submit(header: Header, blocks: List<ArticleFormatter.Block>) {
        this.header = header
        this.blocks = blocks
        currentLocation = null
        notifyDataSetChanged()
    }

    fun updateHeader(header: Header) {
        this.header = header
        notifyItemChanged(0)
    }

    fun setCurrentLocation(location: ArticleFormatter.Location?) {
        val old = currentLocation
        if (old == location) return

        currentLocation = location
        old?.let { notifyItemChanged(it.blockIndex + 1, PAYLOAD_HIGHLIGHT) }
        if (location != null && location.blockIndex != old?.blockIndex) {
            notifyItemChanged(location.blockIndex + 1, PAYLOAD_HIGHLIGHT)
        }
    }

    override fun getItemCount() = if (header == null) 0 else blocks.size + 1

    override fun getItemViewType(position: Int) = when {
        position == 0 -> TYPE_HEADER
        blocks[position - 1] is ArticleFormatter.Heading -> TYPE_HEADING
        else -> TYPE_PARAGRAPH
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ArticleHeaderRowBinding.inflate(inflater, parent, false))
            TYPE_HEADING -> HeadingViewHolder(ArticleHeadingRowBinding.inflate(inflater, parent, false))
            else -> ParagraphViewHolder(ArticleParagraphRowBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is HeaderViewHolder -> bindHeader(holder)
            is HeadingViewHolder -> bindHeading(holder, blocks[position - 1] as ArticleFormatter.Heading)
            is ParagraphViewHolder -> bindParagraph(holder, position - 1)
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
        payloads: MutableList<Any>
    ) {
        if (holder is ParagraphViewHolder && payloads.isNotEmpty() &&
            payloads.all { it == PAYLOAD_HIGHLIGHT }
        ) {
            // only the highlight changed, keep the rest of the view as it is
            updateHighlight(holder, position - 1)
        } else {
            onBindViewHolder(holder, position)
        }
    }

    private fun bindHeader(holder: HeaderViewHolder) {
        val header = header ?: return
        holder.binding.apply {
            articleTitle.text = header.title
            articleAuthor.text = header.author
            articleAuthor.isVisible = header.author.isNotBlank()
            articleMeta.text = header.meta
            articleLanguage.isVisible = header.language != null
            articleLanguage.text = header.language
            articleLanguage.isEnabled = header.canChangeLanguage
            articleLanguage.setOnClickListener { onLanguageClick() }
            articleNote.isVisible = header.showNoPunctuationNote

            val status = header.status
            articleProgress.isVisible = status is Status.Loading
            articleMessage.isVisible = status is Status.Message
            articleMessage.text = (status as? Status.Message)?.text
            articleRetry.isVisible = (status as? Status.Message)?.canRetry == true
            articleRetry.setOnClickListener { onRetry() }
        }
    }

    private fun bindHeading(holder: HeadingViewHolder, heading: ArticleFormatter.Heading) {
        holder.binding.headingText.text = heading.title
        ViewCompat.setAccessibilityHeading(holder.binding.root, true)
        holder.binding.root.setOnClickListener { onSeek(heading.startMs) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindParagraph(holder: ParagraphViewHolder, blockIndex: Int) {
        val textView = holder.binding.paragraphText

        updateHighlight(holder, blockIndex)
        holder.lastTouchX = -1f
        holder.lastTouchY = -1f

        // remember where the paragraph was touched, so that a tap can seek to the line there
        textView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                holder.lastTouchX = event.x
                holder.lastTouchY = event.y
            }
            false
        }
        textView.setOnClickListener {
            val paragraph = paragraphAt(holder) ?: return@setOnClickListener
            // clicks from e.g. TalkBack or a keyboard don't have a touch position
            val segment = if (holder.lastTouchX >= 0) {
                val offset = textView.getOffsetForPosition(holder.lastTouchX, holder.lastTouchY)
                paragraph.segments.getOrNull(paragraph.segmentAtOffset(offset))
            } else {
                null
            } ?: paragraph.segments.first()
            holder.lastTouchX = -1f
            holder.lastTouchY = -1f
            onSeek(segment.startMs)
        }
        textView.setOnLongClickListener {
            val paragraph = paragraphAt(holder) ?: return@setOnLongClickListener false
            ClipboardHelper.save(textView.context, text = paragraph.text, notify = true)
            true
        }
    }

    private fun paragraphAt(holder: RecyclerView.ViewHolder): ArticleFormatter.Paragraph? {
        val position = holder.bindingAdapterPosition
        if (position == RecyclerView.NO_POSITION || position == 0) return null
        return blocks.getOrNull(position - 1) as? ArticleFormatter.Paragraph
    }

    private fun updateHighlight(holder: ParagraphViewHolder, blockIndex: Int) {
        val paragraph = blocks[blockIndex] as ArticleFormatter.Paragraph
        val textView = holder.binding.paragraphText
        val location = currentLocation

        if (location == null || location.blockIndex != blockIndex) {
            textView.text = paragraph.text
            return
        }

        val segment = paragraph.segments.getOrNull(location.segmentIndex)
        if (segment == null) {
            textView.text = paragraph.text
            return
        }

        val primary = ThemeHelper.getThemeColor(
            textView.context,
            androidx.appcompat.R.attr.colorPrimary
        )
        val highlight = ColorUtils.setAlphaComponent(primary, HIGHLIGHT_ALPHA)
        textView.text = SpannableString(paragraph.text).apply {
            setSpan(
                BackgroundColorSpan(highlight),
                segment.textStart,
                segment.textEnd,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    /**
     * The vertical position of the current line, relative to the top of [holder]'s item view,
     * or null if [holder] doesn't show the current paragraph or it hasn't been laid out yet.
     */
    fun getCurrentLineTop(holder: RecyclerView.ViewHolder): Int? {
        val location = currentLocation ?: return null
        if (holder !is ParagraphViewHolder) return null
        if (holder.bindingAdapterPosition != location.blockIndex + 1) return null
        val paragraph = blocks.getOrNull(location.blockIndex) as? ArticleFormatter.Paragraph
            ?: return null
        val segment = paragraph.segments.getOrNull(location.segmentIndex) ?: return null

        val textView = holder.binding.paragraphText
        val layout = textView.layout ?: return null
        val line = layout.getLineForOffset(segment.textStart.coerceAtMost(textView.text.length))
        // the text view is the item view itself
        return textView.totalPaddingTop + layout.getLineTop(line)
    }

    class HeaderViewHolder(val binding: ArticleHeaderRowBinding) :
        RecyclerView.ViewHolder(binding.root)

    class HeadingViewHolder(val binding: ArticleHeadingRowBinding) :
        RecyclerView.ViewHolder(binding.root)

    class ParagraphViewHolder(val binding: ArticleParagraphRowBinding) :
        RecyclerView.ViewHolder(binding.root) {
        var lastTouchX = -1f
        var lastTouchY = -1f
    }

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_HEADING = 1
        private const val TYPE_PARAGRAPH = 2

        private const val PAYLOAD_HIGHLIGHT = "highlight"
        private const val HIGHLIGHT_ALPHA = 0x40
    }
}
