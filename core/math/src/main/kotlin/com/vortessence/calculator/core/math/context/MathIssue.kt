package com.vortessence.calculator.core.math.context

import com.vortessence.calculator.core.math.i18n.Language
import com.vortessence.calculator.core.math.i18n.Text
import com.vortessence.calculator.core.math.i18n.Texts

/**
 * Issues that may arise during evaluation, matching legacy MEIssue.
 */
enum class MathIssue(private val text: Text) {
    DIVISION_BY_ZERO(Text.DIVISION_BY_ZERO),
    NEGATIVE_ROOT(Text.NEGATIVE_ROOT),
    LOGARITHMIC_DOMAIN(Text.LOGARITHMIC_DOMAIN),
    TRIGONOMETRIC_DOMAIN(Text.TRIGONOMETRIC_DOMAIN),
    VALUES_TOO_LARGE(Text.VALUES_TOO_LARGE),
    UNDEFINED(Text.UNDEFINED),
    CONTRADICTION(Text.CONTRADICTION),
    NO_REAL_SOLUTION(Text.NO_REAL_SOLUTION),
    CANT_SOLVE_GENERIC(Text.CANT_SOLVE_GENERIC),
    INVALID_UNIT(Text.INVALID_UNIT),
    COMPLEX_RESULT(Text.COMPLEX_RESULT),
    SYNTAX_ERROR(Text.SYNTAX_ERROR),
    UNKNOWN_FUNCTION(Text.UNKNOWN_FUNCTION),
    CANT_DIFFERENTIATE(Text.CANT_DIFFERENTIATE),
    CANT_INTEGRATE(Text.CANT_INTEGRATE),
    TIMEOUT(Text.TIMEOUT),
    DIMENSION_MISMATCH(Text.DIMENSION_MISMATCH),
    MATRIX_NOT_SQUARE(Text.MATRIX_NOT_SQUARE),
    SINGULAR_MATRIX(Text.SINGULAR_MATRIX),
    MATRIX_UNSUPPORTED(Text.MATRIX_UNSUPPORTED),
    MATRIX_EXPECTED(Text.MATRIX_EXPECTED),
    MATRIX_NEEDS_NUMBERS(Text.MATRIX_NEEDS_NUMBERS),
    NO_EIGENVALUES(Text.NO_EIGENVALUES),
    EIGEN_ALONE(Text.EIGEN_ALONE),
    EIGENVALUES_NOT_EXACT(Text.EIGENVALUES_NOT_EXACT),
    MATRIX_AND_SCALAR(Text.MATRIX_AND_SCALAR),
    NO_ANS(Text.NO_ANS),
    DIVERGES(Text.DIVERGES),
    INVALID_BOUNDS(Text.INVALID_BOUNDS),
    ARGUMENT_COUNT(Text.ARGUMENT_COUNT),
    WHOLE_NUMBERS_ONLY(Text.WHOLE_NUMBERS_ONLY),
    VECTOR_EXPECTED(Text.VECTOR_EXPECTED),
    CROSS_NEEDS_THREE(Text.CROSS_NEEDS_THREE);

    /** What went wrong, in the language of the calculation that is running (English where none is). */
    val userMessage: String get() = Texts.get(text)

    /** What went wrong, said in [language]. */
    fun message(language: Language): String = Texts.get(text, language)
}
