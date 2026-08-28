package org.riderxge.incredibuild.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.builder.toNonNullableProperty
import com.intellij.ui.dsl.builder.toNullableProperty
import org.riderxge.incredibuild.ib.IncrediBuildLocator
import org.riderxge.incredibuild.run.BeforeRunTaskSwapper

/** Settings | Build, Execution, Deployment | IncrediBuild */
class IncrediBuildConfigurable : BoundConfigurable("IncrediBuild") {

    override fun apply() {
        super.apply()
        BeforeRunTaskSwapper.syncAllProjects()
    }

    override fun createPanel(): DialogPanel {
        val state = IncrediBuildSettings.getInstance().state
        val detected = IncrediBuildLocator.detectBuildConsole()?.toString() ?: "not found"
        val version = IncrediBuildLocator.versionText()?.let { " (v$it)" } ?: ""
        return panel {
            group("Installation") {
                row("BuildConsole.exe:") {
                    textFieldWithBrowseButton(
                        FileChooserDescriptorFactory.createSingleFileDescriptor("exe").withTitle("Select BuildConsole.exe")
                    )
                        .bindText(state::buildConsolePath.toNonNullableProperty(""))
                        .align(AlignX.FILL)
                        .comment("Leave empty to auto-detect. Detected: $detected$version")
                }
            }
            group("Dispatch") {
                row("Mode:") {
                    comboBox(DispatchMode.entries, textListCellRenderer<DispatchMode?> { it?.label ?: "" })
                        .bindItem(state::dispatchMode.toNullableProperty())
                        .comment(
                            "Hybrid: the C++ dependency projects of the target are dispatched to IncrediBuild, then Rider builds the managed projects. " +
                                "Dependencies: everything the target depends on (native, C++/CLI and managed) is built through BuildConsole, " +
                                "then Rider builds only the target - use this when C++/CLI projects sit on top of C# projects. " +
                                "Full: the whole solution is built through BuildConsole."
                        )
                }
                row("Build engine:") {
                    comboBox(BuildEngine.entries, textListCellRenderer<BuildEngine?> { it?.label ?: "" })
                        .bindItem(state::buildEngine.toNullableProperty())
                        .comment("Engine BuildConsole uses for Visual Studio solutions. MSBuild is recommended for SDK-style C# projects.")
                }
                lateinit var override: com.intellij.ui.dsl.builder.Cell<javax.swing.JCheckBox>
                row {
                    override = checkBox("Use IncrediBuild for Rider's standard build actions")
                        .bindSelected(state::overrideStandardBuildActions)
                        .comment(
                            "Reroutes Build/Rebuild/Clean for the solution, the selection, the startup project and the current project " +
                                "(menus, the toolbar build button, Ctrl+F9 / Ctrl+Shift+B) through IncrediBuild using the mode above. Rider's own build is used as a " +
                                "fallback when nothing can be dispatched."
                        )
                }
                indent {
                    row {
                        checkBox("Also use IncrediBuild for the build step before Run/Debug")
                            .bindSelected(state::replaceBeforeRunBuildSteps)
                            .comment(
                                "Replaces Rider's \"Build Project\" / \"Build Solution\" step under Before launch in every run configuration " +
                                    "(what F5 / Shift+F10 execute) with \"Build with IncrediBuild\". The steps are switched back when this is turned off."
                            )
                            .enabledIf(override.selected)
                    }
                }
                row {
                    checkBox("Dispatch C++/CLI (/clr) projects to IncrediBuild in hybrid mode").bindSelected(state::dispatchClrProjects)
                        .comment(
                            "On (default): the native translation units of C++/CLI projects are distributed; the /clr ones run locally. " +
                                "Off leaves such projects to the Rider phase entirely."
                        )
                }
                row {
                    checkBox("Restore NuGet packages before building (/restore)").bindSelected(state::restorePackages)
                        .comment("Required for SDK-style C# projects when the whole solution is built through BuildConsole.")
                }
                row("Additional MSBuild arguments:") {
                    textField().bindText(state::msBuildArgs.toNonNullableProperty("")).align(AlignX.FILL)
                        .comment("Passed via /MSBUILDARGS when the MSBuild engine is used, e.g. /v:m")
                }
                row("Additional BuildConsole arguments:") {
                    textField().bindText(state::extraArgs.toNonNullableProperty("")).align(AlignX.FILL)
                        .comment("Appended verbatim, e.g. /USECLOUDHELPERS=TRUE /ADDKNOWNPATH=SdkRoot:C:\\dev\\SDK")
                }
            }
            group("Agent Overrides") {
                row("Max CPUs/cores:") {
                    intTextField(0..1024).bindIntText(state::maxCpus).comment("0 = use the agent's global setting")
                }
                row("Avoid local execution:") {
                    comboBox(TriState.entries, textListCellRenderer<TriState?> { it?.label ?: "" })
                        .bindItem(state::avoidLocal.toNullableProperty())
                }
                row("Standalone mode:") {
                    comboBox(TriState.entries, textListCellRenderer<TriState?> { it?.label ?: "" })
                        .bindItem(state::standalone.toNullableProperty())
                        .comment("Standalone builds run on the local machine only (useful when no helpers are reachable).")
                }
                row { checkBox("Stop build immediately on first error (/STOPONERRORS)").bindSelected(state::stopOnErrors) }
            }
            group("Output") {
                row { checkBox("Show executing agent for each task (/SHOWAGENT)").bindSelected(state::showAgent) }
                row { checkBox("Show task execution times (/SHOWTIME)").bindSelected(state::showTime) }
                row { checkBox("Open the IncrediBuild Build Monitor when a build starts (/OPENMONITOR)").bindSelected(state::openMonitor) }
                row { checkBox("Activate the IncrediBuild tool window when a build starts").bindSelected(state::activateToolWindow) }
                row { checkBox("Beep when the build completes (/BEEP)").bindSelected(state::beepOnFinish) }
            }
            group("Troubleshooting") {
                row { checkBox("Timestamp every output line").bindSelected(state::timestampOutput) }
                row {
                    checkBox("Write a detailed MSBuild log for the IncrediBuild phase").bindSelected(state::detailedMsBuildLog)
                        .comment("Adds /flp:LogFile=<solution dir>/incredibuild-msbuild.log;Verbosity=detailed, which records why each target/file was (re)built.")
                }
                row {
                    checkBox("Run the Rider phase in diagnostics mode").bindSelected(state::riderDiagnosticsBuild)
                        .comment("Hybrid mode only: Rider builds the managed projects with detailed MSBuild output in its Build window.")
                }
                row {
                    checkBox("Explain dependency resolution").bindSelected(state::explainDependencies)
                        .comment("Prints how the C++ dispatch list was derived: roots, resolved/unresolved references and configuration exclusions.")
                }
            }
        }
    }
}
