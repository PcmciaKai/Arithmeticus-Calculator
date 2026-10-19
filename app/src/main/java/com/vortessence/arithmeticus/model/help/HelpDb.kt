package com.vortessence.arithmeticus.model.help

import androidx.annotation.StringRes
import com.vortessence.arithmeticus.R
import com.vortessence.arithmeticus.core.math.type.MathNotation
import com.vortessence.arithmeticus.core.math.type.MathString

/** A part of a help page. The math of [Syntax] and [Example] is the same in every language and is not translated. */
sealed interface HelpBlock {
    data class Paragraph(@StringRes val text: Int) : HelpBlock
    data class Heading(@StringRes val text: Int) : HelpBlock
    /** A key as it is labelled on the keypad ([label], or [labelRes] where the label is translated) and what it does. */
    data class Key(val label: String?, @StringRes val labelRes: Int?, @StringRes val text: Int) : HelpBlock
    /** One way of writing a function, taylor(f, x, a, n), and what its parts are. A %s in [form] is the translated [word]. */
    data class Syntax(val form: String, @StringRes val text: Int, @StringRes val word: Int? = null) : HelpBlock
    /**
     * Something to type and what comes out, with an optional note on it. [input] and [result] are how it reads (and what the search
     * finds); [notation] is the same in [MathNotation], which the help shows as the line would show it and offers to try.
     */
    data class Example(val input: String, val result: String, @StringRes val note: Int? = null, val notation: String? = null) : HelpBlock {
        val math: MathString? get() = notation?.let(MathNotation::parse)
    }
}

enum class HelpSection(@StringRes val title: Int) {
    BASICS(R.string.help_section_basics),
    FUNCTIONS(R.string.help_section_functions)
}

data class HelpTopic(
    val id: String,
    val section: HelpSection,
    @StringRes val title: Int,
    @StringRes val summary: Int,
    val blocks: List<HelpBlock>
)

/**
 * The pages of the in-app help: how the app is used (the screen, the keypad and where each function is), and how the
 * functions are written, with examples. The examples are written with a decimal point and ", " between arguments; the
 * help shows them with the decimal mark of the settings.
 */
object HelpDb {
    private fun p(@StringRes text: Int) = HelpBlock.Paragraph(text)
    private fun h(@StringRes text: Int) = HelpBlock.Heading(text)
    private fun key(label: String, @StringRes text: Int) = HelpBlock.Key(label, null, text)
    private fun key(@StringRes label: Int, @StringRes text: Int) = HelpBlock.Key(null, label, text)
    private fun syntax(form: String, @StringRes text: Int, @StringRes word: Int? = null) = HelpBlock.Syntax(form, text, word)
    private fun ex(input: String, result: String, @StringRes note: Int? = null, notation: String? = null) =
        HelpBlock.Example(input, result, note, notation)

    fun topic(id: String): HelpTopic? = topics.firstOrNull { it.id == id }

