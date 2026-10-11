package com.github.libretube

import com.github.libretube.obj.TranscriptLine
import com.github.libretube.util.TranscriptMerger
import com.github.libretube.util.TtmlTranscriptParser
import org.junit.Assert.assertEquals
import org.junit.Test

class TtmlTranscriptParserTest {
    private fun parse(xml: String) =
        TranscriptMerger.merge(TtmlTranscriptParser.parse(xml.toByteArray()))

    @Test
    fun testYouTubeStyleDocument() {
        // shaped like the captions YouTube returns for fmt=ttml
        val xml = """<?xml version="1.0" encoding="utf-8" ?>
            <tt xml:lang="en" xmlns="http://www.w3.org/ns/ttml" xmlns:tts="http://www.w3.org/ns/ttml#styling">
            <head><styling><style xml:id="s1" tts:textAlign="center"/></styling></head>
            <body style="s1"><div>
            <p begin="00:00:00.160" end="00:00:04.160" style="s2">Rivers don&#39;t flow<br />in straight lines.</p>
            <p begin="00:00:04.160" end="00:00:07.000"><span>Even on a </span><span>flat plain.</span></p>
            <p begin="01:02:03.500" end="01:02:05.000">&amp; much later</p>
            </div></body></tt>"""

        assertEquals(
            listOf(
                TranscriptLine(160, "Rivers don't flow in straight lines."),
                TranscriptLine(4160, "Even on a flat plain."),
                TranscriptLine(3_723_500, "& much later")
            ),
            parse(xml)
        )
    }

    @Test
    fun testTimeFormats() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml" xmlns:ttp="http://www.w3.org/ns/ttml#parameter"
            ttp:frameRate="25" ttp:tickRate="10000000"><body><div>
            <p begin="1.5s" end="2s">a</p>
            <p begin="2500ms" dur="1s">b</p>
            <p begin="00:00:04:05">c</p>
            <p begin="60000000t">d</p>
            <p begin="0.5m">e</p>
            </div></body></tt>"""

        assertEquals(
            listOf(1500L, 2500L, 4200L, 6000L, 30_000L),
            parse(xml).map { it.startMs }
        )
    }

    @Test
    fun testEmptyParagraphsAreSkipped() {
        val xml = """<tt xmlns="http://www.w3.org/ns/ttml"><body><div>
            <p begin="00:00:01.000" end="00:00:02.000"></p>
            <p begin="00:00:02.000" end="00:00:03.000">  </p>
            <p begin="00:00:03.000" end="00:00:04.000">text</p>
            </div></body></tt>"""
        assertEquals(listOf(TranscriptLine(3000, "text")), parse(xml))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testNotTtml() {
        TtmlTranscriptParser.parse("WEBVTT\n\n00:00.000 --> 00:01.000\nhi".toByteArray())
    }

    @Test(expected = IllegalArgumentException::class)
    fun testUnsupportedTime() {
        TtmlTranscriptParser.parse(
            """<tt xmlns="http://www.w3.org/ns/ttml"><body><p begin="soon">a</p></body></tt>"""
                .toByteArray()
        )
    }

    @Test
    fun testLongTranscriptIsFast() {
        // a two hour video with a caption every 2.5 seconds
        val paragraphs = (0 until 2880).joinToString("\n") { i ->
            val start = i * 2500L
            "<p begin=\"${clock(start)}\" end=\"${clock(start + 2500)}\">" +
                "this is caption number $i of a long essay</p>"
        }
        val xml = "<tt xmlns=\"http://www.w3.org/ns/ttml\"><body><div>$paragraphs</div></body></tt>"

        val startedAt = System.nanoTime()
        val lines = parse(xml)
        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

        assertEquals(2880, lines.size)
        println("parsed 2880 captions in $elapsedMs ms")
        // generous, this is about catching quadratic behavior (seconds), not micro timing
        assertEquals(true, elapsedMs < 3000)
    }

    private fun clock(ms: Long) = "%02d:%02d:%02d.%03d".format(
        ms / 3_600_000, ms / 60_000 % 60, ms / 1000 % 60, ms % 1000
    )
}
