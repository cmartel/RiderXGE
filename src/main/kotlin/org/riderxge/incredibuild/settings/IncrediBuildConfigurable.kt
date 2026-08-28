package org.riderxge.incredibuild.settings

import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.toNonNullableProperty
import com.intellij.ui.dsl.builder.toNullableProperty
import org.riderxge.incredibuild.ib.IncrediBuildLocator

/** Settings | Build, Execution, Deployment | IncrediBuild */
class IncrediBuildConfigurable : BoundConfigurable("IncrediBuild") {

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
                    comboBox(DispatchMode.entries, SimpleListCellRenderer.create("") { it.label })
                        .bindItem(state::dispatchMode.toNullableProperty())
                        .comment(
                            "Hybrid: the C++ dependency projects of the selected target are dispatched to IncrediBuild, " +
                                "then Rider builds the managed projects. Full: the whole solution is built through BuildConsole."
                        )
                }
                row("Build engine:") {
                    comboBox(BuildEngine.entries, SimpleListCellRenderer.create("") { it.label })
                        .bindItem(state::buildEngine.toNullableProperty())
                        .comment("Engine BuildConsole uses for Visual Studio solutions. MSBuild is recommended for SDK-style C# projects.")
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
                    comboBox(TriState.entries, SimpleListCellRenderer.create("") { it.label })
                        .bindItem(state::avoidLocal.toNullableProperty())
                }
                row("Standalone mode:") {
                    comboBox(TriState.entries, SimpleListCellRenderer.create("") { it.label })
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
        }
    }
}