    val topics = listOf(
        HelpTopic(
            "screen", HelpSection.BASICS, R.string.help_screen_title, R.string.help_screen_summary, listOf(
                p(R.string.help_screen_1),
                p(R.string.help_screen_2),
                p(R.string.help_screen_3),
                ex("2 + 3 × 4", "14", notation = "2+3*4"),
                p(R.string.help_screen_4)
            )
        ),
        HelpTopic(
            "keypad", HelpSection.BASICS, R.string.help_keypad_title, R.string.help_keypad_summary, listOf(
                p(R.string.help_keypad_1),
                h(R.string.help_keypad_main),
                key("0 … 9  .  ×10ⁿ", R.string.help_keypad_digits),
                key("+  −  ×  ÷", R.string.help_keypad_operators),
                key("( )", R.string.help_keypad_parentheses),
                key("⌫", R.string.help_keypad_backspace),
                key("↵", R.string.help_keypad_enter),
                h(R.string.help_keypad_left),
                key("!  □²  √□  log", R.string.help_keypad_row1),
                key("[⋮]  sin  cos  tan", R.string.help_keypad_row2),
                key("∫dx  Σ  i  π", R.string.help_keypad_row3),
                key("=  x  abc  e", R.string.help_keypad_row4),
                h(R.string.help_keypad_right),
                key("AC / C", R.string.help_keypad_clear),
                key(R.string.keypad_units, R.string.help_keypad_units),
                key(R.string.keypad_formulas_short, R.string.help_keypad_functions),
                key(R.string.keypad_constants_short, R.string.help_keypad_constants),
                key(R.string.keypad_graph, R.string.help_keypad_graph),
                key(R.string.keypad_settings, R.string.help_keypad_settings),
                h(R.string.help_keypad_top),
                key("RAD / DEG", R.string.help_keypad_angle),
                key("DEC ▾", R.string.help_keypad_base)
            )
        ),
        HelpTopic(
            "long_press", HelpSection.BASICS, R.string.help_long_press_title, R.string.help_long_press_summary, listOf(
                p(R.string.help_long_press_1),
                p(R.string.help_long_press_2),
                key("!", R.string.help_long_press_factorial),
                key("□²", R.string.help_long_press_square),
                key("√□", R.string.help_long_press_root),
                key("log", R.string.help_long_press_log),
                key("[⋮]", R.string.help_long_press_matrix),
                key("sin  cos  tan", R.string.help_long_press_trig),
                key("∫dx", R.string.help_long_press_integral),
                key("Σ", R.string.help_long_press_sum),
                key("i", R.string.help_long_press_i),
                key("π", R.string.help_long_press_pi),
                key("=", R.string.help_long_press_equals),
                key("x", R.string.help_long_press_x),
                key("+  −", R.string.help_long_press_plus_minus),
                key("÷", R.string.help_long_press_divide),
                key(".", R.string.help_long_press_point),
                key("↵", R.string.help_long_press_enter),
                key(R.string.keypad_units, R.string.help_long_press_units)
            )
        ),
        HelpTopic(
            "editing", HelpSection.BASICS, R.string.help_editing_title, R.string.help_editing_summary, listOf(
                p(R.string.help_editing_1),
                p(R.string.help_editing_2),
                p(R.string.help_editing_3),
                p(R.string.help_editing_4),
                p(R.string.help_editing_5)
            )
        ),
        HelpTopic(
            "history", HelpSection.BASICS, R.string.help_history_title, R.string.help_history_summary, listOf(
                p(R.string.help_history_1),
                p(R.string.help_history_2),
                p(R.string.help_history_3),
                p(R.string.help_history_4),
                h(R.string.help_history_sessions),
                p(R.string.help_history_5)
            )
        ),
        HelpTopic(
            "results", HelpSection.BASICS, R.string.help_results_title, R.string.help_results_summary, listOf(
                p(R.string.help_results_1),
                ex("1/3 + 1/4", "7/12", R.string.help_results_forms_example, "[1;3]+[1;4]"),
                p(R.string.help_results_2),
                p(R.string.help_results_3)
            )
        ),
        HelpTopic(
            "variables", HelpSection.BASICS, R.string.help_variables_title, R.string.help_variables_summary, listOf(
                p(R.string.help_variables_1),
                p(R.string.help_variables_2),
                h(R.string.help_variables_define),
                p(R.string.help_variables_3),
                ex("a := 5", "a = 5", notation = "a:=5"),
                ex("a² + 1", "26"),
                p(R.string.help_variables_4)
            )
        ),
        HelpTopic(
            "functions", HelpSection.FUNCTIONS, R.string.help_functions_title, R.string.help_functions_summary, listOf(
                p(R.string.help_functions_1),
                p(R.string.help_functions_2),
                ex("mean(2, 4, 9)", "5", notation = "mean(2,4,9)"),
                ex("nCr(5, 2)", "10", notation = "nCr(5,2)"),
                ex("gcd(12, 18)", "6", notation = "gcd(12,18)"),
                p(R.string.help_functions_3)
            )
        ),
        HelpTopic(
            "equations", HelpSection.FUNCTIONS, R.string.help_equations_title, R.string.help_equations_summary, listOf(
                p(R.string.help_equations_1),
                ex("2x + 3 = 7", "x = 2", notation = "2x+3=7"),
                ex("x² − 5x + 6 = 0", "x = 3, x = 2", notation = "x^2-5x+6=0"),
                h(R.string.help_equations_systems),
                p(R.string.help_equations_2),
                ex("−2x + y = −3\n3x + 2y = 8", "x = 2, y = 1", notation = "-2x+y=-3|3x+2y=8"),
                h(R.string.help_equations_inequalities),
                p(R.string.help_equations_3),
                ex("2x − 1 > 5", "x > 3", notation = "2x-1>5")
            )
        ),
        HelpTopic(
            "derivatives", HelpSection.FUNCTIONS, R.string.help_derivatives_title, R.string.help_derivatives_summary, listOf(
                p(R.string.help_derivatives_1),
                ex("d/dx(x³ + sin(x))", "3x² + cos(x)", notation = "D{x^3+sin(x)}"),
                p(R.string.help_derivatives_2),
                ex("d/dx(x²)|ₓ₌₃", "6", notation = "#D[3]{x^2}"),
                p(R.string.help_derivatives_3),
                ex("d/dx(x²·y)", "2x·y", R.string.help_derivatives_partial_note, "D{x^2y}")
            )
        ),
        HelpTopic(
            "integrals", HelpSection.FUNCTIONS, R.string.help_integrals_title, R.string.help_integrals_summary, listOf(
                p(R.string.help_integrals_1),
                ex("∫ x² dx", "x³/3 + C", notation = "I{x^2}"),
                p(R.string.help_integrals_2),
                ex("∫₀¹ x² dx", "1/3", notation = "I[0,1]{x^2}"),
                p(R.string.help_integrals_3)
            )
        ),
        HelpTopic(
            "limits", HelpSection.FUNCTIONS, R.string.help_limits_title, R.string.help_limits_summary, listOf(
                p(R.string.help_limits_1),
                ex("Σ (n = 1 … 100) n", "5050", notation = "#S[n=1,100]{n}"),
                ex("∏ (n = 1 … 5) n", "120", notation = "#P[n=1,5]{n}"),
                p(R.string.help_limits_lists),
                ex("sum(2, 4, 9)", "15", notation = "sum(2,4,9)"),
                ex("prod(2, 3, 4)", "24", notation = "prod(2,3,4)"),
                p(R.string.help_limits_2),
                ex("lim x→0 sin(x)/x", "1", notation = "L[x->0]{[sin(x);x]}"),
                ex("lim x→0⁺ 1/x", "∞", R.string.help_limits_side_note, "L[x->0^+]{[1;x]}"),
                ex("lim x→∞ (1 + 1/x)ˣ", "e", notation = "L[x->inf]{(1+[1;x])^x}")
            )
        ),
        HelpTopic(
            "taylor", HelpSection.FUNCTIONS, R.string.help_taylor_title, R.string.help_taylor_summary, listOf(
                p(R.string.help_taylor_1),
                h(R.string.help_heading_where),
                p(R.string.help_taylor_where),
                h(R.string.help_heading_syntax),
                syntax("taylor(f, n)", R.string.help_taylor_syntax_1),
                syntax("taylor(f, x, n)", R.string.help_taylor_syntax_2),
                syntax("taylor(f, x, a, n)", R.string.help_taylor_syntax_3),
                h(R.string.help_heading_examples),
                ex("taylor(sin(x), x, 0, 5)", "x − x³/6 + x⁵/120", notation = "taylor(sin(x),x,0,5)"),
                ex("taylor(eˣ, 3)", "1 + x + x²/2 + x³/6", notation = "taylor(e^x,3)"),
                ex("taylor(ln(x), x, 1, 3)", "(x − 1) − (x − 1)²/2 + (x − 1)³/3", R.string.help_taylor_example_point, "taylor(ln(x),x,1,3)"),
                ex("taylor(sin(x), x, π, 3)", "−(x − π) + (x − π)³/6", notation = "taylor(sin(x),x,pi,3)"),
                h(R.string.help_heading_good_to_know),
                p(R.string.help_taylor_notes_1),
                p(R.string.help_taylor_notes_2),
                p(R.string.help_taylor_notes_3)
            )
        ),
        HelpTopic(
            "dsolve", HelpSection.FUNCTIONS, R.string.help_dsolve_title, R.string.help_dsolve_summary, listOf(
                p(R.string.help_dsolve_1),
                h(R.string.help_heading_where),
                p(R.string.help_dsolve_where),
                p(R.string.help_dsolve_typing),
                h(R.string.help_heading_syntax),
                syntax("dsolve(%s)", R.string.help_dsolve_syntax_1, R.string.help_dsolve_equation),
                syntax("dsolve(%s, x₀, y₀)", R.string.help_dsolve_syntax_2, R.string.help_dsolve_equation),
                syntax("dsolve(%s, x₀, y₀, y′₀)", R.string.help_dsolve_syntax_3, R.string.help_dsolve_equation),
                h(R.string.help_dsolve_kinds),
                p(R.string.help_dsolve_kinds_1),
                p(R.string.help_dsolve_kinds_2),
                h(R.string.help_heading_examples),
                ex("dsolve(d/dx(y) = 2x·y)", "y = C·e^(x²)", R.string.help_dsolve_example_separable, "dsolve(D{y}=2xy)"),
                ex("dsolve(d/dx(y) + y = x)", "y = x − 1 + C·e^(−x)", R.string.help_dsolve_example_linear, "dsolve(D{y}+y=x)"),
                ex("dsolve(d/dx(y) = 2y, 0, 3)", "y = 3e^(2x)", R.string.help_dsolve_example_condition, "dsolve(D{y}=2y,0,3)"),
                ex("dsolve(d/dx(d/dx(y)) + y = 0)", "y = C₁·cos(x) + C₂·sin(x)", R.string.help_dsolve_example_second, "dsolve(D{D{y}}+y=0)"),
                ex("dsolve(d/dx(d/dx(y)) + y = 0, 0, 0, 1)", "y = sin(x)", R.string.help_dsolve_example_second_condition, "dsolve(D{D{y}}+y=0,0,0,1)"),
                ex("dsolve(d/dx(d/dx(y)) − 3·d/dx(y) + 2y = 0)", "y = C₁·e^(2x) + C₂·eˣ", notation = "dsolve(D{D{y}}-3D{y}+2y=0)"),
                h(R.string.help_heading_good_to_know),
                p(R.string.help_dsolve_notes_1),
                p(R.string.help_dsolve_notes_2)
            )
        ),
        HelpTopic(
            "matrices", HelpSection.FUNCTIONS, R.string.help_matrices_title, R.string.help_matrices_summary, listOf(
                p(R.string.help_matrices_1),
                p(R.string.help_matrices_2),
                ex("det([1 2; 3 4])", "−2", notation = "det(M[1 2/3 4])"),
                ex("[1 2; 3 4]⁻¹", "[−2 1; 3/2 −1/2]", notation = "M[1 2/3 4]^-1"),
                p(R.string.help_matrices_3),
                ex("cross([1; 0; 0], [0; 1; 0])", "[0; 0; 1]", notation = "cross(M[1/0/0],M[0/1/0])"),
                p(R.string.help_matrices_4)
            )
        ),
        HelpTopic(
            "complex", HelpSection.FUNCTIONS, R.string.help_complex_title, R.string.help_complex_summary, listOf(
                p(R.string.help_complex_1),
                ex("√−4", "2i", notation = "sqrt(-4)"),
                ex("(2 + 3i)(1 − i)", "5 + i", notation = "(2+3#i)(1-#i)"),
                p(R.string.help_complex_2)
            )
        ),
        HelpTopic(
            "units", HelpSection.FUNCTIONS, R.string.help_units_title, R.string.help_units_summary, listOf(
                p(R.string.help_units_1),
                p(R.string.help_units_2),
                ex("5 km → mi", "3.106855961 mi", notation = "5#u{km}->#u{mi}"),
                ex("3 m · 2 m", "6 m²", notation = "3#u{m}*2#u{m}"),
                p(R.string.help_units_3)
            )
        ),
        HelpTopic(
            "graph", HelpSection.FUNCTIONS, R.string.help_graph_title, R.string.help_graph_summary, listOf(
                p(R.string.help_graph_1),
                ex("x² − 2", "", R.string.help_graph_example_function, "x^2-2"),
                ex("x² + y² = 4", "", R.string.help_graph_example_implicit, "x^2+y^2=4"),
                ex("sin(x)·cos(y)", "", R.string.help_graph_example_surface, "sin(x)*cos(y)"),
                p(R.string.help_graph_2),
                p(R.string.help_graph_3),
                p(R.string.help_graph_4)
            )
        ),
        HelpTopic(
            "bases", HelpSection.FUNCTIONS, R.string.help_bases_title, R.string.help_bases_summary, listOf(
                p(R.string.help_bases_1),
                p(R.string.help_bases_2),
                ex("0xFF AND 0x0F", "15", R.string.help_bases_example_note)
            )
        )
    )
}
