package com.github.libretube.ui.fragments

import android.annotation.SuppressLint
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.constraintlayout.motion.widget.MotionLayout
import androidx.constraintlayout.motion.widget.TransitionAdapter
import androidx.core.math.MathUtils.clamp
import androidx.core.os.bundleOf
import androidx.core.view.isGone
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.commit
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import com.github.libretube.R
import com.github.libretube.api.JsonHelper
import com.github.libretube.api.obj.ChapterSegment
import com.github.libretube.api.obj.Streams
import com.github.libretube.constants.IntentData
import com.github.libretube.databinding.FragmentAudioPlayerBinding
import com.github.libretube.enums.PlayerCommand
import com.github.libretube.extensions.navigateVideo
import com.github.libretube.extensions.normalize
import com.github.libretube.extensions.seekBy
import com.github.libretube.extensions.toID
import com.github.libretube.extensions.togglePlayPauseState
import com.github.libretube.extensions.updateIfChanged
import com.github.libretube.helpers.AudioHelper
import com.github.libretube.helpers.BackgroundHelper
import com.github.libretube.helpers.ClipboardHelper
import com.github.libretube.helpers.ImageHelper
import com.github.libretube.helpers.NavigationHelper
import com.github.libretube.helpers.PlayerHelper
import com.github.libretube.helpers.ThemeHelper
import com.github.libretube.helpers.TranscriptHelper
import com.github.libretube.obj.TranscriptLine
import com.github.libretube.parcelable.PlayerData
import com.github.libretube.services.AbstractPlayerService
import com.github.libretube.services.OfflinePlayerService
import com.github.libretube.services.OnlinePlayerService
import com.github.libretube.ui.activities.AbstractPlayerHostActivity
import com.github.libretube.ui.adapters.ArticleAdapter
import com.github.libretube.ui.extensions.getSystemInsets
import com.github.libretube.ui.extensions.setOnBackPressed
import com.github.libretube.ui.interfaces.AudioPlayerOptions
import com.github.libretube.ui.listeners.AudioPlayerThumbnailListener
import com.github.libretube.ui.models.ChaptersViewModel
import com.github.libretube.ui.models.CommonPlayerViewModel
import com.github.libretube.ui.models.ReaderStyle
import com.github.libretube.ui.models.TranscriptViewModel
import com.github.libretube.ui.sheets.BaseBottomSheet
import com.github.libretube.ui.sheets.ChaptersBottomSheet
import com.github.libretube.ui.sheets.PlaybackOptionsSheet
import com.github.libretube.ui.sheets.PlayingQueueSheet
import com.github.libretube.ui.sheets.SleepTimerSheet
import com.github.libretube.ui.sheets.VideoOptionsBottomSheet
import com.github.libretube.util.ArticleFormatter
import com.github.libretube.util.DataSaverMode
import com.github.libretube.util.PlayingQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

@UnstableApi
class AudioPlayerFragment : Fragment(R.layout.fragment_audio_player), AudioPlayerOptions {
    private var _binding: FragmentAudioPlayerBinding? = null
    val binding get() = _binding!!

    private lateinit var audioHelper: AudioHelper
    private val activity get() = context as AbstractPlayerHostActivity
    private val viewModel: CommonPlayerViewModel by activityViewModels()
    private val chaptersModel: ChaptersViewModel by activityViewModels()
    private val transcriptViewModel: TranscriptViewModel by activityViewModels()

    /**
     * Whether the transcript is shown as an article instead of the player controls.
     */
    private var isReaderView = false
    private var formatJob: Job? = null
    private var formatJobInput: FormatInput? = null
    private var formattedArticle: FormattedArticle? = null

    /**
     * Whether to tell the user that there's no transcript, after they opened reader view.
     */
    private var showNoTranscriptHint = false

    /**
     * What an article is formatted from, to avoid formatting the same transcript again.
     */
    private data class FormatInput(
        val lines: List<TranscriptLine>,
        val chapters: List<ChapterSegment>,
        val language: String?
    )

