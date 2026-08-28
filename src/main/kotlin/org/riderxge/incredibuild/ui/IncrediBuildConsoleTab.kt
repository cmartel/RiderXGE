package org.riderxge.incredibuild.ui

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.content.Content
import org.riderxge.incredibuild.build.BuildOutcome
import org.riderxge.incredibuild.build.Diagnostic
import org.riderxge.incredibuild.build.MsBuildOutputParser
import java.util.concurrent.atomic.AtomicInteger

/** One "build" tab in the IncrediBuild tool window: a console plus the diagnostics tally for that run. */
class IncrediBuildConsoleTab(val console: ConsoleView, val content: Content) : Disposable {
    private val errors = AtomicInteger()
    private val warnings = AtomicInteger()
    @Volatile
    var outcome: BuildOutcome? = null
        private set

    val errorCount: Int get() = errors.get()
    val warningCount: Int get() = warnings.get()
    val isRunning: Boolean get() = outcome == null

    init {
        Disposer.register(content, this)
    }

    fun attach(handler: ProcessHandler) {
        console.attachToProcess(handler)
    }

    /** When true every printed line is prefixed with a wall-clock timestamp. */
    @Volatile
    var timestamps: Boolean = false

    fun println(text: String, type: ConsoleViewContentType) {
        if (!timestamps) {
            console.print(text + "\n", type)
            return
        }
        for (line in text.split('\n')) console.print("${stamp()} $line\n", type)
    }

    /** Prints one line of BuildConsole output (already split into complete lines by the runner). */
    fun printOutput(line: String, stderr: Boolean) {
        println(line, if (stderr) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.NORMAL_OUTPUT)
    }

    private fun stamp(): String = "[" + java.time.LocalTime.now().format(TIME_FORMAT) + "]"

    /** Called for every complete output line (already printed by the attached process handler). */
    fun onLine(line: String) {
        val d = MsBuildOutputParser.parseLine(line) ?: return
        when (d.severity) {
            Diagnostic.Severity.ERROR -> errors.incrementAndGet()
            Diagnostic.Severity.WARNING -> warnings.incrementAndGet()
        }
    }

    fun markFinished(outcome: BuildOutcome) {
        this.outcome = outcome
        val suffix = when (outcome) {
            BuildOutcome.SUCCESS, BuildOutcome.SKIPPED -> "✓"
            BuildOutcome.FAILED -> "✗"
            BuildOutcome.CANCELED -> "–"
        }
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            if (!content.isValid) return@invokeLater
            content.displayName = "${content.displayName.trimEnd(' ', '✓', '✗', '–')} $suffix"
        }
    }

    override fun dispose() {}

    companion object {
        private val TIME_FORMAT: java.time.format.DateTimeFormatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
    }
}
