package com.openide.jupyter.navigation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NavigationPositionsTest {
    @Test
    fun `converts utf16 offsets without splitting surrogate pairs`() {
        val text = "a😀b"

        assertEquals(0, NavigationPositions.utf16OffsetToCodePointOffset(text, 0))
        assertEquals(1, NavigationPositions.utf16OffsetToCodePointOffset(text, 1))
        assertNull(NavigationPositions.utf16OffsetToCodePointOffset(text, 2))
        assertEquals(2, NavigationPositions.utf16OffsetToCodePointOffset(text, 3))
        assertEquals(3, NavigationPositions.utf16OffsetToCodePointOffset(text, 4))
    }

    @Test
    fun `converts unicode columns back to editor columns`() {
        val text = "first\n😀value\n"

        assertEquals(0, NavigationPositions.codePointColumnToUtf16(text, 1, 0))
        assertEquals(2, NavigationPositions.codePointColumnToUtf16(text, 1, 1))
        assertEquals(7, NavigationPositions.codePointColumnToUtf16(text, 1, 6))
        assertNull(NavigationPositions.codePointColumnToUtf16(text, 4, 0))
    }
}