    private data class FormattedArticle(val input: FormatInput, val article: ArticleFormatter.Article)

    // for the transition
    private var transitionStartId = 0
    private var transitionEndId = 0

    private var handler = Handler(Looper.getMainLooper())
    private val seekBarUpdater = Runnable { updateSeekBar() }
    private var isSeekBarListenerAdded = false
    private var isPaused = !PlayerHelper.playAutomatically

    var isOffline: Boolean = false
        private set
    private var playerController: MediaController? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        audioHelper = AudioHelper(requireContext())

        isOffline = requireArguments().getBoolean(IntentData.offlinePlayer)

        BackgroundHelper.startMediaService(
            requireContext(),
            if (isOffline) OfflinePlayerService::class.java else OnlinePlayerService::class.java,
        ) {
            if (_binding == null) {
                it.sendCustomCommand(AbstractPlayerService.stopServiceCommand, Bundle.EMPTY)
                it.release()
                return@startMediaService
            }

            playerController = it
            handleServiceConnection()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentAudioPlayerBinding.bind(view)
        super.onViewCreated(view, savedInstanceState)

        // manually apply additional padding for edge-to-edge compatibility
        activity.getSystemInsets()?.let { systemBars ->
            with(binding.audioPlayerMain) {
                setPadding(
                    paddingLeft,
                    paddingTop + systemBars.top,
                    paddingRight,
                    paddingBottom + systemBars.bottom
                )
            }
        }

        initializeTransitionLayout()

        // select the title TV in order for it to automatically scroll
        binding.title.isSelected = true
        binding.uploader.isSelected = true

        binding.title.setOnLongClickListener {
            ClipboardHelper.save(requireContext(), text = binding.title.text.toString())
            true
        }

        binding.minimizePlayer.setOnClickListener { minimize() }

        binding.autoPlay.isChecked = PlayerHelper.autoPlayEnabled
        binding.autoPlay.setOnCheckedChangeListener { _, isChecked ->
            PlayerHelper.autoPlayEnabled = isChecked
        }

        binding.prev.setOnClickListener {
            playerController?.navigateVideo(PlayingQueue.getPrev() ?: return@setOnClickListener)
        }

        binding.next.setOnClickListener {
            playerController?.navigateVideo(PlayingQueue.getNext() ?: return@setOnClickListener)
        }

        binding.rewindBTN.setOnClickListener {
            playerController?.seekBy(-PlayerHelper.seekIncrement)
        }
        binding.forwardBTN.setOnClickListener {
            playerController?.seekBy(PlayerHelper.seekIncrement)
        }

        childFragmentManager.setFragmentResultListener(
            PlayingQueueSheet.PLAYING_QUEUE_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, args ->
            playerController?.navigateVideo(
                args.getString(IntentData.videoId) ?: return@setFragmentResultListener
            )
        }
        binding.openQueue.setOnClickListener {
            PlayingQueueSheet().show(childFragmentManager)
        }

        binding.playbackOptions.setOnClickListener {
            playerController?.let {
                PlaybackOptionsSheet(it)
                    .show(childFragmentManager)
            }
        }

        binding.sleepTimer.setOnClickListener {
            SleepTimerSheet().show(childFragmentManager)
        }

        binding.openVideo.setOnClickListener {
            val currentId = PlayingQueue.getCurrent()?.url?.toID()
            switchToVideoMode(currentId ?: return@setOnClickListener)
        }

        childFragmentManager.setFragmentResultListener(
            ChaptersBottomSheet.SEEK_TO_POSITION_REQUEST_KEY,
            viewLifecycleOwner
        ) { _, bundle ->
            playerController?.seekTo(bundle.getLong(IntentData.currentPosition))
        }

        chaptersModel.chaptersLiveData.observe(viewLifecycleOwner) { chapters ->
            _binding?.openChapters?.isVisible = !chapters.isNullOrEmpty()
            renderArticle()
        }

        binding.openChapters.setOnClickListener {
            ChaptersBottomSheet()
                .apply {
                    arguments = bundleOf(
                        IntentData.duration to playerController?.duration?.div(1000)
                    )
                }
                .show(childFragmentManager)
        }

        binding.miniPlayerClose.setOnClickListener {
            killFragment(true)
        }

        val listener = AudioPlayerThumbnailListener(requireContext(), this)
        binding.thumbnail.setOnTouchListener(listener)

        binding.playPause.setOnClickListener {
            playerController?.togglePlayPauseState()
        }

        binding.miniPlayerPause.setOnClickListener {
            playerController?.togglePlayPauseState()
        }

        binding.showMore.setOnClickListener {
            onLongTap()
        }

        // update the currently shown volume
        binding.volumeProgressBar.let { bar ->
            bar.progress = (audioHelper.deviceVolume * bar.max).toInt().coerceIn(0, bar.max)
        }

        if (!PlayerHelper.playAutomatically) updatePlayPauseButton()

        updateChapterIndex()

        initializeReaderView()

        val onBackPressedCallback = object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                binding.audioPlayerContainer.isClickable = false
                binding.playerMotionLayout.transitionToEnd()
                activity.minimizePlayerContainerLayout()
                activity.requestOrientationChange()
            }

