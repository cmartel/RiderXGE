package org.riderxge.incredibuild.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/** How a build request is split between IncrediBuild and Rider's own build engine. */
enum class DispatchMode(val label: String) {
    /**
     * Resolve the transitive C++ (`.vcxproj`) dependencies of the requested projects, dispatch only those to
     * IncrediBuild, then let Rider's regular build pipeline build the managed projects (which will find the C++
     * outputs already up to date).
     */
    HYBRID("C++ projects via IncrediBuild, managed projects via Rider"),

    /** Build everything (C# and C++) through `BuildConsole.exe`, i.e. through IncrediBuild's MSBuild integration. */
    FULL("Entire build via IncrediBuild (BuildConsole)");
}

enum class TriState(val label: String) { DEFAULT("Use agent settings"), ON("On"), OFF("Off") }

enum class BuildEngine(val label: String, val switch: String?) {
    MSBUILD_64("MSBuild (64-bit)", "/USEMSBUILD=64"),
    MSBUILD_32("MSBuild (32-bit)", "/USEMSBUILD=32"),
    DEVENV("Visual Studio (devenv)", null);
}

class IncrediBuildSettingsState : BaseState() {
    /** Explicit path to BuildConsole.exe; empty means auto-detect. */
    var buildConsolePath by string("")
    var dispatchMode by enum(DispatchMode.HYBRID)
    var buildEngine by enum(BuildEngine.MSBUILD_64)
    /** Pass `/restore` to MSBuild so SDK-style projects get their NuGet assets before building (NETSDK1004 otherwise). */
    var restorePackages by property(true)
    var msBuildArgs by string("")
    var extraArgs by string("")
    var openMonitor by property(false)
    var showAgent by property(true)
    var showTime by property(true)
    var stopOnErrors by property(false)
    var maxCpus by property(0)
    var avoidLocal by enum(TriState.DEFAULT)
    var standalone by enum(TriState.DEFAULT)
    var activateToolWindow by property(true)
    var beepOnFinish by property(false)
}

@Service(Service.Level.APP)
@State(name = "IncrediBuildSettings", storages = [Storage("incredibuild.xml")])
class IncrediBuildSettings : SimplePersistentStateComponent<IncrediBuildSettingsState>(IncrediBuildSettingsState()) {
    companion object {
        @JvmStatic
        fun getInstance(): IncrediBuildSettings =
            ApplicationManager.getApplication().getService(IncrediBuildSettings::class.java)
    }
}
