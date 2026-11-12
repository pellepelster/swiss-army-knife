package io.pelle.sak.backend

import java.text.BreakIterator

// the longest emoji in utf-16, e.g. a flag of a region or a family joined of several
const val MAX_ICON_LENGTH = 32

private val REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

private const val KEYCAP = '\u20E3'

// one emoji, the icon of a list (grocery, todo): a single grapheme (the character iterator of the
// jdk counts the extended grapheme clusters) that starts with a pictograph, a pair of regional
// indicators (a flag) or a keycap
fun isOneEmoji(text: String): Boolean {
    if (text.isEmpty() || text.length > MAX_ICON_LENGTH) return false
    val graphemes = BreakIterator.getCharacterInstance().apply { setText(text) }
    if (graphemes.next() != text.length) return false
    val first = text.codePointAt(0)
    return Character.isExtendedPictographic(first) || first in REGIONAL_INDICATORS || text.contains(KEYCAP)
}
