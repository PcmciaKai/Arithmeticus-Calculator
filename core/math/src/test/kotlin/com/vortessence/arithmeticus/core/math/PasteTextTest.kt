package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.type.MathString
import com.vortessence.arithmeticus.core.math.type.MathText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Random

/** Math pasted as text: what the app copies reads back the same, what other apps write is understood, and words are no math. */
class PasteTextTest {
    private fun calc(math: MathString) = runBlocking { MathEngine.calculate(MathText.splitNumbers(math), MathContext(), 2000) }

    private fun valueOf(text: String): String {
        val math = MathText.fromPlainText(text) ?: return "(not math)"
        val result = calc(math)
        return if (result.isSuccess) MathText.toPlainText(result.result) else result.issues.toString()
    }

    @Test
    fun whatTheAppCopiesReadsBackTheSame() {
        // Inputs and results as the app writes them for the clipboard, read again: the same value
        val inputs = listOf(
            "[x+1;2]+sqrt(3)", "x^{n+1}", "[1;2]x", "2^{-1}", "#S[n=1,10]{n^2}", "I[0,1]{x^2}", "I{sin(x)}", "D{x^3}", "#D[2]{x^3}",
            "L[x->0]{sin(x)/x}", "M[1 2/3 4]", "5#u{km}->#u{mi}", "x^2-5x+6=0", "a:=2|a^2", "nCr(5,2)", "gamma(5)", "3!", "50%",
            "factor(x^2-1)", "sqrt(2)/3", "e^{#i*pi}"
        )
        for (notation in inputs) {
            val typed = TestInput.parse(notation)
            val copied = MathText.toPlainText(typed)
            val pasted = MathText.fromPlainText(copied)
            assertNotNull("$notation copied as \"$copied\" did not read back", pasted)
            val context = MathContext(complexNumbers = true)
            val original = runBlocking { MathEngine.calculate(typed, context) }
            val again = runBlocking { MathEngine.calculate(MathText.splitNumbers(pasted!!), context) }
            assertEquals(copied, MathText.toPlainText(original.result), MathText.toPlainText(again.result))
            // Results copied are pasted as well
            if (original.isSuccess && original.answer != null) {
                val result = MathText.toPlainText(original.answer)
                assertNotNull("result \"$result\" of $notation did not read back", MathText.fromPlainText(result))
            }
        }
    }

    @Test
    fun mathOfOtherApps() {
        assertEquals("3", valueOf("1+2"))
        assertEquals("6", valueOf("2·3"))
        assertEquals("6", valueOf("2 × 3"))
        assertEquals("3", valueOf("6÷2"))
        assertEquals("x^2", valueOf("x²"))
        assertEquals("sqrt(2)", valueOf("√2"))
        assertEquals("abs(x - 1)", valueOf("|x − 1|"))
        assertEquals("3", valueOf("log(8, 2)"))
        assertEquals("24", valueOf("Γ(5)"))
        assertEquals("10", valueOf("nCr(5, 2)"))
        assertEquals("9.81 m/s²", valueOf("9.81 m/s^2"))
        assertEquals("1/3125", valueOf("3.2E-4"))
        // A unit after a space, a variable without one, as the app writes them
        assertEquals("3 m", valueOf("3 m"))
        assertEquals("3m", valueOf("3m"))
        assertEquals("(250/9) m/s", valueOf("100 km/h"))
        // A number in another base in an expression
        assertEquals("256", valueOf("0xFF + 1"))
    }

    @Test
    fun wordsAreNoMath() {
        for (text in listOf("hello", "hello world", "sin", "", "   ", "(1+2", "1+2)", "[[1, 2], [3]]", "x != 2", "@", "😀", "Dear Sir")) {
            assertNull(text, MathText.fromPlainText(text))
        }
    }

    @Test
    fun aDecimalCommaWhereItIsSetUp() {
        val withComma = MathText.fromPlainText("1,5 + 1", decimalComma = true)!!
        assertEquals("5/2", MathText.toPlainText(calc(withComma).result))
        // The arguments of a function are separated by ";" then
        val mean = MathText.fromPlainText("mean(1,5; 2,5)", decimalComma = true)!!
        assertEquals("2", MathText.toPlainText(calc(mean).result))
    }

    @Test
    fun randomTextNeverBreaksTheReader() {
        val alphabet = "0123456789.,+-*/^()[]{}|!%=<>:;_ xyzeπ∞√²³·×÷−→∠°\n\tsincoslogabsqrtmodAnsintegralsumkmh"
        val random = Random(7)
        repeat(5000) {
            val text = (0 until 1 + random.nextInt(30)).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
            val math = MathText.fromPlainText(text) ?: return@repeat
            // What is read can be calculated (or not) without an exception
            calc(math)
        }
    }
}
