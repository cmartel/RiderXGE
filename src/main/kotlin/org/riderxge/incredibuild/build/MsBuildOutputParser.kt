package org.riderxge.incredibuild.build

/** A compiler/MSBuild diagnostic recognised in BuildConsole output. */
data class Diagnostic(
    val severity: Severity,
    val file: String?,
    /** 1-based, or 0 when unknown. */
    val line: Int,
    /** 1-based, or 0 when unknown. */
    val column: Int,
    val code: String?,
    val message: String,
) {
    enum class Severity { ERROR, WARNING }
}

/**
 * Recognises the canonical MSBuild diagnostic format
 * `file(line[,col]): [fatal ]error|warning CODE: message` (optionally prefixed by MSBuild's `N>` node id and by
 * IncrediBuild's agent decorations), plus location-less variants such as `error MSB1009: ...` and `LINK : fatal error LNK1104: ...`.
 */
object MsBuildOutputParser {
    // 1>c:\src\a.cpp(12,5): error C2065: 'x': undeclared identifier
    private val LOCATED = Regex(
        """^\s*(?:\d+>)?\s*(.+?)\((\d+)(?:,(\d+))?(?:,\d+,\d+)?\)\s*:\s*(fatal error|error|warning)\s+([A-Za-z]+\d+)?\s*:\s*(.*)$"""
    )

    // LINK : fatal error LNK1104: cannot open file 'x.lib'
    // MSBUILD : error MSB1009: Project file does not exist.
    // 3>Foo.cpp : error C1083: ...   (no location)
    private val UNLOCATED = Regex(
        """^\s*(?:\d+>)?\s*(?:([^:()]+?)\s*:\s*)?(fatal error|error|warning)\s+([A-Za-z]+\d+)\s*:\s*(.*)$"""
    )

    // "  0 Error(s)" / "2 Warning(s)" summary lines from MSBuild
    private val SUMMARY = Regex("""^\s*(\d+)\s+(Error|Warning)\(s\)\s*$""")

    fun parseLine(line: String): Diagnostic? {
        LOCATED.find(line)?.let { m ->
            val file = m.groupValues[1].trim()
            val severity = if (m.groupValues[4] == "warning") Diagnostic.Severity.WARNING else Diagnostic.Severity.ERROR
            return Diagnostic(
                severity = severity,
                file = file,
                line = m.groupValues[2].toIntOrNull() ?: 0,
                column = m.groupValues[3].toIntOrNull() ?: 0,
                code = m.groupValues[5].ifEmpty { null },
                message = m.groupValues[6].trim(),
            )
        }
        UNLOCATED.find(line)?.let { m ->
            val severity = if (m.groupValues[2] == "warning") Diagnostic.Severity.WARNING else Diagnostic.Severity.ERROR
            val origin = m.groupValues[1].trim().ifEmpty { null }
            val looksLikeFile = origin != null && (origin.contains('.') || origin.contains('\\') || origin.contains('/'))
            return Diagnostic(
                severity = severity,
                file = if (looksLikeFile) origin else null,
                line = 0,
                column = 0,
                code = m.groupValues[3],
                message = m.groupValues[4].trim(),
            )
        }
        return null
    }

    /** Returns `(count, isError)` for MSBuild's summary lines, or null. */
    fun parseSummary(line: String): Pair<Int, Boolean>? {
        val m = SUMMARY.find(line) ?: return null
        return (m.groupValues[1].toIntOrNull() ?: return null) to (m.groupValues[2] == "Error")
    }
}
