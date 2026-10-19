package com.vortessence.arithmeticus.core.math

import com.vortessence.arithmeticus.core.math.context.MathContext
import com.vortessence.arithmeticus.core.math.expressions.Variable
import com.vortessence.arithmeticus.core.math.type.MathParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** A fraction with a fraction in its denominator is shown as one fraction: 1/(1/b − 2kt) as b/(1 − 2bkt). */
class InnerFractionsTest {

    private fun shown(text: String): String {
        val result = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }
        assertTrue("$text: ${result.issues}", result.isSuccess)
        return result.result.toString()
    }

    @Test
    fun theDenominatorIsCleared() {
        assertEquals("(b)/(1 − 2bkt)", shown("[1;[1;b]-2kt]"))
        assertEquals("(b)/(1 + 2bkt)", shown("[1;[1;b]+2kt]"))
        assertEquals("(x)/(1 − x)", shown("[1;[1;x]-1]"))
        assertEquals("(xy)/(y + x)", shown("[1;[1;x]+[1;y]]"))
        assertEquals("((x)^(2))/(1 + x)", shown("[1;[1;x^2]+[1;x]]"))
        // With a numerator, in a power, inside a sum
        assertEquals("(3b)/(1 − 2bkt)", shown("[3;[1;b]-2kt]"))
        assertEquals("((x)^(2))/(((1 − x))^(2))", shown("[1;[1;x]-1]^2"))
        assertEquals("(b)/(1 − 2bkt) + 1", shown("[1;[1;b]-2kt]+1"))
        // A number as the inner fraction: 1/(1/3 − 2kt), 1/(x/2 + 1), 1/(x + 1/2)
        assertEquals("(3)/(1 − 6kt)", shown("[1;[1;3]-2kt]"))
        assertEquals("(2)/(x + 2)", shown("[1;[x;2]+1]"))
        assertEquals("(2)/(2x + 1)", shown("[1;x+[1;2]]"))
    }

    @Test
    fun otherFractionsAreLeftAlone() {
        assertEquals("(1)/(1 − x)", shown("[1;1-x]"))
        assertEquals("(1)/(b) − 2kt", shown("[1;b]-2kt"))
        // Numbers only: calculated, not tidied
        assertEquals("(2)/(3)", shown("[1;1+[1;2]]"))
    }

    @Test
    fun theTidiedExpressionIsTheSameFunction() {
        val vars = listOf("b", "k", "t").map { Variable(it) }
        for (text in listOf("[1;[1;b]-2kt]", "[1;[1;b]+2kt]", "[3;[1;b]-2kt]", "[1;[1;k]+[1;t]]", "[1;[1;b^2]+[1;b]]", "[2;[1;b]-[1;t]+k]", "[1;[1;3]-2kt]", "[1;[x;2]+1]")) {
            val typed = MathParser().parse(TestInput.parse(text))
            val answer = runBlocking { MathEngine.calculate(TestInput.parse(text), MathContext()) }.expression
            for (point in listOf(mapOf(vars[0] to 0.7, vars[1] to 1.3, vars[2] to 0.4), mapOf(vars[0] to 2.1, vars[1] to 0.6, vars[2] to 1.9))) {
                assertEquals(text, typed.evaluateNumerically(point), answer.evaluateNumerically(point), 1e-9)
            }
        }
    }
}
