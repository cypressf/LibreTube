package com.github.libretube

import com.github.libretube.obj.TranscriptLine
import com.github.libretube.util.ArticleFormatter
import com.github.libretube.util.ArticleFormatter.Chapter
import com.github.libretube.util.ArticleFormatter.Heading
import com.github.libretube.util.ArticleFormatter.Location
import com.github.libretube.util.ArticleFormatter.Paragraph
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticleFormatterTest {
    private fun line(seconds: Double, text: String) = TranscriptLine((seconds * 1000).toLong(), text)

    private fun paragraphs(article: ArticleFormatter.Article) =
        article.blocks.filterIsInstance<Paragraph>().map { it.text }

    @Test
    fun testShortPunctuatedTranscriptIsOneParagraph() {
        val article = ArticleFormatter.format(
            listOf(
                line(0.0, "Hello and welcome."),
                line(2.0, "Today we talk about rivers."),
                line(4.0, "Let's begin.")
            )
        )
        assertEquals(true, article.isPunctuated)
        assertEquals(
            listOf("Hello and welcome. Today we talk about rivers. Let's begin."),
            paragraphs(article)
        )
    }

    @Test
    fun testPunctuatedParagraphsEndAtSentenceAfterTargetLength() {
        // 12 sentences of ~60 chars each: paragraphs break at the first sentence end after
        // ~320 chars, never mid-sentence
        val sentence = "This sentence is about sixty characters long, roughly."
        val lines = (0 until 12).flatMap { i ->
            // every sentence is split over two caption lines
            listOf(
                line(i * 4.0, sentence.substringBefore(",") + ","),
                line(i * 4.0 + 2, sentence.substringAfter(", "))
            )
        }
        val article = ArticleFormatter.format(lines)
        val texts = paragraphs(article)

        assertEquals(true, texts.size >= 2)
        for (text in texts) {
            assertEquals(true, text.endsWith("roughly."))
            assertEquals(true, text.length <= 320 + sentence.length + 1)
        }
        assertEquals(12, texts.sumOf { it.split("roughly.").size - 1 })
    }

    @Test
    fun testLongPauseEndsPunctuatedParagraphEarly() {
        val article = ArticleFormatter.format(
            listOf(
                line(0.0, "The first idea is that rivers carve valleys over time."),
                line(4.0, "That takes a very long time, many thousands of years."),
                // 10 second pause before the next thought
                line(18.0, "The second idea is about deltas."),
            )
        )
        assertEquals(
            listOf(
                "The first idea is that rivers carve valleys over time. " +
                    "That takes a very long time, many thousands of years.",
                "The second idea is about deltas."
            ),
            paragraphs(article)
        )
    }

    @Test
    fun testChaptersBecomeHeadings() {
        val article = ArticleFormatter.format(
            listOf(
                line(0.5, "Welcome."),
                line(10.0, "Rivers are long."),
                // starts slightly before the chapter it belongs to
                line(59.6, "Deltas are flat."),
            ),
            chapters = listOf(
                Chapter("Intro", 0),
                Chapter("Rivers", 9_000),
                Chapter("Unused", 30_000),
                Chapter("Deltas", 60_000)
            )
        )
        val blocks = article.blocks.map {
            when (it) {
                is Heading -> "# ${it.title}"
                is Paragraph -> it.text
            }
        }
        // a chapter without any text of its own is dropped
        assertEquals(
            listOf("# Intro", "Welcome.", "# Rivers", "Rivers are long.", "# Deltas", "Deltas are flat."),
            blocks
        )
    }

    @Test
    fun testSoundTagsAndSpeakerChanges() {
        val article = ArticleFormatter.format(
            listOf(
                line(0.0, "[Music]"),
                line(2.0, "♪ So, where were we? ♪"),
                line(4.0, "(applause)"),
                line(6.0, "We were talking [laughs] about rivers."),
                line(8.0, ">> Yes, rivers."),
            )
        )
        assertEquals(
            listOf("So, where were we? We were talking about rivers.", "Yes, rivers."),
            paragraphs(article)
        )
    }

    @Test
    fun testUnpunctuatedCaptionsAreCapitalizedAndSplitAtLongestPause() {
        // 40 lines of 40 chars, 2.5s apart; one longer pause after line 12
        val lines = (0 until 40).map { i ->
            val extraPause = if (i > 12) 3.0 else 0.0
            line(i * 2.5 + extraPause, "i think rivers are interesting things ok")
        }
        val article = ArticleFormatter.format(lines, language = "en-US")
        val texts = paragraphs(article)

        assertEquals(false, article.isPunctuated)
        // the first paragraph ends at the long pause, after 13 lines
        assertEquals(13, texts.first().split(" ").size / 7)
        assertEquals(true, texts.all { it.startsWith("I think") })
        assertEquals(true, texts.none { it.contains(" i ") })
        assertEquals(40, texts.sumOf { it.split("rivers").size - 1 })
    }

    @Test
    fun testCaptionEllipsesBecomeProse() {
        // shaped like the professional captions of an MKBHD video
        val article = ArticleFormatter.format(
            listOf(
                line(0.0, "Just get a Chromebook. They're not going anywhere..."),
                line(2.0, "College. Okay, there's a little bit more nuance here."),
                line(4.0, "And so I think I can kind of average it out..."),
                line(6.0, "to somewhere in the B- range."),
                line(8.0, "But the second there's that like one app... that you need"),
                line(10.0, "that's not in the Play Store... then..."),
                line(12.0, "...that kind of makes this a non-option."),
                line(14.0, "Wait... I mean... What?"),
                line(16.0, "And that's it…")
            )
        )
        assertEquals(
            listOf(
                "Just get a Chromebook. They're not going anywhere. College. Okay, there's a " +
                    "little bit more nuance here. And so I think I can kind of average it out to " +
                    "somewhere in the B- range. But the second there's that like one app that you " +
                    "need that's not in the Play Store then that kind of makes this a non-option. " +
                    "Wait I mean. What?",
                // the paragraph is long enough to end at the next sentence end
                "And that's it."
            ),
            paragraphs(article)
        )
    }

    @Test
    fun testStandaloneIOnlyCapitalizedForEnglish() {
        val text = ArticleFormatter.format(
            listOf(line(0.0, "and i'm sure i said it")),
            language = "en"
        ).let(::paragraphs).single()
        assertEquals("And I'm sure I said it", text)

        // within unpunctuated captions, "i.e." and "Hawai'i" must stay as they are
        val tricky = ArticleFormatter.format(
            (0 until 10).map { line(it * 3.0, "we went to the coast last summer with friends") } +
                line(30.0, "so i.e. in hawai'i i think, i'd say i"),
            language = "en"
        ).let(::paragraphs).joinToString(" ")
        assertEquals(true, tricky.endsWith("so i.e. in hawai'i I think, I'd say I"))

        val italian = ArticleFormatter.format(
            listOf(line(0.0, "dove sono i fiumi")),
            language = "it"
        ).let(::paragraphs).single()
        assertEquals("Dove sono i fiumi", italian)
    }

    @Test
    fun testSegmentsAndLocate() {
        val article = ArticleFormatter.format(
            listOf(
                line(1.0, "First line."),
                line(3.0, "Second line."),
                line(5.0, "Third line.")
            ),
            chapters = listOf(Chapter("Start", 0))
        )
        val paragraph = article.blocks[1] as Paragraph
        assertEquals("First line. Second line. Third line.", paragraph.text)
        assertEquals("Second line.", paragraph.text.substring(
            paragraph.segments[1].textStart, paragraph.segments[1].textEnd
        ))

        assertEquals(0, paragraph.segmentAtOffset(0))
        assertEquals(0, paragraph.segmentAtOffset(11)) // the space after the first line
        assertEquals(1, paragraph.segmentAtOffset(12))
        assertEquals(2, paragraph.segmentAtOffset(paragraph.text.length - 1))

        assertEquals(null, article.locate(500))
        assertEquals(Location(1, 0), article.locate(1000))
        assertEquals(Location(1, 1), article.locate(4999))
        assertEquals(Location(1, 2), article.locate(99_000))
    }

    @Test
    fun testReadingTime() {
        val words = (1..1000).joinToString(" ") { "word" }
        val article = ArticleFormatter.format(listOf(line(0.0, "$words.")))
        assertEquals(1000, article.wordCount)
        assertEquals(4, article.readingTimeMinutes)
        assertEquals(1, ArticleFormatter.format(listOf(line(0.0, "Hi."))).readingTimeMinutes)
    }

    @Test
    fun testEmptyTranscript() {
        val article = ArticleFormatter.format(emptyList(), listOf(Chapter("Intro", 0)))
        assertEquals(emptyList<ArticleFormatter.Block>(), article.blocks)
        assertEquals(null, article.locate(1000))
    }
}
