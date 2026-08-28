package org.riderxge.incredibuild.build

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.jetbrains.rider.build.BuildHost
import com.jetbrains.rider.model.BuildResultKind
import com.jetbrains.rider.model.BuildTarget
import com.jetbrains.rider.model.BuildTargetBase
import com.jetbrains.rider.model.CleanTarget
import com.jetbrains.rider.model.RebuildTarget
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import com.jetbrains.rider.projectView.hasSolution
import com.jetbrains.rider.projectView.solutionFile
import org.riderxge.incredibuild.ib.BuildOperation
import org.riderxge.incredibuild.ib.IncrediBuildCommandLine
import org.riderxge.incredibuild.ib.IncrediBuildLocator
import org.riderxge.incredibuild.settings.DispatchMode
import org.riderxge.incredibuild.settings.IncrediBuildConfigurable
import org.riderxge.incredibuild.settings.IncrediBuildSettings
import org.riderxge.incredibuild.sln.ProjectGraph
import org.riderxge.incredibuild.sln.SolutionConfiguration
import org.riderxge.incredibuild.sln.SolutionModel
import org.riderxge.incredibuild.sln.SolutionParser
import org.riderxge.incredibuild.sln.SolutionProject
import org.riderxge.incredibuild.ui.IncrediBuildConsoleTab
import org.riderxge.incredibuild.ui.IncrediBuildToolWindow
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Executes build requests: runs `BuildConsole.exe` for the IncrediBuild part and, in hybrid mode, hands the
 * managed projects over to Rider's own build pipeline afterwards.
 */
@Service(Service.Level.PROJECT)
class IncrediBuildRunner(private val project: Project) : Disposable {

    private class Running(val handler: KillableColoredProcessHandler, val tab: IncrediBuildConsoleTab, val indicator: ProgressIndicator?) {
        val canceled = AtomicBoolean(false)
    }

    private val running = AtomicReference<Running?>(null)
    private val phaseRunning = AtomicBoolean(false)

    val isBuilding: Boolean get() = phaseRunning.get()

