# IncrediBuild Integration (for JetBrains Rider)

A JetBrains Rider plugin that dispatches builds to a running **IncrediBuild** agent. Rider has no native
IncrediBuild integration; this plugin adds one, with first-class support for the common
"C# application that depends on C++ interop projects" layout.

```
App.csproj  ──►  CppCliBridge.vcxproj (C++/CLI)  ──►  NativeCore.vcxproj (native DLL)
   │                                                        ▲
   └───────────── P/Invoke (NativeCore.dll) ────────────────┘
```

With the plugin, building `App` dispatches `NativeCore` and `CppCliBridge` to IncrediBuild (distributed
`cl.exe`/`link.exe`), and Rider's own build engine then finishes the managed project against the fresh outputs.

## Features

| Feature | Where |
|---|---|
| Build / Rebuild / Clean **solution** with IncrediBuild | *Build ▸ IncrediBuild* menu (`Ctrl+Alt+Shift+B` for Build) |
| **Take over Rider's standard build commands** (Build/Rebuild/Clean Solution, Selection, Startup Project and Current Project incl. the *Only…* variants, toolbar build button, `Ctrl+F9` / `Ctrl+Shift+B`) | Settings ▸ IncrediBuild ▸ *Use IncrediBuild for Rider's standard build actions* |
| Build / Rebuild / Clean **selected projects** with IncrediBuild | Solution Explorer context menu ▸ *IncrediBuild* |
| Build selection *without* dependencies (`/NORECURSE`) | Solution Explorer context menu ▸ *IncrediBuild* |
| **Dispatch C++ dependencies only** (no Rider build afterwards) | Solution Explorer context menu ▸ *IncrediBuild* |
| Build entire solution via BuildConsole regardless of mode | *Build ▸ IncrediBuild* |
| "Build with IncrediBuild" **before-launch task** | Run/Debug configuration ▸ *Before launch* ▸ `+` |
| IncrediBuild **tool window** with clickable `file(line,col): error CXXXX` diagnostics, cancel, Build Monitor, agent status | *View ▸ Tool Windows ▸ IncrediBuild* |
| Settings (BuildConsole path, dispatch mode, engine, agent overrides, output options) | *Settings ▸ Build, Execution, Deployment ▸ IncrediBuild* |

### Dispatch modes

* **Hybrid** (default) – the transitive `.vcxproj` dependency closure of the requested project(s) is computed from the
  solution and the `<ProjectReference>` items of each project file (plus explicit `.sln` project dependencies) and handed
  to `BuildConsole.exe <sln> /PRJ=<cpp projects> /CFG=<active configuration>`. When that succeeds, the managed
  project(s) are built through Rider's regular build pipeline (`BuildHost`), which finds the C++ outputs up to date.
  If there is nothing native to dispatch, the build goes straight to Rider.
  C++/CLI projects (`<CLRSupport>` set) are dispatched too: IncrediBuild runs their `/clr` translation units locally but
  distributes the native ones, which for large bridge projects is most of the work. *Settings > Dispatch C++/CLI projects
  to IncrediBuild* can leave them to the Rider phase instead.
* **Dependencies** – *all* dependencies of the requested project(s) – native, C++/CLI and managed – are built through
  `BuildConsole.exe` (`/PRJ=<every transitive dependency>`), then Rider builds only the requested project(s) themselves
  *without dependencies*. One engine owns everything below the target, so nothing is compiled twice across the
  C++/CLI ↔ C# boundary. Use this when C++/CLI projects sit on top of C# projects (the second phase of *Hybrid* would
  otherwise rebuild the managed assemblies they reference and invalidate them). For a whole-solution request this is
  identical to *Full*.
* **Full** – the whole request runs through `BuildConsole.exe` with IncrediBuild's MSBuild integration
  (`/USEMSBUILD`). C++ tasks are distributed; C# projects are built by MSBuild locally.

The active *solution configuration and platform* selected in Rider's toolbar is used for both modes. Projects that are
not built under that solution configuration (no `Build.0` entry) are skipped.

### Taking over Rider's own build commands

With **Use IncrediBuild for Rider's standard build actions** enabled (off by default), the plugin wraps Rider's stock
build actions (`BuildWholeSolutionAction`, the toolbar build button, `RebuildSolutionAction`, `CleanSolutionAction`,
the *Build/Rebuild/Clean Selection* context actions and the *Startup Project* / *Current Project* actions including their
*Only …* (no dependencies) variants) so that the familiar commands and shortcuts dispatch through
IncrediBuild using the configured mode; menu entries show an *(IncrediBuild)* suffix while active. The original action
is used as a fallback whenever the plugin cannot resolve a target (e.g. *Build Selection* invoked from a context without
a Solution Explorer selection).

Run/Debug (F5, Shift+F10) does not go through these actions: it executes the run configuration's *Before launch* steps,
and Rider puts a *Build Project* (or *Build Solution*) step there that calls its build host directly. With the override
enabled, the plugin therefore also swaps that step for its own **Build with IncrediBuild** step in every run
configuration (existing ones on startup, new ones as they are created) and swaps it back when the option is turned off
(*Also use IncrediBuild for the build step before Run/Debug*, on by default). Build-before-unit-tests is still Rider's own.

## Requirements

* JetBrains Rider 2025.3 or newer (builds 253+, verified on 2025.3, 2026.1 and 2026.2) on Windows. Rider 2024.3 (build 243) is
  served by the separate `rider2024.3` branch/release line.
