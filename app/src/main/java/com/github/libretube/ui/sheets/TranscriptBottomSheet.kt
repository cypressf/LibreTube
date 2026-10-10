package com.github.libretube.ui.sheets

import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import androidx.core.os.bundleOf
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.setFragmentResult
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.R
import com.github.libretube.api.obj.Subtitle
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.BottomSheetTranscriptBinding
import com.github.libretube.ui.adapters.TranscriptAdapter
import com.github.libretube.ui.extensions.onSystemInsets
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.TranscriptViewModel
import com.github.libretube.util.TranscriptMerger
import java.util.Locale

/**
 * Shows the transcript of the current video, built from its subtitles.
 *
 * Tapping a line seeks the player to it, and the line that's currently spoken is highlighted.
 */
class TranscriptBottomSheet : ExpandablePlayerSheet(R.layout.bottom_sheet_transcript) {
    private var _binding: BottomSheetTranscriptBinding? = null
    private val binding get() = _binding!!

    private val transcriptViewModel: TranscriptViewModel by activityViewModels()
    private val commonPlayerViewModel: CommonPlayerViewModel by activityViewModels()

    private lateinit var adapter: TranscriptAdapter
    private lateinit var layoutManager: LinearLayoutManager

    /**
     * Time at which the user last scrolled the list manually, auto-scrolling is paused for a few
     * seconds afterwards so that it doesn't fight the user.
     */
    private var lastManualScrollAt = 0L
    private var isDragging = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = BottomSheetTranscriptBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        transcriptViewModel.isSheetOpen = true

        layoutManager = LinearLayoutManager(context)
        adapter = TranscriptAdapter { positionMs ->
            lastManualScrollAt = 0L
            setFragmentResult(
                ChaptersBottomSheet.SEEK_TO_POSITION_REQUEST_KEY,
                bundleOf(IntentData.currentPosition to positionMs)
            )
        }
        binding.transcriptRecycler.layoutManager = layoutManager
        binding.transcriptRecycler.adapter = adapter
        binding.transcriptRecycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // count the pause from the end of the user's scroll, not its start
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) isDragging = true
                if (isDragging) lastManualScrollAt = SystemClock.elapsedRealtime()
                if (newState == RecyclerView.SCROLL_STATE_IDLE) isDragging = false
            }
        })

        // add bottom padding to the list, to ensure that the last item is not overlapped by the system bars
        binding.transcriptRecycler.onSystemInsets { v, systemInsets ->
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, systemInsets.bottom)
        }

        binding.retryButton.setOnClickListener { transcriptViewModel.retry() }
        binding.languageButton.setOnClickListener { showLanguageSelection() }
        updateLanguageButton()

        transcriptViewModel.state.observe(viewLifecycleOwner) { state ->
            updateLanguageButton()
            binding.progress.isVisible = state is TranscriptViewModel.State.Loading
            binding.transcriptRecycler.isVisible = state is TranscriptViewModel.State.Loaded
            binding.messageLayout.isVisible = state is TranscriptViewModel.State.Empty ||
                state is TranscriptViewModel.State.Error
            binding.retryButton.isVisible = state is TranscriptViewModel.State.Error

            when (state) {
                is TranscriptViewModel.State.Loaded -> {
                    adapter.submitLines(state.lines)
                    transcriptViewModel.positionMs.value?.let { highlightPosition(it, true) }
                }

                TranscriptViewModel.State.Empty ->
                    binding.messageText.setText(R.string.transcript_unavailable)

                TranscriptViewModel.State.Error ->
                    binding.messageText.setText(R.string.transcript_load_failed)

                TranscriptViewModel.State.Loading -> adapter.submitLines(emptyList())
            }
        }

        transcriptViewModel.positionMs.observe(viewLifecycleOwner) { positionMs ->
            highlightPosition(positionMs, false)
        }
    }

    private fun highlightPosition(positionMs: Long, forceScroll: Boolean) {
        if (_binding == null || adapter.lines.isEmpty()) return

        val index = TranscriptMerger.indexAt(adapter.lines, positionMs)
        val changed = index != adapter.selectedPosition
        adapter.updateSelectedPosition(index)
        if (index < 0) return

        val userIsScrolling = isDragging ||
            SystemClock.elapsedRealtime() - lastManualScrollAt < AUTO_SCROLL_PAUSE_MS
        if (forceScroll || (changed && !userIsScrolling)) scrollToLine(index, forceScroll)
    }

    /**
     * Keep the current line in the upper part of the list, so the upcoming lines stay visible.
     */
    private fun scrollToLine(index: Int, force: Boolean) {
        val recycler = binding.transcriptRecycler
        val first = layoutManager.findFirstCompletelyVisibleItemPosition()
        val last = layoutManager.findLastCompletelyVisibleItemPosition()
        val isComfortablyVisible = first != RecyclerView.NO_POSITION &&
            index in (first + 1)..(last - 2)

        if (force || !isComfortablyVisible) {
            // runs right away if the list is already laid out, otherwise once it is
            recycler.doOnLayout {
                if (_binding != null) layoutManager.scrollToPositionWithOffset(index, it.height / 4)
            }
        }
    }

    private fun updateLanguageButton() {
        val subtitles = transcriptViewModel.subtitles
        binding.languageButton.isVisible = subtitles.isNotEmpty()
        binding.languageButton.isEnabled = subtitles.size > 1
        binding.languageButton.text = transcriptViewModel.selectedSubtitle?.let(::getDisplayName)
    }

    private fun showLanguageSelection() {
        val subtitles = transcriptViewModel.subtitles
        val names = subtitles.map(::getDisplayName)

        BaseBottomSheet()
            .setSimpleItems(
                names,
                preselectedItem = transcriptViewModel.selectedSubtitle?.let(::getDisplayName)
            ) { index ->
                transcriptViewModel.selectSubtitle(subtitles[index])
            }
            .show(parentFragmentManager)
    }

    private fun getDisplayName(subtitle: Subtitle): String {
        val language = subtitle.code
            ?.let { Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()) }
            ?.takeIf { it.isNotBlank() }
            ?: subtitle.name
            ?: subtitle.code
            ?: getString(R.string.unknown)

        return if (subtitle.autoGenerated == true) {
            "$language (${getString(R.string.auto_generated)})"
        } else {
            language
        }
    }

    override fun getSheetMaxHeightPx() = commonPlayerViewModel.maxSheetHeightPx

    override fun getDragHandle() = binding.dragHandle

    override fun getBottomSheet() = binding.standardBottomSheet

    override fun onStart() {
        super.onStart()
        // remove internal padding from the bottomsheet
        // https://github.com/material-components/material-components-android/issues/3389#issuecomment-2049028605
        dialog?.window?.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )
    }

    override fun onDestroyView() {
        transcriptViewModel.isSheetOpen = false
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "TranscriptBottomSheet"
        private const val AUTO_SCROLL_PAUSE_MS = 4000L
    }
}
