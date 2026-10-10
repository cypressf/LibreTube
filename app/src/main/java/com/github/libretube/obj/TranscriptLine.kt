package com.github.libretube.obj

/**
 * A single line of a video transcript.
 *
 * @param startMs position in the video (in milliseconds) at which the line is first spoken
 * @param text the spoken text
 */
data class TranscriptLine(
    val startMs: Long,
    val text: String
)
