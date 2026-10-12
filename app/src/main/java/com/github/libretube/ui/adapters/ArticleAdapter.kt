package com.github.libretube.ui.adapters

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.databinding.ArticleHeaderRowBinding
import com.github.libretube.databinding.ArticleHeadingRowBinding
import com.github.libretube.databinding.ArticleParagraphRowBinding
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.ui.models.ReaderStyle
import com.github.libretube.util.ArticleFormatter

/**
 * Shows a video transcript formatted as an article: a header, chapter headings and paragraphs.
 *
 * The first item is always the header, so the block at index i is at adapter position i + 1.
 */
class ArticleAdapter(
    private val onLanguageClick: () -> Unit,
    private val onRetry: () -> Unit,
    private val onMinimize: () -> Unit,
    private val onShowPlayer: () -> Unit
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
    private var style: ReaderStyle? = null

    @SuppressLint("NotifyDataSetChanged")
    fun setStyle(style: ReaderStyle) {
        if (this.style == style) return
        this.style = style
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun submit(header: Header, blocks: List<ArticleFormatter.Block>) {
        this.header = header
        this.blocks = blocks
        notifyDataSetChanged()
    }

    fun updateHeader(header: Header) {
        this.header = header
        notifyItemChanged(0)
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
            is ParagraphViewHolder -> bindParagraph(holder, blocks[position - 1] as ArticleFormatter.Paragraph)
        }
    }

    private fun bindHeader(holder: HeaderViewHolder) {
        val header = header ?: return
        holder.binding.apply {
            articleMinimize.setOnClickListener { onMinimize() }
            articleShowPlayer.setOnClickListener { onShowPlayer() }

            style?.let { style ->
                articleTitle.applyStyle(style.typeface, style.titleSizeSp, bold = true)
                articleAuthor.applyStyle(style.typeface, style.metaSizeSp * 1.12f)
                articleMeta.applyStyle(style.typeface, style.metaSizeSp)
                articleNote.applyStyle(style.typeface, style.metaSizeSp * 0.9f)
                articleMessage.applyStyle(style.typeface, style.metaSizeSp * 1.12f)
            }

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
        val textView = holder.binding.headingText
        style?.let { textView.applyStyle(it.typeface, it.headingSizeSp, bold = true) }
        textView.text = heading.title
        ViewCompat.setAccessibilityHeading(textView, true)
    }

    private fun bindParagraph(holder: ParagraphViewHolder, paragraph: ArticleFormatter.Paragraph) {
        val textView = holder.binding.paragraphText
        style?.let { textView.applyStyle(it.typeface, it.textSizeSp) }
        textView.text = paragraph.text

        textView.setOnLongClickListener {
            ClipboardHelper.save(textView.context, text = textView.text.toString(), notify = true)
            true
        }
    }

    private fun TextView.applyStyle(typeface: Typeface, sizeSp: Float, bold: Boolean = false) {
        setTypeface(typeface, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
    }

    class HeaderViewHolder(val binding: ArticleHeaderRowBinding) :
        RecyclerView.ViewHolder(binding.root)

    class HeadingViewHolder(val binding: ArticleHeadingRowBinding) :
        RecyclerView.ViewHolder(binding.root)

    class ParagraphViewHolder(val binding: ArticleParagraphRowBinding) :
        RecyclerView.ViewHolder(binding.root)

    companion object {
        private const val TYPE_HEADER = 0
        private const val TYPE_HEADING = 1
        private const val TYPE_PARAGRAPH = 2
    }
}
