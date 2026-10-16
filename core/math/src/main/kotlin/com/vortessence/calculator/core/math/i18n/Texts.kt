package com.vortessence.calculator.core.math.i18n

import com.vortessence.calculator.core.math.context.MathContext
import java.util.Locale

/**
 * Every text the math engine shows to the user, in each language. A text with parts that vary (a row number, a name) has them as
 * %s or %d: [Texts.format].
 */
enum class Text(val en: String, val de: String) {
    // ----- what went wrong -----
    DIVISION_BY_ZERO("Division by zero", "Division durch null"),
    NEGATIVE_ROOT("Even root of negative number", "Gerade Wurzel aus einer negativen Zahl"),
    LOGARITHMIC_DOMAIN("Logarithm argument/base out of domain", "Argument oder Basis des Logarithmus außerhalb des Definitionsbereichs"),
    TRIGONOMETRIC_DOMAIN("Argument out of domain", "Argument außerhalb des Definitionsbereichs"),
    VALUES_TOO_LARGE("Value too large to calculate", "Wert zu groß zum Berechnen"),
    UNDEFINED("Calculation undefined", "Berechnung nicht definiert"),
    CONTRADICTION("Contradiction (no solution)", "Widerspruch (keine Lösung)"),
    NO_REAL_SOLUTION("No real solution", "Keine reelle Lösung"),
    CANT_SOLVE_GENERIC("Cannot solve this equation", "Diese Gleichung kann nicht gelöst werden"),
    INVALID_UNIT("Incompatible or invalid units", "Einheiten passen nicht zusammen oder sind ungültig"),
    COMPLEX_RESULT("Complex numbers are switched off in the settings", "Komplexe Zahlen sind in den Einstellungen ausgeschaltet"),
    SYNTAX_ERROR("Incomplete or invalid expression", "Unvollständiger oder ungültiger Ausdruck"),
    UNKNOWN_FUNCTION("Unknown function", "Unbekannte Funktion"),
    CANT_DIFFERENTIATE("Cannot differentiate this expression", "Dieser Ausdruck kann nicht abgeleitet werden"),
    CANT_INTEGRATE("No closed form found for this integral", "Für dieses Integral wurde keine geschlossene Form gefunden"),
    TIMEOUT("Calculation timed out", "Zeitüberschreitung bei der Berechnung"),
    DIMENSION_MISMATCH("Matrix dimensions do not match", "Die Dimensionen der Matrizen passen nicht zusammen"),
    MATRIX_NOT_SQUARE("Matrix must be square", "Die Matrix muss quadratisch sein"),
    SINGULAR_MATRIX("Matrix is not invertible", "Die Matrix ist nicht invertierbar"),
    MATRIX_UNSUPPORTED("Not defined for matrices", "Für Matrizen nicht definiert"),
    MATRIX_EXPECTED("Needs a matrix", "Eine Matrix wird benötigt"),
    MATRIX_NEEDS_NUMBERS("Needs a matrix of numbers", "Eine Matrix aus Zahlen wird benötigt"),
    NO_EIGENVALUES("No real eigenvalues", "Keine reellen Eigenwerte"),
    EIGEN_ALONE("eig can only be used on its own", "eig kann nur allein verwendet werden"),
    EIGENVALUES_NOT_EXACT("The eigenvalues cannot be found exactly", "Die Eigenwerte lassen sich nicht exakt bestimmen"),
    MATRIX_AND_SCALAR("A matrix and a number cannot be added", "Eine Matrix und eine Zahl können nicht addiert werden"),
    NO_ANS("No previous result for Ans", "Kein vorheriges Ergebnis für Ans"),
    DIVERGES("Does not converge", "Konvergiert nicht"),
    INVALID_BOUNDS("Bounds must be whole numbers", "Die Grenzen müssen ganze Zahlen sein"),
    ARGUMENT_COUNT("Wrong number of values for this function", "Falsche Anzahl an Werten für diese Funktion"),
    WHOLE_NUMBERS_ONLY("Only defined for whole numbers", "Nur für ganze Zahlen definiert"),
    VECTOR_EXPECTED("Needs a vector (a matrix of one row or one column)", "Ein Vektor wird benötigt (eine Matrix mit einer Zeile oder Spalte)"),
    CROSS_NEEDS_THREE("The cross product needs vectors with 3 components", "Das Kreuzprodukt benötigt Vektoren mit 3 Komponenten"),
    CALCULATION_ERROR("Calculation Error", "Berechnungsfehler"),

    // ----- results and notes in the steps -----
    TRUE("true", "wahr"),
    FALSE("false", "falsch"),
    NO_SOLUTION("no solution", "keine Lösung"),
    NO_REAL_SOLUTION_TEXT("no real solution", "keine reelle Lösung"),
    NOT_DEFINED("not defined", "nicht definiert"),
    NOT_DEFINED_FAILS("not defined ✗", "nicht definiert ✗"),
    UNDEFINED_VALUE("undefined", "undefiniert"),
    NOT_A_SOLUTION("%s is not a solution", "%s ist keine Lösung"),
    EXPANDED_ALONG_ROW("expanded along row %d", "entwickelt nach Zeile %d"),
    EXPANDED_ALONG_COLUMN("expanded along column %d", "entwickelt nach Spalte %d"),
    RANK("rank = %d", "Rang = %d"),
    COMMON_DENOMINATOR("common denominator", "Hauptnenner"),
    DENOMINATOR("denominator", "Nenner"),
    NUMERATOR("numerator", "Zähler"),
    HIGHEST_POWERS("highest powers", "höchste Potenzen"),
    IS_POSITIVE_FOR("%s is positive for %s", "%s ist positiv für %s"),
    IS_NEGATIVE_FOR("%s is negative for %s", "%s ist negativ für %s"),
    GOES_AS("%s  as  %s", "%s  für  %s"),
    MATRIX_IS("%s is a matrix", "%s ist eine Matrix"),
    MATRICES_ARE("%s are matrices", "%s sind Matrizen"),
    AND(" and ", " und "),

    // ----- the words between the symbols of the formulas of the rules -----
    FORMULA_WITH("  with  ", "  mit  "),
    FORMULA_FOR("  for  ", "  für  "),
    FORMULA_OR("  or  ", "  oder  "),
    FORMULA_WHERE("  where  ", "  wobei  "),
    FORMULA_IS("  is  ", "  ist  "),
    FORMULA_AND("  (and  ", "  (und  "),
    FORMULA_UNTIL_IT_SETTLES("  until it settles", "  bis es sich einpendelt"),
    FORMULA_TO(" to ", " bis "),
    FORMULA_TOP("(top: ", "(oben: "),
    FORMULA_BOTTOM("; bottom: ", "; unten: "),
    FORMULA_ENTRY("entry", "Eintrag"),
    FORMULA_ROW_OF("row of ", "Zeile von "),
    FORMULA_COLUMN_OF("column of ", "Spalte von "),
    FORMULA_BY_PARTS_TWICE(":  by parts twice, then solve for I", ":  zweimal partiell integrieren, dann nach I auflösen")
}

object Texts {
    /** [text] in [language], the one of the context the engine is running in by default. */
    fun get(text: Text, language: Language = MathContext.current().language): String =
        if (language == Language.GERMAN) text.de else text.en

    fun format(text: Text, vararg arguments: Any?, language: Language = MathContext.current().language): String =
        String.format(Locale.ROOT, get(text, language), *arguments)
}
