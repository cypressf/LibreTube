package com.github.libretube.util

import com.github.libretube.obj.TranscriptLine

/**
 * Turns a transcript into an article that reads like prose: chapters become headings and
 * caption lines are joined into paragraphs.
 *
 * Captions written by people usually have punctuation, so paragraphs end at sentence boundaries.
 * Auto-generated captions usually don't, so they're split into chunks of roughly half a minute,
 * at the longest pause within that range.
 *
 * Pure Kotlin without Android dependencies, so it can be unit-tested on the JVM.
 */
object ArticleFormatter {
    data class Chapter(val title: String, val startMs: Long)

    /**
     * A caption line within a paragraph, covering the characters from [textStart] (inclusive)
     * to [textEnd] (exclusive) of the paragraph's text.
     */
    data class Segment(val startMs: Long, val textStart: Int, val textEnd: Int)

    sealed interface Block {
        val startMs: Long
    }

    data class Heading(val title: String, override val startMs: Long) : Block

    data class Paragraph(val text: String, val segments: List<Segment>) : Block {
        override val startMs get() = segments.first().startMs

        /**
         * The index of the segment that contains the character at [offset], or -1.
         */
        fun segmentAtOffset(offset: Int): Int =
            segments.indexOfFirst { offset >= it.textStart && offset < it.textEnd }
                .takeIf { it >= 0 }
                // the space between two segments belongs to the previous one
                ?: segments.indexOfLast { offset >= it.textStart }
    }

    /**
     * Where in the article a playback position is: the block and the segment within it.
     */
    data class Location(val blockIndex: Int, val segmentIndex: Int)