* [IncrediBuild for Windows](https://www.incredibuild.com/) Agent installed locally (`BuildConsole.exe` is auto-detected via the
  registry / `%ProgramFiles(x86)%\IncrediBuild`, or set the path in settings).
* Visual Studio Build Tools / MSVC for the C++ projects (as for any IncrediBuild VS build).

## Building the plugin

```powershell
# Uses the locally installed Rider from gradle.properties (riderLocalPath); falls back to downloading `platformVersion`.
.\gradlew.bat test buildPlugin
# → build\distributions\rider-incredibuild-<version>.zip  (install via Settings ▸ Plugins ▸ ⚙ ▸ Install Plugin from Disk)

.\gradlew.bat runIde   # launches a sandboxed Rider with the plugin
```

A JDK 21 is required (`JAVA_HOME`). The plugin is compiled against the oldest supported Rider (`platformVersion`, Java 21
bytecode) so the same binary loads on the Java 25 based Rider 2026.x; `verifyPlugin` checks it against 2025.3, 2026.1 and
2026.2. `riderLocalPath` can point at a local install instead of downloading.

### Supporting older Rider versions

| Rider | Java | `BuildParameters` ctor | project-model module split | Served by |
|---|---|---|---|---|
| 2024.3 (243) | 21 | old | no | `rider2024.3` branch |
| 2025.3 (253) | 21 | `Boolean` silent mode | yes | `main` |
| 2026.1 (261) | 25 | `Boolean` silent mode | yes | `main` |
| 2026.2 (262) | 25 | `SilentMode` enum | yes | `main` |

`main` handles the 2026.2 constructor change reflectively (`RiderBuildParameters`), so one zip covers 2025.3 → 2026.2+.
Only Rider 2024.3 needs the separate `rider2024.3` branch (older `BuildParameters`, no module split); its diffs are small
and can be cherry-picked in either direction. 2025.1/2025.2 have not been verified.

## Sample solution

`samples/InteropDemo` contains the layout from the diagram above (`App` C# console app, `CppCliBridge` C++/CLI .NET 8
assembly, `NativeCore` native DLL with several template-heavy translation units). Open `InteropDemo.sln` in Rider,
select `App` in the Solution Explorer and choose *IncrediBuild ▸ Build 'App' with IncrediBuild*; the tool window shows the
`BuildConsole` command line, the distributed C++ tasks (agent and timing per task) and then the Rider build of `App`.

Equivalent command lines that the plugin generates for that action:

```
BuildConsole.exe InteropDemo.sln /CFG="Debug|x64" /PRJ=NativeCore,CppCliBridge /USEMSBUILD=64 /MSBUILDARGS=/restore /NOLOGO /SHOWAGENT /SHOWTIME   # hybrid: C++ part
BuildConsole.exe InteropDemo.sln /CFG="Debug|x64" /USEMSBUILD=64 /MSBUILDARGS=/restore /NOLOGO /SHOWAGENT /SHOWTIME             # full mode
```

## Using it as the build step for Run/Debug

With *Use IncrediBuild for Rider's standard build actions* enabled this happens automatically (see above). To do it by
hand for a single configuration:

1. *Run ▸ Edit Configurations…*, pick the configuration.
2. Under *Before launch* remove Rider's default *Build Solution* / *Build Project* step and add **Build with IncrediBuild**.
3. Run/Debug as usual – the project is built through the configured dispatch mode, then the app starts.

## Troubleshooting

*Settings ▸ IncrediBuild ▸ Troubleshooting* offers:

* **Timestamp every output line** – wall-clock prefix on each line of the IncrediBuild tool window. Phase start/end
  times and durations (IncrediBuild phase, Rider phase, total) are always printed.
* **Detailed MSBuild log for the IncrediBuild phase** – `/flp:LogFile=<solution dir>\incredibuild-msbuild.log;Verbosity=detailed`;
  the log records, per target and file, *why* MSBuild considered it out of date ("Input file … is newer than output file …").
* **Rider phase in diagnostics mode** – the managed projects are built with Rider's *Build with diagnostics* flag, giving
  the same level of detail in Rider's Build window for the second phase of a hybrid build.
* **Explain dependency resolution** – prints the root projects, their resolved / unresolved `ProjectReference`s, the
  resulting C++ closure and any projects excluded because they are not built under the active configuration.

Typical use: if C++ projects that IncrediBuild just built get recompiled again by the Rider phase, enable the detailed
MSBuild log and diagnostics mode, run once more, and look for the first "newer than" / "not up to date" line for the
affected project – it names the input (often a generated file or a referenced managed assembly) that changed between
the two phases.

## Notes and limitations

* `.slnx` solutions are supported through `BuildConsole /COMMAND="msbuild ..."` (IncrediBuild's automatic interception)
  because BuildConsole's Visual Studio syntax only accepts `.sln`. MSBuild is located with `vswhere`.
* `ProjectReference` items containing MSBuild properties (`$(...)`) cannot be resolved statically and are skipped for
  dependency discovery; use *Full* mode or add an explicit project dependency in the `.sln` in that case.
* Cancelling issues `BuildConsole /STOP` first so the distributed build is torn down cleanly, then kills the process.
* The plugin is a Rider *frontend* (JVM) plugin; it does not require a ReSharper/backend component.