            override fun handleOnBackProgressed(backEvent: BackEventCompat) {
                binding.playerMotionLayout.progress = backEvent.progress
            }

            override fun handleOnBackCancelled() {
                binding.playerMotionLayout.transitionToStart()
            }
        }
        setOnBackPressed(onBackPressedCallback)

        viewModel.isMiniPlayerVisible.observe(viewLifecycleOwner) { isMiniPlayerVisible ->
            // re-add the callback on top of the back pressed dispatcher listeners stack,
            // so that it's the first one to become called while the full player is visible
            if (!isMiniPlayerVisible) {
                onBackPressedCallback.remove()
                setOnBackPressed(onBackPressedCallback)
            }

            // if the player is minimized, the fragment behind the player should handle the event
            onBackPressedCallback.isEnabled = isMiniPlayerVisible != true

            updateKeepScreenOn()
        }
    }

    fun switchToVideoMode(videoId: String) {
        playerController?.sendCustomCommand(
            AbstractPlayerService.runPlayerActionCommand,
            bundleOf(PlayerCommand.TOGGLE_AUDIO_ONLY_MODE.name to false)
        )

        killFragment(false)

        NavigationHelper.openVideoPlayerFragment(
            context = requireContext(),
            playerData = PlayerData(
                videoId = videoId,
                isOffline = isOffline
            ),
            alreadyStarted = true
        )
    }

    private fun killFragment(stopPlayer: Boolean) {
        viewModel.isMiniPlayerVisible.value = false

        if (stopPlayer) playerController?.sendCustomCommand(
            AbstractPlayerService.stopServiceCommand,
            Bundle.EMPTY
        )
        playerController?.release()
        playerController = null

        viewModel.isFullscreen.value = false
        binding.playerMotionLayout.transitionToEnd()
        activity.supportFragmentManager.commit {
            remove(this@AudioPlayerFragment)
        }
    }

    fun playNextVideo(videoId: String) {
        playerController?.navigateVideo(videoId)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initializeTransitionLayout() {
        activity.setPlayerContainerProgress(0f)

        binding.playerMotionLayout.addTransitionListener(object : TransitionAdapter() {
            override fun onTransitionChange(
                motionLayout: MotionLayout?,
                startId: Int,
                endId: Int,
                progress: Float
            ) {
                activity.setPlayerContainerProgress(progress.absoluteValue)
                transitionEndId = endId
                transitionStartId = startId
            }

            override fun onTransitionCompleted(motionLayout: MotionLayout?, currentId: Int) {
                if (currentId == transitionEndId) {
                    viewModel.isMiniPlayerVisible.value = true
                    activity.minimizePlayerContainerLayout()
                } else if (currentId == transitionStartId) {
                    viewModel.isMiniPlayerVisible.value = false
                    activity.maximizePlayerContainerLayout()
                }
            }
        })

        if (arguments?.getBoolean(IntentData.minimizeByDefault, false) != true) {
            binding.playerMotionLayout.progress = 1f
            binding.playerMotionLayout.transitionToStart()
        } else {
            binding.playerMotionLayout.progress = 0f
            binding.playerMotionLayout.transitionToEnd()
        }
    }

    /**
     * Load the information from a new stream into the UI
     */
    private fun updateStreamInfo(metadata: MediaMetadata) {
        val binding = _binding ?: return

        binding.title.text = metadata.title
        binding.miniPlayerTitle.text = metadata.title

        binding.uploader.text = metadata.artist
        binding.uploader.setOnClickListener {
            val uploaderId = metadata.composer?.toString() ?: return@setOnClickListener
            NavigationHelper.navigateChannel(requireContext(), uploaderId)
        }

        metadata.artworkUri?.let { updateThumbnailAsync(it) }

        initializeSeekBar()
        updateReaderButton()

        if (isReaderView) {
            loadTranscript()
            renderArticle()
        }
    }

    private fun updateThumbnailAsync(thumbnailUri: Uri) {
        if (DataSaverMode.isEnabled(requireContext()) && !isOffline) {
            binding.progress.isVisible = false
            binding.thumbnail.setImageResource(R.drawable.ic_launcher_monochrome)
            val primaryColor = ThemeHelper.getThemeColor(
                requireContext(),
                androidx.appcompat.R.attr.colorPrimary
            )
            binding.thumbnail.setColorFilter(primaryColor)
            return
        }

        binding.progress.isVisible = true
        binding.thumbnail.isGone = true
        // reset color filter if data saver mode got toggled or conditions for it changed
        binding.thumbnail.setColorFilter(Color.TRANSPARENT)

        lifecycleScope.launch {
            val binding = _binding ?: return@launch
            val bitmap = ImageHelper.getImage(requireContext(), thumbnailUri)
            binding.thumbnail.setImageBitmap(bitmap)
            binding.miniPlayerThumbnail.setImageBitmap(bitmap)
            binding.thumbnail.isVisible = true
            binding.progress.isGone = true
        }
    }

    private fun initializeSeekBar() {
        val binding = _binding ?: return
        if (!isSeekBarListenerAdded) {
            binding.timeBar.addOnChangeListener { _, value, fromUser ->
                if (fromUser) playerController?.seekTo(value.toLong() * 1000)
            }
            isSeekBarListenerAdded = true
        }
        // this is called for every new video, make sure only one update loop is running
        handler.removeCallbacks(seekBarUpdater)
        updateSeekBar()
    }

    /**
     * Update the position, duration and text views belonging to the seek bar
     */
    private fun updateSeekBar() {
        val binding = _binding ?: return
        val duration = playerController?.duration?.takeIf { it > 0 } ?: let {
            // if there's no duration available, clear everything
            binding.timeBar.value = 0f
            binding.duration.text = ""
            binding.currentPosition.text = ""
            handler.postDelayed(seekBarUpdater, 100)
            return
        }
        val currentPosition = playerController?.currentPosition?.toFloat() ?: 0f

        // set the text for the indicators
        binding.duration.text = DateUtils.formatElapsedTime(duration / 1000)
        binding.currentPosition.text = DateUtils.formatElapsedTime(
            (currentPosition / 1000).toLong()
        )

        if (isReaderView && viewModel.isMiniPlayerVisible.value != true) {
            binding.articleReader.updatePosition(currentPosition.toLong())
        }

        // update the time bar current value and maximum value
        binding.timeBar.valueTo = (duration / 1000).toFloat()
        binding.timeBar.value = clamp(
            currentPosition / 1000,
            binding.timeBar.valueFrom,
            binding.timeBar.valueTo
        )

        handler.postDelayed(seekBarUpdater, 200)
    }

    private fun updatePlayPauseButton() {
        playerController?.let {
            val binding = _binding ?: return

            val iconRes = PlayerHelper.getPlayPauseActionIcon(it)
            binding.playPause.setIconResource(iconRes)
            binding.miniPlayerPause.setImageResource(iconRes)
        }
    }

    private fun handleServiceConnection() {
        playerController?.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                super.onIsPlayingChanged(isPlaying)

                updatePlayPauseButton()
                isPaused = !isPlaying
                updateKeepScreenOn()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                super.onMediaMetadataChanged(mediaMetadata)

                updateStreamInfo(mediaMetadata)
                // JSON-encode as work-around for https://github.com/androidx/media/issues/564
                val chapters: List<ChapterSegment>? =
                    mediaMetadata.extras?.getString(IntentData.chapters)?.let {
                        JsonHelper.json.decodeFromString(it)
                    }
                chaptersModel.chaptersLiveData.value = chapters
            }
        })
        playerController?.mediaMetadata?.let { updateStreamInfo(it) }
        // JSON-encode as work-around for https://github.com/androidx/media/issues/564
        chaptersModel.chaptersLiveData.value =
            playerController?.mediaMetadata?.extras?.getString(IntentData.chapters)?.let {
                JsonHelper.json.decodeFromString(it)
            }

        updatePlayPauseButton()
        initializeSeekBar()
        updateKeepScreenOn()
    }

    private fun minimize() {
        activity.minimizePlayerContainerLayout()
        binding.playerMotionLayout.transitionToEnd()
    }

    private fun initializeReaderView() {
        binding.articleReader.onSeek = { positionMs -> playerController?.seekTo(positionMs) }
        binding.articleReader.onLanguageClick = { showTranscriptLanguageSelection() }
        binding.articleReader.onRetry = { transcriptViewModel.retry() }
        binding.articleReader.onMinimize = { minimize() }
        binding.articleReader.onShowPlayer = { setReaderView(false) }

        binding.openReaderView.setOnClickListener {
            showNoTranscriptHint = true
            setReaderView(true)
        }
        updateReaderButton()

        transcriptViewModel.state.observe(viewLifecycleOwner) { renderArticle() }

        // downloaded videos don't have a transcript to read
        setReaderView(!isOffline && requireArguments().getBoolean(IntentData.readerView, false))
    }

    /**
     * Show the transcript as an article instead of the player, e.g. when the user opened the video
     * with "Read" while this player was already running.
     */
    fun showReaderView() {
        if (isOffline) return
        showNoTranscriptHint = true
        setReaderView(true)
    }

    private fun setReaderView(enabled: Boolean) {
        isReaderView = enabled
        // remember the mode, so that it survives e.g. rotating the screen
        requireArguments().putBoolean(IntentData.readerView, enabled)

        if (enabled) loadTranscript()
        renderArticle()
        updateKeepScreenOn()
    }

    /**
     * Only offer reader view for videos that have a transcript.
     */
    private fun updateReaderButton() {
        val binding = _binding ?: return
        val streams = getCurrentStreams()
        binding.openReaderView.isVisible =
            !isOffline && streams != null && TranscriptHelper.hasTranscript(streams)
    }

    /**
     * Show either the article on its own, using the full height, or the player as usual.
     */
    private fun updateReaderVisibility(showReader: Boolean) {
        val binding = _binding ?: return
        binding.articleReader.isVisible = showReader
        binding.thumbnailContainer.isInvisible = showReader
        for (view in listOf(
            binding.topBar, binding.titleLayout, binding.seekBarLayout, binding.timeLayout,
            binding.buttonGroup, binding.bottomBar
        )) {
            view.isGone = showReader
        }
    }

    override fun onResume() {
        super.onResume()
        // the font settings might have changed while the player was in the background
        _binding?.articleReader?.setStyle(ReaderStyle.fromPreferences(requireContext()))
    }

    private fun updateKeepScreenOn() {
        val binding = _binding ?: return
        // reading along shouldn't be interrupted by the screen turning off
        binding.articleReader.keepScreenOn = isReaderView &&
            binding.articleReader.isVisible &&
            viewModel.isMiniPlayerVisible.value != true &&
            playerController?.isPlaying == true
    }

    private fun getCurrentVideoId(): String? =
        playerController?.mediaMetadata?.extras?.getString(IntentData.videoId)

    private fun getCurrentStreams(): Streams? =
        playerController?.mediaMetadata?.extras?.getString(IntentData.streams)?.let {
            runCatching { JsonHelper.json.decodeFromString<Streams>(it) }.getOrNull()
        }

    /**
     * Start loading the transcript of the current video, if it isn't loaded already.
     */
    private fun loadTranscript() {
        val videoId = getCurrentVideoId() ?: return
        val streams = getCurrentStreams()

        val subtitles = if (streams != null && TranscriptHelper.hasTranscript(streams)) {
            streams.subtitles
        } else {
            emptyList()
        }
        transcriptViewModel.setVideo(videoId, subtitles, PlayerHelper.defaultSubtitleCode)
    }

    private fun buildArticleHeader(
        article: ArticleFormatter.Article?,
        showLanguage: Boolean = true
    ): ArticleAdapter.Header {
        val metadata = playerController?.mediaMetadata
        val subtitle = transcriptViewModel.selectedSubtitle?.takeIf { showLanguage }

        return ArticleAdapter.Header(
            title = metadata?.title?.toString().orEmpty(),
            author = metadata?.artist?.toString().orEmpty(),
            meta = article?.let {
                resources.getQuantityString(
                    R.plurals.reading_time_minutes,
                    it.readingTimeMinutes,
                    it.readingTimeMinutes
                )
            }.orEmpty(),
            language = subtitle?.let { TranscriptHelper.getDisplayName(requireContext(), it) },
            canChangeLanguage = showLanguage && transcriptViewModel.subtitles.size > 1,
            showNoPunctuationNote = article != null && !article.isPunctuated
        )
    }

    /**
     * Show the current transcript state in the article reader.
     */
    private fun renderArticle() {
        val binding = _binding ?: return
        if (!isReaderView) {
            formatJob?.cancel()
            updateReaderVisibility(false)
            updateKeepScreenOn()
            return
        }

        // until the player is connected, it's unknown which video the transcript belongs to,
        // and the shared view model might still hold the transcript of another video
        val currentVideoId = getCurrentVideoId()
        val state = transcriptViewModel.state.value
        if (currentVideoId == null || currentVideoId != transcriptViewModel.videoId) {
            formatJob?.cancel()
            updateReaderVisibility(true)
            binding.articleReader.showLoading(buildArticleHeader(null, showLanguage = false))
            return
        }

        if (state !is TranscriptViewModel.State.Loaded) formatJob?.cancel()

        // if the video has no subtitles at all, show the thumbnail as usual; if only the selected
        // one is empty, keep the reader, so that another language can be picked
        val hasNoSubtitles = transcriptViewModel.subtitles.isEmpty()
        updateReaderVisibility(!(state == TranscriptViewModel.State.Empty && hasNoSubtitles))
        updateKeepScreenOn()

        when (state) {
            null, TranscriptViewModel.State.Loading ->
                binding.articleReader.showLoading(buildArticleHeader(null))

            TranscriptViewModel.State.Empty -> if (hasNoSubtitles) {
                if (showNoTranscriptHint) {
                    Toast.makeText(context, R.string.transcript_unavailable, Toast.LENGTH_SHORT)
                        .show()
                }
            } else {
                binding.articleReader.showMessage(
                    buildArticleHeader(null),
                    getString(R.string.transcript_unavailable),
                    canRetry = false
                )
            }

            TranscriptViewModel.State.Error -> binding.articleReader.showMessage(
                buildArticleHeader(null),
                getString(R.string.transcript_load_failed),
                canRetry = true
            )

            is TranscriptViewModel.State.Loaded -> showFormattedArticle(state.lines)
        }

        // only hint once after the user opened reader view
        if (state != null && state != TranscriptViewModel.State.Loading) showNoTranscriptHint = false
    }

    private fun showFormattedArticle(lines: List<TranscriptLine>) {
        val chapters = chaptersModel.chapters.filter { it.highlightDrawable == null }
        val language = transcriptViewModel.selectedSubtitle?.code
        val input = FormatInput(lines, chapters, language)

        val cached = formattedArticle
        if (cached != null && cached.input == input) {
            showArticle(cached.article)
            return
        }

        // the same transcript is being formatted already
        if (formatJob?.isActive == true && formatJobInput == input) return

        formatJob?.cancel()
        formatJobInput = input
        formatJob = viewLifecycleOwner.lifecycleScope.launch {
            val article = withContext(Dispatchers.Default) {
                ArticleFormatter.format(
                    lines,
                    chapters.map { ArticleFormatter.Chapter(it.title, it.start * 1000) },
                    language
                )
            }
            formattedArticle = FormattedArticle(input, article)

            // the transcript might have changed while formatting
            val currentLines = (transcriptViewModel.state.value as? TranscriptViewModel.State.Loaded)?.lines
            if (currentLines === lines && isReaderView) showArticle(article)
        }
    }

    private fun showArticle(article: ArticleFormatter.Article) {
        val binding = _binding ?: return
        binding.articleReader.showArticle(
            buildArticleHeader(article),
            article,
            playerController?.currentPosition ?: 0
        )
    }

    private fun showTranscriptLanguageSelection() {
        val subtitles = transcriptViewModel.subtitles
        val names = subtitles.map { TranscriptHelper.getDisplayName(requireContext(), it) }
        val selected = transcriptViewModel.selectedSubtitle
            ?.let { TranscriptHelper.getDisplayName(requireContext(), it) }

        BaseBottomSheet()
            .setSimpleItems(names, preselectedItem = selected) { index ->
                transcriptViewModel.selectSubtitle(subtitles[index])
            }
            .show(childFragmentManager)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(seekBarUpdater)
        isSeekBarListenerAdded = false
        _binding = null
    }

    override fun onSingleTap() {
        playerController?.togglePlayPauseState()
    }

    override fun onLongTap() {
        val current = PlayingQueue.getCurrent() ?: return
        VideoOptionsBottomSheet()
            .apply {
                arguments = bundleOf(IntentData.streamItem to current)
            }
            .show(childFragmentManager)
    }

    override fun onSwipe(distanceY: Float) {
        if (!PlayerHelper.swipeGestureEnabled) return

        binding.volumeControls.isVisible = true
        updateVolume(distanceY)
    }

    override fun onSwipeEnd() {
        if (!PlayerHelper.swipeGestureEnabled) return

        binding.volumeControls.isGone = true
    }

    private fun updateVolume(distance: Float) {
        val bar = binding.volumeProgressBar
        binding.volumeControls.apply {
            if (isGone) {
                isVisible = true
                // Volume could be changed using other mediums, sync progress
                // bar with new value.
                bar.progress = (audioHelper.deviceVolume * bar.max).toInt().coerceIn(0, bar.max)
            }
        }

        if (bar.progress == 0) {
            binding.volumeImageView.setImageResource(
                when {
                    distance > 0 -> R.drawable.ic_volume_up
                    else -> R.drawable.ic_volume_off
                }
            )
        }
        bar.incrementProgressBy(distance.toInt() / 3)
        audioHelper.deviceVolume = bar.progress.toFloat() / bar.max

        binding.volumeTextView.text = "${bar.progress.normalize(0, bar.max, 0, 100)}"
    }

    private fun updateChapterIndex() {
        if (_binding == null) return
        handler.postDelayed(this::updateChapterIndex, 100)

        val currentIndex =
            PlayerHelper.getCurrentChapterIndex(
                playerController?.currentPosition ?: return,
                chaptersModel.chapters
            )
        chaptersModel.currentChapterIndex.updateIfChanged(currentIndex ?: return)
    }
}