    data class Article(
        val blocks: List<Block>,
        val wordCount: Int,
        /** false if the captions had (almost) no punctuation, e.g. auto-generated ones */
        val isPunctuated: Boolean
    ) {
        // flattened (startMs, location) pairs, sorted by time, for looking up positions
        private val index: List<Pair<Long, Location>> by lazy {
            blocks.flatMapIndexed { blockIndex, block ->
                if (block !is Paragraph) return@flatMapIndexed emptyList()
                block.segments.mapIndexed { segmentIndex, segment ->
                    segment.startMs to Location(blockIndex, segmentIndex)
                }
            }.sortedBy { it.first }
        }

        /**
         * Find the segment that's spoken at [positionMs], or null if it's before the first one.
         */
        fun locate(positionMs: Long): Location? {
            var low = 0
            var high = index.size - 1
            var result: Location? = null
            while (low <= high) {
                val mid = (low + high) ushr 1
                if (index[mid].first <= positionMs) {
                    result = index[mid].second
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            return result
        }

        val readingTimeMinutes get() = maxOf(1, (wordCount + WORDS_PER_MINUTE / 2) / WORDS_PER_MINUTE)
    }

    private const val WORDS_PER_MINUTE = 230

    // paragraphs of captions with punctuation end at the first sentence end after this length
    private const val TARGET_PARAGRAPH_CHARS = 320
    // ... or earlier, if the speaker pauses noticeably and the paragraph has at least this length
    private const val MIN_PARAGRAPH_CHARS_AT_PAUSE = 100
    // ... and are split even without a sentence end if they get too long
    private const val MAX_PARAGRAPH_CHARS = 1000

    // paragraphs of captions without punctuation are split at the longest pause in this range
    private const val MIN_UNPUNCTUATED_CHARS = 280
    private const val MAX_UNPUNCTUATED_CHARS = 560

    // a pause of at least this length counts as a deliberate break in speech
    private const val LONG_PAUSE_MS = 1800L
    // typical speaking rate, used to estimate how long a line takes to say
    private const val CHARS_PER_SECOND = 15.0

    // captions that have fewer sentence ends per 100 words are treated as unpunctuated
    private const val MIN_SENTENCE_ENDS_PER_100_WORDS = 1.5

    // captions often start shortly before the chapter they belong to
    private const val CHAPTER_TOLERANCE_MS = 1000L

    private val whitespace = Regex("\\s+")

    // ellipses in captions usually mark where a sentence continues in the next caption, or a pause
    private val leadingEllipsis = Regex("^(?:\\.{3,}|…)\\s*")
    private val trailingEllipsis = Regex("\\s*(?:\\.{3,}|…)$")
    private val innerEllipsis = Regex("\\s*(?:\\.{3,}|…)\\s*(?=[\\p{L}\\p{N}\"'“‘(])")
    private val startsWithI = Regex("^[\"'“‘(]?I(?:'[a-z]+)?\\b")
    private val endsWithPunctuation = Regex("[.!?,;:—–-][\"'”’)\\]]*$")
    private val soundTag = Regex("\\[[^\\]]{0,40}]")
    private val soundOnlyLine = Regex("^\\([^)]{0,40}\\)$")
    private val musicNotes = Regex("[♪♫♬]")
    private val speakerChange = Regex("^>>\\s*")
    private val sentenceEnd = Regex("[.!?…][\"'”’)\\]]*$")
    private val sentenceEndAnywhere = Regex("[.!?…](\\s|$)")
    // "i" and "i'm", "i've" etc., but not "i.e." or the "i" in "Hawai'i"
    private val standaloneI = Regex("(?<![\\w'])i(?=[\\s,;:!?]|$|'(?:m|ve|ll|d|s)\\b)")

    private data class CaptionUnit(
        val startMs: Long,
        val text: String,
        val startsNewSpeaker: Boolean
    )

    fun format(
        lines: List<TranscriptLine>,
        chapters: List<Chapter> = emptyList(),
        language: String? = null
    ): Article {
        val units = clean(lines)
        val words = units.sumOf { unit -> unit.text.split(' ').count { it.isNotEmpty() } }
        val sentenceEnds = units.sumOf { sentenceEndAnywhere.findAll(it.text).count() }
        val isPunctuated = words > 0 &&
            sentenceEnds * 100.0 / words >= MIN_SENTENCE_ENDS_PER_100_WORDS
        val isEnglish = language?.substringBefore('-')?.equals("en", ignoreCase = true) == true

        val blocks = mutableListOf<Block>()
        // split the transcript into sections at the chapter starts
        for ((chapter, sectionUnits) in splitIntoSections(units, chapters)) {
            if (chapter != null) blocks.add(Heading(chapter.title.trim(), chapter.startMs))

            val paragraphs = if (isPunctuated) {
                splitPunctuated(sectionUnits)
            } else {
                splitUnpunctuated(sectionUnits)
            }
            paragraphs.mapTo(blocks) { paragraphUnits ->
                buildParagraph(paragraphUnits, capitalize = !isPunctuated, isEnglish)
            }
        }

        return Article(blocks, words, isPunctuated)
    }

    private fun clean(lines: List<TranscriptLine>): List<CaptionUnit> = lines
        .sortedBy { it.startMs }
        .mapNotNull { line ->
            var text = line.text.replace(whitespace, " ").trim()
            val startsNewSpeaker = speakerChange.containsMatchIn(text)
            text = text.replace(speakerChange, "")
            if (soundOnlyLine.matches(text)) return@mapNotNull null
            text = text.replace(soundTag, "")
                .replace(musicNotes, "")
                .replace(whitespace, " ")
                .trim()
            if (text.isEmpty()) null else CaptionUnit(line.startMs, text, startsNewSpeaker)
        }
        .let(::normalizeEllipses)

    /**
     * Captions often use "..." where a sentence continues in the next caption ("average it out..."
     * followed by "to somewhere in the range") or for a short pause. In an article they're noise
     * and would end paragraphs mid-sentence, so they become a space, or a period if a new sentence
     * follows.
     */
    private fun normalizeEllipses(units: List<CaptionUnit>): List<CaptionUnit> {
        val texts = units.map { it.text.replace(leadingEllipsis, "") }

        return units.mapIndexedNotNull { index, unit ->
            var text = texts[index]

            if (trailingEllipsis.containsMatchIn(text)) {
                text = text.replace(trailingEllipsis, "")
                val next = texts.drop(index + 1).firstOrNull { it.isNotBlank() }
                val endsSentence = next == null || startsNewSentence(next)
                if (endsSentence && text.isNotEmpty() && !endsWithPunctuation.containsMatchIn(text)) {
                    text += "."
                }
            }

            text = innerEllipsis.replace(text) { match ->
                val following = text.substring(match.range.last + 1)
                if (startsNewSentence(following)) ". " else " "
            }.trim()

            if (text.isEmpty()) null else unit.copy(text = text)
        }
    }

    /**
     * Whether text after an ellipsis starts a new sentence: it starts with a capital letter, other
     * than the word "I", which is just as likely to continue the sentence.
     */
    private fun startsNewSentence(text: String): Boolean {
        val trimmed = text.trimStart()
        val firstLetter = trimmed.firstOrNull { it.isLetterOrDigit() } ?: return false
        return firstLetter.isUpperCase() && !startsWithI.containsMatchIn(trimmed)
    }

    private fun splitIntoSections(
        units: List<CaptionUnit>,
        chapters: List<Chapter>
    ): List<Pair<Chapter?, List<CaptionUnit>>> {
        val sortedChapters = chapters.filter { it.title.isNotBlank() }.sortedBy { it.startMs }
        val sections = mutableListOf<Pair<Chapter?, MutableList<CaptionUnit>>>()
        var currentSection: Pair<Chapter?, MutableList<CaptionUnit>>? = null
        var nextChapterIndex = 0

        for (unit in units) {
            // if several chapters start before this line, only the last of them has any text
            var chapter: Chapter? = null
            while (nextChapterIndex < sortedChapters.size &&
                unit.startMs >= sortedChapters[nextChapterIndex].startMs - CHAPTER_TOLERANCE_MS
            ) {
                chapter = sortedChapters[nextChapterIndex++]
            }

            if (chapter != null || currentSection == null) {
                currentSection = chapter to mutableListOf()
                sections.add(currentSection)
            }
            currentSection.second.add(unit)
        }

        return sections
    }

    /**
     * Estimated silence after [units]`[index]`, based on when the next line starts and how long
     * it takes to say this one.
     */
    private fun pauseAfter(units: List<CaptionUnit>, index: Int): Long {
        val next = units.getOrNull(index + 1) ?: return 0
        val current = units[index]
        val speakingTimeMs = (current.text.length / CHARS_PER_SECOND * 1000).toLong()
        return next.startMs - current.startMs - speakingTimeMs
    }

    private fun splitPunctuated(units: List<CaptionUnit>): List<List<CaptionUnit>> {
        val paragraphs = mutableListOf<List<CaptionUnit>>()
        var current = mutableListOf<CaptionUnit>()
        var chars = 0

        for ((index, unit) in units.withIndex()) {
            if (unit.startsNewSpeaker && current.isNotEmpty()) {
                paragraphs.add(current)
                current = mutableListOf()
                chars = 0
            }
            current.add(unit)
            chars += unit.text.length + 1

            val endsSentence = sentenceEnd.containsMatchIn(unit.text)
            val shouldBreak = when {
                endsSentence && chars >= TARGET_PARAGRAPH_CHARS -> true
                endsSentence && chars >= MIN_PARAGRAPH_CHARS_AT_PAUSE &&
                    pauseAfter(units, index) >= LONG_PAUSE_MS -> true
                chars >= MAX_PARAGRAPH_CHARS -> true
                else -> false
            }
            if (shouldBreak) {
                paragraphs.add(current)
                current = mutableListOf()
                chars = 0
            }
        }
        if (current.isNotEmpty()) paragraphs.add(current)

        return paragraphs
    }

    private fun splitUnpunctuated(units: List<CaptionUnit>): List<List<CaptionUnit>> {
        val paragraphs = mutableListOf<List<CaptionUnit>>()
        var start = 0

        while (start < units.size) {
            // grow the paragraph line by line, remembering the line after which the speaker pauses
            // the longest once the paragraph has its minimum length
            var end = start
            var chars = units[start].text.length
            var best = -1
            var bestPause = Long.MIN_VALUE

            while (end + 1 < units.size) {
                val next = units[end + 1]
                if (next.startsNewSpeaker) {
                    best = end
                    break
                }
                if (chars >= MIN_UNPUNCTUATED_CHARS) {
                    val pause = pauseAfter(units, end)
                    if (pause > bestPause) {
                        best = end
                        bestPause = pause
                    }
                }
                if (best >= 0 && chars + next.text.length + 1 > MAX_UNPUNCTUATED_CHARS) break

                end++
                chars += next.text.length + 1
            }

            // the last paragraph of a section takes all remaining lines
            val last = if (end + 1 >= units.size) units.size - 1 else best
            paragraphs.add(units.subList(start, last + 1))
            start = last + 1
        }

        return paragraphs
    }

    private fun buildParagraph(units: List<CaptionUnit>, capitalize: Boolean, isEnglish: Boolean): Paragraph {
        val text = StringBuilder()
        val segments = mutableListOf<Segment>()

        for ((index, unit) in units.withIndex()) {
            var unitText = unit.text
            if (capitalize) {
                if (isEnglish) unitText = unitText.replace(standaloneI, "I")
                if (index == 0) unitText = unitText.replaceFirstChar { it.uppercaseChar() }
            }

            if (text.isNotEmpty()) text.append(' ')
            val textStart = text.length
            text.append(unitText)
            segments.add(Segment(unit.startMs, textStart, text.length))
        }

        return Paragraph(text.toString(), segments)
    }
}
