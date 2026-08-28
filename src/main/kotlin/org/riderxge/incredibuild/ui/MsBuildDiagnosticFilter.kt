package org.riderxge.incredibuild.ui

import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.riderxge.incredibuild.build.MsBuildOutputParser
import java.nio.file.Path
import java.nio.file.Paths

/** Turns `file(line,col): error CXXXX: ...` occurrences into clickable links in the console. */
class MsBuildDiagnosticFilter(private val project: Project, private val baseDir: Path?) : Filter {

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        val diagnostic = MsBuildOutputParser.parseLine(line) ?: return null
        val file = diagnostic.file ?: return null
        val path = resolve(file) ?: return null
        val vf = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return null
        val idx = line.indexOf(file)
        if (idx < 0) return null
        val lineStart = entireLength - line.length
        val locEnd = line.indexOf(')', idx + file.length).let { if (it < 0) idx + file.length else it + 1 }
        val info = OpenFileHyperlinkInfo(project, vf, (diagnostic.line - 1).coerceAtLeast(0), (diagnostic.column - 1).coerceAtLeast(0))
        return Filter.Result(lineStart + idx, lineStart + locEnd, info)
    }

    private fun resolve(file: String): Path? {
        val raw = runCatching { Paths.get(file.trim()) }.getOrNull() ?: return null
        val abs = if (raw.isAbsolute) raw else baseDir?.resolve(raw) ?: return null
        return abs.normalize().takeIf { java.nio.file.Files.isRegularFile(it) }
    }
}