    /** Starts the build described by [request]. The returned future completes with the overall outcome. */
    fun build(request: IncrediBuildRequest): CompletableFuture<BuildOutcome> {
        val result = CompletableFuture<BuildOutcome>()
        if (!phaseRunning.compareAndSet(false, true)) {
            notify("An IncrediBuild build is already running.", NotificationType.WARNING)
            result.complete(BuildOutcome.FAILED)
            return result
        }
        result.whenComplete { _, _ -> phaseRunning.set(false) }

        val title = "IncrediBuild: ${request.operation.label} ${if (request.isWholeSolution) "Solution" else request.projectPaths.joinToString { it.fileName.toString().substringBeforeLast('.') }}"
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    runPhases(request, indicator, result)
                } catch (t: Throwable) {
                    LOG.warn("IncrediBuild build failed", t)
                    notify("IncrediBuild build failed: ${t.message}", NotificationType.ERROR)
                    result.complete(BuildOutcome.FAILED)
                }
            }

            override fun onCancel() {
                cancel()
            }
        })
        return result
    }

    /** Stops the running BuildConsole process (gracefully via `/STOP`, then by force). */
    fun cancel() {
        val r = running.get() ?: return
        if (!r.canceled.compareAndSet(false, true)) return
        r.tab.println("\n[IncrediBuild] Cancel requested – stopping build...", ConsoleViewContentType.SYSTEM_OUTPUT)
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val exe = IncrediBuildLocator.buildConsole()
                if (exe != null) {
                    val stop = GeneralCommandLine(exe.toString(), "/STOP", "/NOLOGO")
                    CapturingProcessHandler(stop).runProcess(15_000)
                }
            } catch (t: Throwable) {
                LOG.warn("BuildConsole /STOP failed", t)
            }
            if (!r.handler.waitFor(15_000)) {
                r.handler.destroyProcess()
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------

    private fun runPhases(request: IncrediBuildRequest, indicator: ProgressIndicator, result: CompletableFuture<BuildOutcome>) {
        val settings = IncrediBuildSettings.getInstance().state
        val exe = IncrediBuildLocator.buildConsole()
        if (exe == null) {
            notifyMissingBuildConsole()
            result.complete(BuildOutcome.FAILED)
            return
        }
        if (!project.hasSolution) {
            notify("No solution is open.", NotificationType.WARNING)
            result.complete(BuildOutcome.FAILED)
            return
        }
        val slnFile = project.solutionFile.toPath().toAbsolutePath().normalize()
        if (!Files.isRegularFile(slnFile) || !(slnFile.toString().endsWith(".sln", true) || slnFile.toString().endsWith(".slnx", true))) {
            notify("IncrediBuild needs a .sln/.slnx solution; '${slnFile.fileName}' is not one.", NotificationType.WARNING)
            result.complete(BuildOutcome.FAILED)
            return
        }
        val solution = SolutionParser.parse(slnFile)
        val cfg = activeConfiguration(solution)
        val mode = request.modeOverride ?: settings.dispatchMode
        val activate = request.activateWindow && settings.activateToolWindow

        val roots: List<SolutionProject> = if (request.isWholeSolution) solution.projects else request.projectPaths.mapNotNull { p ->
            solution.findByPath(p).also { if (it == null) LOG.warn("Project $p is not part of ${solution.file}") }
        }
        if (!request.isWholeSolution && roots.isEmpty()) {
            notify("None of the selected projects belong to ${solution.file.fileName}.", NotificationType.WARNING)
            result.complete(BuildOutcome.FAILED)
            return
        }

        val tab = IncrediBuildToolWindow.createTab(project, "${request.operation.label} ${if (request.isWholeSolution) solution.file.fileName.toString().substringBeforeLast('.') else roots.joinToString { it.name }}", activate)
        tab.timestamps = settings.timestampOutput
        val buildStart = System.currentTimeMillis()
        tab.println("IncrediBuild ${IncrediBuildLocator.versionText() ?: ""} – ${request.operation.label} ($cfg) – started ${now()}", ConsoleViewContentType.SYSTEM_OUTPUT)

        val cmd = IncrediBuildCommandLine(exe, settings)
        val clrRoots = HashSet<Path>()
        val ibOutcome: BuildOutcome = when (mode) {
            DispatchMode.FULL -> {
                val names = if (request.isWholeSolution) emptyList() else roots.map { it.name }
                tab.println("Mode: entire build through BuildConsole${if (names.isEmpty()) "" else " for ${names.joinToString()}"}", ConsoleViewContentType.SYSTEM_OUTPUT)
                timed(tab, "IncrediBuild phase") { runBuildConsole(cmd, solution, cfg, request, names, tab, indicator) }
            }
            DispatchMode.HYBRID -> {
                val graph = ProjectGraph.build(solution)
                val closure = if (request.withoutDependencies) roots.filter { it.isCpp } else graph.cppProjectsFor(roots)
                val clrKept = if (settings.dispatchClrProjects) emptyList() else closure.filter { graph.isClr(it) }
                clrRoots.addAll(clrKept.map { it.path })
                val cpp = closure.filter { solution.isBuilt(it, cfg) && it !in clrKept }
                if (clrKept.isNotEmpty()) {
                    tab.println("C++/CLI project(s) left to the Rider phase (see settings): ${clrKept.joinToString { it.name }}", ConsoleViewContentType.SYSTEM_OUTPUT)
                }
                graph.danglingReferences.forEach { (from, refs) ->
                    tab.println("Note: ${from.fileName} references project(s) outside the solution: ${refs.joinToString { it.fileName.toString() }}", ConsoleViewContentType.LOG_WARNING_OUTPUT)
                }
                if (settings.explainDependencies) explainDependencies(tab, graph, roots, closure, cfg, request.withoutDependencies)
                if (cpp.isEmpty()) {
                    tab.println("Mode: hybrid – no C++ projects among the targets or their dependencies; nothing to dispatch to IncrediBuild.", ConsoleViewContentType.SYSTEM_OUTPUT)
                    BuildOutcome.SKIPPED
                } else {
                    tab.println("Mode: hybrid – dispatching C++ project(s) to IncrediBuild: ${cpp.joinToString { it.name }}", ConsoleViewContentType.SYSTEM_OUTPUT)
                    timed(tab, "IncrediBuild phase") { runBuildConsole(cmd, solution, cfg, request, cpp.map { it.name }, tab, indicator) }
                }
            }
        }

        if (!ibOutcome.isSuccess) {
            finish(tab, ibOutcome, result)
            return
        }
        if (mode == DispatchMode.FULL || request.cppOnly) {
            finish(tab, ibOutcome, result)
            return
        }

        // Hybrid: hand the managed projects (and anything else) over to Rider's build.
        val managedRoots = if (request.isWholeSolution) emptyList() else roots.filter { !it.isCpp || (!settings.dispatchClrProjects && clrRoots.contains(it.path)) }
        if (!request.isWholeSolution && managedRoots.isEmpty()) {
            finish(tab, ibOutcome, result)
            return
        }
        tab.println("\nHanding over to Rider build: ${if (managedRoots.isEmpty()) "whole solution" else managedRoots.joinToString { it.name }} (C++ outputs are up to date)", ConsoleViewContentType.SYSTEM_OUTPUT)
        indicator.text = "Rider build: ${if (managedRoots.isEmpty()) "solution" else managedRoots.joinToString { it.name }}"
        val riderOutcome = timed(tab, "Rider phase") {
            try {
                runRiderBuild(request, managedRoots.map { it.path }, settings.riderDiagnosticsBuild).get()
            } catch (t: Throwable) {
                LOG.warn("Rider build failed", t)
                BuildOutcome.FAILED
            }
        }
        tab.println("Total: ${formatDuration(System.currentTimeMillis() - buildStart)}", ConsoleViewContentType.SYSTEM_OUTPUT)
        finish(tab, riderOutcome, result)
    }

    private fun now(): String = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return if (s >= 60) "${s / 60} min ${s % 60} s" else "$s s ($ms ms)"
    }

    private inline fun <T> timed(tab: IncrediBuildConsoleTab, phase: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        tab.println("--- $phase started ${now()}", ConsoleViewContentType.SYSTEM_OUTPUT)
        val r = block()
        tab.println("--- $phase finished ${now()} after ${formatDuration(System.currentTimeMillis() - start)}", ConsoleViewContentType.SYSTEM_OUTPUT)
        return r
    }

    private fun explainDependencies(
        tab: IncrediBuildConsoleTab,
        graph: ProjectGraph,
        roots: List<SolutionProject>,
        closure: List<SolutionProject>,
        cfg: SolutionConfiguration,
        withoutDependencies: Boolean,
    ) {
        val t = ConsoleViewContentType.LOG_INFO_OUTPUT
        tab.println("Dependency resolution (${roots.size} root project(s), configuration $cfg${if (withoutDependencies) ", without dependencies" else ""}):", t)
        for (root in roots) {
            val deps = graph.directDependencies(root)
            val dangling = graph.danglingReferences[root.path].orEmpty()
            tab.println("  ${root.name} [${root.extension}]${if (root.isCpp) " C++" else ""}", t)
            for (d in deps) tab.println("    -> ${d.name}${if (graph.isClr(d)) " (C++/CLI)" else if (d.isCpp) " (C++)" else ""}${if (!graph.solution.isBuilt(d, cfg)) " [not built in $cfg]" else ""}", t)
            for (d in dangling) tab.println("    -> ${d.fileName} [NOT IN SOLUTION: $d]", t)
        }
        val excluded = closure.filter { !graph.solution.isBuilt(it, cfg) }
        tab.println("  C++ closure (dependency order): ${closure.joinToString { if (graph.isClr(it)) it.name + " (C++/CLI)" else it.name }.ifEmpty { "<none>" }}", t)
        if (excluded.isNotEmpty()) tab.println("  Excluded because not built under $cfg: ${excluded.joinToString { it.name }}", t)
    }

    private fun finish(tab: IncrediBuildConsoleTab, outcome: BuildOutcome, result: CompletableFuture<BuildOutcome>) {
        val type = if (outcome.isSuccess) ConsoleViewContentType.SYSTEM_OUTPUT else ConsoleViewContentType.ERROR_OUTPUT
        val text = when (outcome) {
            BuildOutcome.SUCCESS -> "Build succeeded."
            BuildOutcome.SKIPPED -> "Nothing to build."
            BuildOutcome.FAILED -> "Build FAILED. ${tab.errorCount} error(s), ${tab.warningCount} warning(s)."
            BuildOutcome.CANCELED -> "Build canceled."
        }
        tab.println("\n[IncrediBuild] $text", type)
        tab.markFinished(outcome)
        if (outcome == BuildOutcome.FAILED) notify("IncrediBuild: build failed (${tab.errorCount} error(s)).", NotificationType.ERROR)
        else if (outcome == BuildOutcome.SUCCESS) notify("IncrediBuild: build succeeded.", NotificationType.INFORMATION)
        result.complete(outcome)
    }

    private fun runBuildConsole(
        cmd: IncrediBuildCommandLine,
        solution: SolutionModel,
        cfg: SolutionConfiguration,
        request: IncrediBuildRequest,
        projectNames: List<String>,
        tab: IncrediBuildConsoleTab,
        indicator: ProgressIndicator,
    ): BuildOutcome {
        val spec = IncrediBuildCommandLine.Spec(
            solution = solution.file,
            operation = request.operation,
            configuration = cfg.configuration,
            platform = cfg.platform,
            projectNames = projectNames,
            withoutDependencies = request.withoutDependencies,
            title = "Rider: ${request.operation.label} ${project.name}",
            msBuildExe = if (solution.file.toString().endsWith(".sln", true)) null else MsBuildLocator.find(),
            detailedLogFile = if (IncrediBuildSettings.getInstance().state.detailedMsBuildLog) solution.directory.resolve("incredibuild-msbuild.log") else null,
        )
        spec.detailedLogFile?.let { tab.println("Detailed MSBuild log: $it", ConsoleViewContentType.LOG_INFO_OUTPUT) }
        val args = cmd.build(spec)
        val commandLine = GeneralCommandLine(cmd.exePath)
            .withParameters(args)
            .withWorkDirectory(solution.directory.toString())
            .withCharset(IncrediBuildLocator.consoleCharset())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        tab.println(commandLine.commandLineString, ConsoleViewContentType.LOG_INFO_OUTPUT)
        tab.println("", ConsoleViewContentType.NORMAL_OUTPUT)

        val handler = KillableColoredProcessHandler(commandLine)
        val run = Running(handler, tab, indicator)
        running.set(run)
        val lineBuffer = StringBuilder()
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                if (outputType == ProcessOutputTypes.SYSTEM) return
                val stderr = outputType == ProcessOutputTypes.STDERR
                lineBuffer.append(event.text)
                var nl = lineBuffer.indexOf("\n")
                while (nl >= 0) {
                    val line = lineBuffer.substring(0, nl).trimEnd('\r')
                    lineBuffer.delete(0, nl + 1)
                    tab.printOutput(line, stderr)
                    tab.onLine(line)
                    indicator.text2 = line.take(120)
                    nl = lineBuffer.indexOf("\n")
                }
            }
        })
        handler.startNotify()
        while (!handler.waitFor(200)) {
            if (indicator.isCanceled) cancel()
        }
        running.set(null)
        if (lineBuffer.isNotEmpty()) { tab.printOutput(lineBuffer.toString(), false); tab.onLine(lineBuffer.toString()) }
        val exit = handler.exitCode ?: -1
        tab.println("\nBuildConsole exited with code $exit", ConsoleViewContentType.SYSTEM_OUTPUT)
        return when {
            run.canceled.get() -> BuildOutcome.CANCELED
            exit == 0 -> BuildOutcome.SUCCESS
            else -> BuildOutcome.FAILED
        }
    }

    /** Runs Rider's own build for [projectPaths] (empty = whole solution) and maps the result. */
    private fun runRiderBuild(request: IncrediBuildRequest, projectPaths: List<Path>, diagnostics: Boolean): CompletableFuture<BuildOutcome> {
        val future = CompletableFuture<BuildOutcome>()
        val target: BuildTargetBase = when (request.operation) {
            BuildOperation.BUILD -> BuildTarget()
            BuildOperation.REBUILD -> RebuildTarget()
            BuildOperation.CLEAN -> CleanTarget()
        }
        ApplicationManager.getApplication().invokeLater {
            try {
                val host = BuildHost.getInstance(project)
                val params = RiderBuildParameters.create(
                    operation = target,
                    selectedProjectsPaths = projectPaths.map { it.toString() },
                    activateWindowOnStart = true,
                    withoutDependencies = request.withoutDependencies,
                    diagnosticsMode = diagnostics,
                )
                val started = host.requestBuild(params) { kind: BuildResultKind ->
                    future.complete(
                        when (kind) {
                            BuildResultKind.Successful, BuildResultKind.HasWarnings -> BuildOutcome.SUCCESS
                            BuildResultKind.Canceled -> BuildOutcome.CANCELED
                            BuildResultKind.HasErrors, BuildResultKind.Crashed -> BuildOutcome.FAILED
                        }
                    )
                }
                if (!started) {
                    notify("Rider's build could not be started (is another build running?).", NotificationType.ERROR)
                    future.complete(BuildOutcome.FAILED)
                }
            } catch (t: Throwable) {
                LOG.warn("Failed to request Rider build", t)
                future.complete(BuildOutcome.FAILED)
            }
        }
        return future
    }

    private fun activeConfiguration(solution: SolutionModel): SolutionConfiguration {
        val active = runCatching { SolutionConfigurationManager.tryGetInstance(project)?.activeConfigurationAndPlatform }.getOrNull()
        if (active != null) return SolutionConfiguration(active.configuration, active.platform)
        return solution.configurations.firstOrNull { it.configuration.equals("Debug", true) }
            ?: solution.configurations.firstOrNull()
            ?: SolutionConfiguration("Debug", "Any CPU")
    }

    private fun notifyMissingBuildConsole() {
        val n = NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification("IncrediBuild not found", "BuildConsole.exe could not be located. Install IncrediBuild or set its path in the settings.", NotificationType.ERROR)
        n.addAction(com.intellij.notification.NotificationAction.createSimple("Open settings") {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, IncrediBuildConfigurable::class.java)
        })
        n.notify(project)
    }

    private fun notify(text: String, type: NotificationType) {
        NotificationGroupManager.getInstance().getNotificationGroup(NOTIFICATION_GROUP)
            .createNotification(text, type)
            .notify(project)
    }

    override fun dispose() {
        running.get()?.handler?.destroyProcess()
    }

    companion object {
        const val NOTIFICATION_GROUP = "IncrediBuild"
        private val LOG = logger<IncrediBuildRunner>()

        @JvmStatic
        fun getInstance(project: Project): IncrediBuildRunner = project.getService(IncrediBuildRunner::class.java)

        /** Runs `BuildConsole` with the given switches and returns its output (used for status queries). */
        fun query(vararg switches: String): String? {
            val exe = IncrediBuildLocator.buildConsole() ?: return null
            val cmd = GeneralCommandLine(exe.toString()).withParameters(*switches).withCharset(IncrediBuildLocator.consoleCharset())
            val out = CapturingProcessHandler(cmd).runProcess(TimeUnit.SECONDS.toMillis(30).toInt())
            return (out.stdout + out.stderr).trim()
        }
    }
}
