package com.hermes.agent.util

/**
 * Escapes user input for SQL `LIKE '%…%' ESCAPE '\'` patterns so `%` and `_`
 * in the query match literally instead of acting as wildcards (audit L1).
 * The paired DAO query must declare `ESCAPE '\'`.
 */
object SqlLike {
    fun escape(query: String): String =
        query.replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
}
