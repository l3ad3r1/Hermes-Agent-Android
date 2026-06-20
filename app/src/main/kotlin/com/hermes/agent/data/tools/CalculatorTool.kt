package com.hermes.agent.data.tools

import com.hermes.agent.domain.tool.Tool
import com.hermes.agent.domain.tool.ToolDescriptor
import com.hermes.agent.domain.tool.ToolParameter
import com.hermes.agent.domain.tool.ToolParameterType
import com.hermes.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Arithmetic calculator. Phase 1 of the calculator evaluates a restricted
 * arithmetic expression by hand-rolling a tokenizer + shunting-yard
 * parser so we don't open up arbitrary code execution via `eval`-style
 * primitives. Phase 3 can swap in a more capable math engine (mXparser,
 * exp4j) if the LLM starts emitting trigonometric or statistical calls.
 *
 * Supported grammar:
 *   expr   := term (('+'|'-') term)*
 *   term   := factor (('*'|'/') factor)*
 *   factor := number | '(' expr ')' | '-' factor
 */
@Singleton
class CalculatorTool @Inject constructor() : Tool {

    override val descriptor = ToolDescriptor(
        name = "calculator",
        description = "Evaluate an arithmetic expression. Supports +, -, *, /, parentheses, " +
            "and decimal numbers. Use this whenever the user asks for a calculation rather than " +
            "estimating in your head.",
        parameters = listOf(
            ToolParameter(
                name = "expression",
                type = ToolParameterType.STRING,
                description = "The arithmetic expression to evaluate, e.g. '(2 + 3) * 4'.",
            ),
        ),
        category = "productivity",
    )

    override suspend fun execute(arguments: Map<String, JsonElement>): ToolResult {
        val start = System.currentTimeMillis()
        val expr = arguments["expression"]?.extractString()
            ?: return ToolResult.error("missing required parameter: expression")

        return runCatching { evaluate(expr) }
            .map { v ->
                ToolResult.ok(
                    output = formatNumber(v),
                    executionMs = System.currentTimeMillis() - start,
                )
            }
            .getOrElse { t ->
                ToolResult.error(
                    message = t.message ?: "could not evaluate expression",
                    executionMs = System.currentTimeMillis() - start,
                )
            }
    }

    private fun evaluate(input: String): Double {
        val tokens = tokenize(input)
        val parser = Parser(tokens)
        val result = parser.parseExpr()
        require(parser.eof()) { "unexpected trailing tokens" }
        return result
    }

    private fun tokenize(s: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c in "+-*/()" -> {
                    out.add(Token.Op(c.toString())); i++
                }
                c.isDigit() || c == '.' -> {
                    val j = (i + 1 until s.length).firstOrNull {
                        !(s[it].isDigit() || s[it] == '.')
                    } ?: s.length
                    out.add(Token.Num(s.substring(i, j).toDouble()))
                    i = j
                }
                else -> throw IllegalArgumentException("unexpected char '$c' at $i")
            }
        }
        return out
    }

    private sealed class Token {
        data class Num(val v: Double) : Token()
        data class Op(val s: String) : Token()
    }

    private class Parser(val tokens: List<Token>) {
        private var pos = 0
        fun eof() = pos >= tokens.size

        fun parseExpr(): Double {
            var lhs = parseTerm()
            while (peekOp() in setOf("+", "-")) {
                val op = nextOp(); val rhs = parseTerm()
                lhs = if (op == "+") lhs + rhs else lhs - rhs
            }
            return lhs
        }

        private fun parseTerm(): Double {
            var lhs = parseFactor()
            while (peekOp() in setOf("*", "/")) {
                val op = nextOp(); val rhs = parseFactor()
                lhs = if (op == "*") lhs * rhs else {
                    require(rhs != 0.0) { "division by zero" }
                    lhs / rhs
                }
            }
            return lhs
        }

        private fun parseFactor(): Double {
            if (peekOp() == "-") { nextOp(); return -parseFactor() }
            if (peekOp() == "(") {
                nextOp(); val v = parseExpr()
                require(peekOp() == ")") { "expected ')'" }
                nextOp(); return v
            }
            return when (val t = tokens.getOrNull(pos)) {
                is Token.Num -> { pos++; t.v }
                else -> throw IllegalArgumentException("expected number at $pos")
            }
        }

        private fun peekOp(): String? = (tokens.getOrNull(pos) as? Token.Op)?.s
        private fun nextOp(): String = (tokens[pos++] as Token.Op).s
    }

    private fun formatNumber(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()

    private fun JsonElement.extractString(): String? =
        (this as? JsonPrimitive)?.contentOrNull
}
