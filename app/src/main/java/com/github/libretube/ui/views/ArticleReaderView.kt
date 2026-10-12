package com.github.libretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.recyclerview.widget.LinearLayoutManager
import com.github.libretube.databinding.ViewArticleReaderBinding
import com.github.libretube.ui.adapters.ArticleAdapter
import com.github.libretube.ui.models.ReaderStyle
import com.github.libretube.util.ArticleFormatter

/**
 * Shows a transcript formatted as an article, for reading instead of watching or listening.
 */
class ArticleReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    val binding = ViewArticleReaderBinding.inflate(LayoutInflater.from(context), this, true)

    var onLanguageClick: () -> Unit = {}
    var onRetry: () -> Unit = {}
    var onMinimize: () -> Unit = {}
    var onShowPlayer: () -> Unit = {}

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = ArticleAdapter(
        onLanguageClick = { onLanguageClick() },
        onRetry = { onRetry() },
        onMinimize = { onMinimize() },
        onShowPlayer = { onShowPlayer() }
    )

    private var article: ArticleFormatter.Article? = null

    init {
        binding.articleRecycler.layoutManager = layoutManager
        binding.articleRecycler.adapter = adapter
    }

    /**
     * Apply the font and text size from the settings.
     */
    fun setStyle(style: ReaderStyle) = adapter.setStyle(style)

    fun showLoading(header: ArticleAdapter.Header) {
        showStatus(header.copy(status = ArticleAdapter.Status.Loading))
    }

    fun showMessage(header: ArticleAdapter.Header, message: String, canRetry: Boolean) {
        showStatus(header.copy(status = ArticleAdapter.Status.Message(message, canRetry)))
    }

    private fun showStatus(header: ArticleAdapter.Header) {
        article = null
        if (adapter.blocks.isEmpty() && adapter.header != null) {
            adapter.updateHeader(header)
        } else {
            adapter.submit(header, emptyList())
        }
    }

    fun showArticle(header: ArticleAdapter.Header, article: ArticleFormatter.Article) {
        if (this.article === article) {
            adapter.updateHeader(header)
            return
        }

        this.article = article
        adapter.submit(header, article.blocks)
        // a new article starts at the top
        layoutManager.scrollToPositionWithOffset(0, 0)
    }
}
