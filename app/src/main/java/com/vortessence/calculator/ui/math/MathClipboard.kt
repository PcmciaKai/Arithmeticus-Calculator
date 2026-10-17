package com.vortessence.calculator.ui.math

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.vortessence.calculator.core.math.type.MathString
import com.vortessence.calculator.core.math.type.MathText

/**
 * The system clipboard for math. Copied math goes there as plain text (see [MathText]), so it
 * can be pasted into any app. The app also remembers the copied structure itself: pasting it
 * back here restores fractions, matrices, integrals, ... exactly, as long as the clipboard
 * still holds that text.
 */
class MathClipboard(private val clipboard: ClipboardManager) {

    fun copy(math: MathString) {
        val text = MathText.toPlainText(math)
        lastCopy = text to math.copy()
        clipboard.setPrimaryClip(ClipData.newPlainText("Math", text))
    }

    /** Whether the clipboard holds text; checked without reading it (which Android announces). */
    fun hasText(): Boolean =
        clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType("text/*") == true

    /** The clipboard's content as math, or null if it is empty or cannot be read as math. */
    fun paste(): PasteContent? {
        val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: return null
        lastCopy?.let { (copiedText, math) -> if (copiedText == text) return PasteContent(text, math.copy()) }
        return PasteContent(text, MathText.fromPlainText(text))
    }

    /** Text from the clipboard and the math read from it ([math] is null if it could not be read). */
    class PasteContent(val text: String, val math: MathString?)

    private companion object {
        /** The most recently copied math with its text; shared, so it outlives the screen. */
        var lastCopy: Pair<String, MathString>? = null
    }
}

@Composable
fun rememberMathClipboard(): MathClipboard {
    val context = LocalContext.current
    return remember(context) { MathClipboard(context.getSystemService(ClipboardManager::class.java)) }
}
