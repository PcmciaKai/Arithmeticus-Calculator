package com.vortessence.calculator.ui.dialogs

import com.vortessence.calculator.core.math.steps.StepRule
import com.vortessence.calculator.core.math.type.*
import org.junit.Assert.*
import org.junit.Test

class RuleFormulaTest {
    @Test
    fun everyRuleFormulaCanBeRead() {
        for (rule in StepRule.entries) {
            val formula = rule.formula ?: continue
            assertTrue(rule.name, ruleFormula(formula).isNotEmpty)
        }
    }

    @Test
    fun exponentsFractionsAndRoots() {
        val formula = ruleFormula("x^{\\frac{1}{n}} = \\sqrt[n]{x}")
        val power = formula.nodeAt(0) as ExponentNode
        assertEquals("x", power.base.toString())
        assertTrue(power.exponent.nodeAt(0) is FractionNode)
        assertTrue(formula.nodeAt(2) is RadicalNode)
        // A single character after ^ is the exponent, the part before it the base
        assertEquals("n", (ruleFormula("x^n").nodeAt(0) as ExponentNode).exponent.toString())
        val powerOfPower = ruleFormula("(x^2)^3").nodeAt(0) as ExponentNode
        assertTrue(powerOfPower.base!!.nodeAt(0) is ParenthesesNode)
        assertEquals("3", powerOfPower.exponent.toString())
    }

    @Test
    fun reciprocalAndHyperbolicFunctionsAreFunctionsWithTheirFullName() {
        for (name in listOf("sec", "csc", "cot", "asec", "acsc", "acot", "sinh", "cosh", "tanh", "sech", "csch", "coth", "asinh", "acosh", "atanh", "asech", "acsch", "acoth")) {
            val node = ruleFormula("$name(x)").nodeAt(0)
            assertTrue(name, node is FunctionNode)
            assertEquals(name, (node as FunctionNode).name)
        }
    }

    @Test
    fun functionsIntegralsAndText() {
        assertTrue(ruleFormula("sin(x)").nodeAt(0) is FunctionNode)
        assertTrue(ruleFormula("\\int{x^n}{x}").nodeAt(0) is AntiderivativeNode)
        assertEquals("u", (ruleFormula("\\int{f}{u}").nodeAt(0) as AntiderivativeNode).variable)
        assertTrue((ruleFormula("e^x").nodeAt(0) as ExponentNode).base!!.nodeAt(0) is ConstantNode)
        assertTrue(ruleFormula("a\\text{ with }b").nodeAt(1) is TextNode)
    }
}
