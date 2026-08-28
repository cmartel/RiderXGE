package org.riderxge.incredibuild.build

import com.intellij.openapi.diagnostic.logger
import com.jetbrains.rider.build.BuildParameters
import com.jetbrains.rider.model.BuildTargetBase

/**
 * Creates Rider's [BuildParameters] across the constructor change in Rider 2026.2 (build 262), where the
 * `silentMode: Boolean` parameter became a `com.jetbrains.rider.model.SilentMode` enum. Resolving the
 * constructor reflectively lets one binary run on 2025.3 through 2026.2+.
 */
object RiderBuildParameters {
    private val LOG = logger<RiderBuildParameters>()

    fun create(
        operation: BuildTargetBase,
        selectedProjectsPaths: List<String>,
        activateWindowOnStart: Boolean,
        withoutDependencies: Boolean,
        diagnosticsMode: Boolean = false,
    ): BuildParameters {
        val ctor = BuildParameters::class.java.constructors
            .filter { it.parameterCount == 7 && it.parameterTypes[0] == BuildTargetBase::class.java && it.parameterTypes[1] == List::class.java }
            .minByOrNull { it.parameterCount }
            ?: throw IllegalStateException("No compatible BuildParameters constructor found in this Rider version")
        val silentType = ctor.parameterTypes[3]
        val silent: Any = if (silentType == java.lang.Boolean.TYPE) {
            false
        } else {
            // enum SilentMode { Default, Silent, Hidden }
            silentType.enumConstants.firstOrNull { (it as Enum<*>).name == "Default" }
                ?: silentType.enumConstants.first()
        }
        LOG.debug("Using BuildParameters constructor ${ctor.parameterTypes.joinToString { it.simpleName }}")
        // (operation, selectedProjectsPaths, diagnosticsMode, silentMode, activateWindowOnStart, withoutDependencies, noRestore)
        return ctor.newInstance(operation, selectedProjectsPaths, diagnosticsMode, silent, activateWindowOnStart, withoutDependencies, false) as BuildParameters
    }
}
