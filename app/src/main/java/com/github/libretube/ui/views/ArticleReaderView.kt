package com.github.libretube.ui.views

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
import androidx.core.view.doOnPreDraw
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.github.libretube.R
import com.github.libretube.databinding.ViewArticleReaderBinding
import com.github.libretube.ui.adapters.ArticleAdapter
import com.github.libretube.util.ArticleFormatter

/**
 * Shows a transcript formatted as an article and follows along with playback: the line that's
 * currently spoken is highlighted and kept in view.
 *
 * Scrolling manually stops following, so that the user can read ahead or go back. A button
 * then brings the reader back to the current position.
 */
class ArticleReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    val binding = ViewArticleReaderBinding.inflate(LayoutInflater.from(context), this, true)

    var onSeek: (positionMs: Long) -> Unit = {}
    var onLanguageClick: () -> Unit = {}
    var onRetry: () -> Unit = {}

    private val layoutManager = LinearLayoutManager(context)
    private val adapter = ArticleAdapter(
        onSeek = { positionMs ->
            // jumping to a line means the user wants to follow along from there
            setFollowing(true)
            onSeek(positionMs)
        },
        onLanguageClick = { onLanguageClick() },
        onRetry = { onRetry() }
    )

    private var article: ArticleFormatter.Article? = null
    private var isFollowing = true
    private var isUserDragging = false
    private var isWaitingForLayout = false
    private var isJumpIconUp: Boolean? = null

    init {
        binding.articleRecycler.layoutManager = layoutManager
        binding.articleRecycler.adapter = adapter
        // don't cross-fade paragraphs whenever the highlighted line changes
        (binding.articleRecycler.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        binding.articleRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                when (newState) {
                    RecyclerView.SCROLL_STATE_DRAGGING -> isUserDragging = true
                    RecyclerView.SCROLL_STATE_IDLE -> isUserDragging = false
                }
                updateJumpButton()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // only an actual drag stops following, not a tap that interrupts auto-scrolling
                if (isUserDragging && dy != 0) setFollowing(false)
            }
        })

        binding.jumpToCurrent.setOnClickListener {
            setFollowing(true)
            scrollToCurrent(smooth = false)
        }
    }

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
        updateJumpButton()
    }

    fun showArticle(header: ArticleAdapter.Header, article: ArticleFormatter.Article, positionMs: Long) {
        if (this.article === article) {
            adapter.updateHeader(header)
            return
        }

        this.article = article
        adapter.submit(header, article.blocks)
        adapter.setCurrentLocation(article.locate(positionMs))
        setFollowing(true)

        val location = adapter.currentLocation
        if (location == null) {
            // the video hasn't started yet, so start at the top
            layoutManager.scrollToPositionWithOffset(0, 0)
        } else {
            // the old views are invalid now: jump to the paragraph and fine-tune the position
            // once the new ones are laid out
            layoutManager.scrollToPositionWithOffset(location.blockIndex + 1, anchorOffset())
            binding.articleRecycler.doOnPreDraw { if (isFollowing) scrollToCurrent(smooth = false) }
        }
    }

    fun updatePosition(positionMs: Long) {
        val article = article ?: return
        adapter.setCurrentLocation(article.locate(positionMs))
        if (isFollowing) scrollToCurrent(smooth = true)
        updateJumpButton()
    }

    private fun setFollowing(following: Boolean) {
        isFollowing = following
        updateJumpButton()
    }

    private fun updateJumpButton() {
        val location = adapter.currentLocation
        binding.jumpToCurrent.isVisible = !isFollowing && location != null
        if (location == null) return

        // point towards the current line
        val isAbove = location.blockIndex + 1 < layoutManager.findFirstVisibleItemPosition()
        if (isAbove != isJumpIconUp) {
            isJumpIconUp = isAbove
            binding.jumpToCurrent.setIconResource(
                if (isAbove) R.drawable.ic_arrow_upward else R.drawable.ic_arrow_downward
            )
        }
    }

    private fun anchorOffset() = (binding.articleRecycler.height * ANCHOR_FRACTION).toInt()

    /**
     * Keep the current line at roughly a third of the height, so the upcoming text stays visible,
     * but only scroll once it leaves a comfortable band, so that the text doesn't move constantly.
     */
    private fun scrollToCurrent(smooth: Boolean) {
        val location = adapter.currentLocation ?: return
        val recycler = binding.articleRecycler
        val height = recycler.height
        if (height == 0) {
            // not laid out yet (e.g. reading mode was just turned on), try again once it is
            if (!isWaitingForLayout) {
                isWaitingForLayout = true
                recycler.doOnLayout {
                    isWaitingForLayout = false
                    if (isFollowing && it.height > 0) scrollToCurrent(smooth = false)
                }
            }
            return
        }

        val position = location.blockIndex + 1
        val anchor = anchorOffset()
        val view = layoutManager.findViewByPosition(position)
        val lineTop = view?.let { adapter.getCurrentLineTop(recycler.getChildViewHolder(it)) }
        if (view == null || lineTop == null) {
            // far away or not laid out: jump to the paragraph, the next update fine-tunes it
            layoutManager.scrollToPositionWithOffset(position, anchor)
            return
        }

        val lineY = view.top + lineTop
        val isComfortable = lineY >= height * MIN_COMFORT_FRACTION &&
            lineY <= height * MAX_COMFORT_FRACTION

        when {
            !smooth -> recycler.scrollBy(0, lineY - anchor)
            !isComfortable -> recycler.smoothScrollBy(0, lineY - anchor)
        }
    }

    companion object {
        private const val ANCHOR_FRACTION = 0.3
        private const val MIN_COMFORT_FRACTION = 0.12
        private const val MAX_COMFORT_FRACTION = 0.65
    }
}
