package com.github.libretube.util

import com.github.libretube.obj.TranscriptLine

/**
 * Turns the time slices emitted by a subtitle parser into a readable list of transcript lines.
 *
 * Subtitle parsers emit one slice per time range, containing every cue that is visible during it.
 * The same caption line therefore usually shows up in several consecutive slices, and YouTube's
 * auto-generated captions additionally "roll" text upwards, so that the second line of one cue
 * becomes the first line of the next one. This class only keeps the moment each line first appears.
 *
 * Pure Kotlin without Android dependencies, so it can be unit-tested on the JVM.
 */
object TranscriptMerger {
    /**
     * A time range of a subtitle file, together with the text of all cues visible during it.
     *
     * @param endMs end of the time range, or null if unknown
     */
    data class CueSlice(
        val startMs: Long,
        val endMs: Long?,
        val texts: List<String>
    )

    private val whitespace = Regex("\\s+")

    fun merge(slices: List<CueSlice>): List<TranscriptLine> {
        val transcript = mutableListOf<TranscriptLine>()
        var previousLines = emptySet<String>()
        var previousEndMs: Long? = null

        for (slice in slices.sortedBy { it.startMs }) {
            // after a pause without any captions, a line may legitimately be spoken again
            if (previousEndMs != null && slice.startMs > previousEndMs) {
                previousLines = emptySet()
            }

            val currentLines = slice.texts
                .flatMap { it.lines() }
                .map { it.replace(whitespace, " ").trim() }
                .filter { it.isNotEmpty() }
                .distinct()

            // lines that start at the same time (e.g. a two-line cue) form one entry, so that
            // each entry has a distinct timestamp to seek to and highlight
            val newLines = currentLines.filter { it !in previousLines }
            if (newLines.isNotEmpty()) {
                transcript.add(TranscriptLine(slice.startMs, newLines.joinToString(" ")))
            }

            previousLines = currentLines.toSet()
            previousEndMs = slice.endMs
        }

        return transcript
    }
}
