package com.vortessence.calculator.core.math.context

/**
 * Issues that may arise during evaluation, matching legacy MEIssue.
 */
enum class MathIssue(val userMessage: String) {
    DIVISION_BY_ZERO("Division by zero"),
    NEGATIVE_ROOT("Even root of negative number"),
    LOGARITHMIC_DOMAIN("Logarithm argument/base out of domain"),
    TRIGONOMETRIC_DOMAIN("Argument out of domain"),
    VALUES_TOO_LARGE("Value too large to calculate"),
    UNDEFINED("Calculation undefined"),
    CONTRADICTION("Contradiction (no solution)"),
    NO_REAL_SOLUTION("No real solution"),
    CANT_SOLVE_GENERIC("Cannot solve this equation"),
    INVALID_UNIT("Incompatible or invalid units"),
    COMPLEX_RESULT("Complex numbers are switched off in the settings"),
    SYNTAX_ERROR("Incomplete or invalid expression"),
    UNKNOWN_FUNCTION("Unknown function"),
    CANT_DIFFERENTIATE("Cannot differentiate this expression"),
    CANT_INTEGRATE("No closed form found for this integral"),
    TIMEOUT("Calculation timed out"),
    DIMENSION_MISMATCH("Matrix dimensions do not match"),
    MATRIX_NOT_SQUARE("Matrix must be square"),
    SINGULAR_MATRIX("Matrix is not invertible"),
    MATRIX_UNSUPPORTED("Not defined for matrices"),
    MATRIX_AND_SCALAR("A matrix and a number cannot be added"),
    NO_ANS("No previous result for Ans"),
    DIVERGES("Does not converge"),
    INVALID_BOUNDS("Bounds must be whole numbers"),
    ARGUMENT_COUNT("Wrong number of values for this function"),
    WHOLE_NUMBERS_ONLY("Only defined for whole numbers")
}
