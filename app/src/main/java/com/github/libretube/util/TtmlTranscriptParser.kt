package com.github.libretube.util

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import javax.xml.parsers.SAXParserFactory

/**
 * A minimal, single-pass parser for TTML subtitles, which is the format YouTube's captions use.
 *
 * Media3's TTML parser is made for rendering: it looks up the visible cues for every point in
 * time by walking the whole document, which takes seconds for the long transcripts of essay
 * videos. A transcript only needs the text and start time of each paragraph, so this reads the
 * document once.
 *
 * Only uses javax.xml, so it works on Android and in JVM unit tests alike.
 */
object TtmlTranscriptParser {
    // the frame rate to assume for "hh:mm:ss:frames" times if the document doesn't specify one
    private const val DEFAULT_FRAME_RATE = 30.0

    private val clockTime = Regex("^(\\d+):(\\d{2}):(\\d{2})(?:(\\.\\d+)|:(\\d+(?:\\.\\d+)?))?$")
    private val offsetTime = Regex("^(\\d+(?:\\.\\d+)?)(h|m|s|ms|f|t)$")

    /**
     * Parse the paragraphs of a TTML document into time slices.
     *
     * @throws IllegalArgumentException if the document isn't valid TTML or contains times that
     * can't be parsed, so that the caller can fall back to another parser
     */
    fun parse(data: ByteArray): List<TranscriptMerger.CueSlice> {
        val handler = Handler()
        val factory = SAXParserFactory.newInstance().apply { isNamespaceAware = true }
        // don't resolve external entities, the document comes from the network
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching {
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
        }

        try {
            factory.newSAXParser().parse(InputSource(ByteArrayInputStream(data)), handler)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid TTML document", e)
        }

        if (!handler.foundTtRoot) throw IllegalArgumentException("Not a TTML document")
        return handler.slices
    }

    private class Handler : DefaultHandler() {
        val slices = mutableListOf<TranscriptMerger.CueSlice>()
        var foundTtRoot = false

        private var frameRate = DEFAULT_FRAME_RATE
        private var tickRate = 1.0

        // the paragraph being read, null while outside of a <p> element
        private var text: StringBuilder? = null
        private var startMs = 0L
        private var endMs: Long? = null
        private var depth = 0

        override fun startElement(
            uri: String?,
            localName: String?,
            qName: String?,
            attributes: Attributes
        ) {
            val name = elementName(localName, qName)

            if (name == "tt") {
                foundTtRoot = true
                attribute(attributes, "frameRate")?.toDoubleOrNull()?.let { frameRate = it }
                attribute(attributes, "tickRate")?.toDoubleOrNull()?.let { tickRate = it }
                return
            }

            val paragraph = text
            if (paragraph != null) {
                depth++
                if (name == "br") paragraph.append('\n')
                return
            }

            if (name == "p") {
                val begin = attribute(attributes, "begin")
                    ?: throw IllegalArgumentException("Paragraph without begin time")
                startMs = parseTimeMs(begin)
                val end = attribute(attributes, "end")
                val duration = attribute(attributes, "dur")
                endMs = when {
                    end != null -> parseTimeMs(end)
                    duration != null -> startMs + parseTimeMs(duration)
                    else -> null
                }
                text = StringBuilder()
                depth = 0
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val paragraph = text ?: return
            if (depth > 0) {
                depth--
                return
            }

            // the end of the paragraph
            text = null
            val content = paragraph.toString().trim()
            if (content.isNotEmpty()) {
                slices.add(TranscriptMerger.CueSlice(startMs, endMs, listOf(content)))
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text?.append(ch, start, length)
        }

        private fun elementName(localName: String?, qName: String?) =
            localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')

        /**
         * Get an attribute by its local name, regardless of its namespace prefix.
         */
        private fun attribute(attributes: Attributes, name: String): String? {
            for (i in 0 until attributes.length) {
                val attributeName = attributes.getLocalName(i)?.takeIf { it.isNotEmpty() }
                    ?: attributes.getQName(i).substringAfter(':')
                if (attributeName == name) return attributes.getValue(i)
            }
            return null
        }

        private fun parseTimeMs(time: String): Long {
            val value = time.trim()

            clockTime.matchEntire(value)?.let { match ->
                val (hours, minutes, seconds) = match.destructured
                var totalSeconds =
                    (hours.toLong() * 3600 + minutes.toLong() * 60 + seconds.toLong()).toDouble()
                match.groups[4]?.value?.let { fraction -> totalSeconds += fraction.toDouble() }
                match.groups[5]?.value?.let { frames -> totalSeconds += frames.toDouble() / frameRate }
                return (totalSeconds * 1000).toLong()
            }

            offsetTime.matchEntire(value)?.let { match ->
                val (amount, unit) = match.destructured
                val number = amount.toDouble()
                val milliseconds = when (unit) {
                    "h" -> number * 3_600_000
                    "m" -> number * 60_000
                    "s" -> number * 1000
                    "ms" -> number
                    "f" -> number / frameRate * 1000
                    else -> number / tickRate * 1000
                }
                return milliseconds.toLong()
            }

            throw IllegalArgumentException("Unsupported TTML time: $time")
        }
    }
}
