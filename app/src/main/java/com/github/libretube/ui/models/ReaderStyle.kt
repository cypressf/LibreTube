package com.github.libretube.ui.models

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import com.github.libretube.R
import com.github.libretube.constants.PreferenceKeys
import com.github.libretube.helpers.PreferenceHelper

/**
 * How reader view shows text, as chosen in the settings.
 */
data class ReaderStyle(
    val typeface: Typeface,
    val textSizeSp: Float
) {
    val titleSizeSp get() = textSizeSp * 1.45f
    val headingSizeSp get() = textSizeSp * 1.17f
    val metaSizeSp get() = textSizeSp * 0.8f

    companion object {
        const val FONT_SERIF = "serif"
        const val FONT_SANS = "sans"
        const val FONT_HYPERLEGIBLE = "hyperlegible"
        const val FONT_MONO = "mono"

        private const val DEFAULT_TEXT_SIZE = 18f

        fun fromPreferences(context: Context): ReaderStyle {
            val font = PreferenceHelper.getString(PreferenceKeys.READER_FONT, FONT_SERIF)
            val typeface = when (font) {
                FONT_SANS -> Typeface.SANS_SERIF
                FONT_MONO -> Typeface.MONOSPACE
                FONT_HYPERLEGIBLE -> runCatching {
                    ResourcesCompat.getFont(context, R.font.atkinson_hyperlegible)
                }.getOrNull() ?: Typeface.SANS_SERIF

                else -> Typeface.SERIF
            }

            val textSize = PreferenceHelper.getString(PreferenceKeys.READER_TEXT_SIZE, "")
                .toFloatOrNull() ?: DEFAULT_TEXT_SIZE

            return ReaderStyle(typeface, textSize)
        }
    }
}
