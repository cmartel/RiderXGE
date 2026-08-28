package org.riderxge.incredibuild.build

import org.riderxge.incredibuild.ib.BuildOperation
import org.riderxge.incredibuild.settings.DispatchMode
import java.nio.file.Path

/** What the user asked for. Paths are absolute project files; an empty list means the whole solution. */
data class IncrediBuildRequest(
    val operation: BuildOperation,
    val projectPaths: List<Path> = emptyList(),
    /** Build only the given projects, not their dependencies (`/NORECURSE`, and `withoutDependencies` for Rider). */
    val withoutDependencies: Boolean = false,
    /** Overrides the dispatch mode from settings. */
    val modeOverride: DispatchMode? = null,
    /** Hybrid only: dispatch the C++ projects and stop – do not hand over to Rider's build afterwards. */
    val cppOnly: Boolean = false,
    /** Activate the IncrediBuild tool window when the build starts (subject to the user setting). */
    val activateWindow: Boolean = true,
) {
    val isWholeSolution: Boolean get() = projectPaths.isEmpty()
}

enum class BuildOutcome(val isSuccess: Boolean) {
    SUCCESS(true),
    /** Nothing to do for this phase (e.g. no C++ projects to dispatch). */
    SKIPPED(true),
    FAILED(false),
    CANCELED(false);
}
