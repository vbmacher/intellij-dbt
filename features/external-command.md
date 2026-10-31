# External command execution

[Capability index](README.md) · [User guide](../docs/configuration.md#external-tools-and-script-wrappers)

Run dbt through an executable, interpreter or launcher with fixed prefix arguments.

## Contract

- **Inputs:** the owning root’s executable, prefix arguments, execution directory, environment, profiles, artifact path and target; a typed dbt command request.
- **Output:** one argument vector in this order: executable, parsed prefix arguments, dbt subcommand and generated command options, target option when configured, color option.
- Prefix arguments precede every command, including Test, Build, Preview, Generate Docs and Resolve Selection. They are never scanned to find or replace a dbt subcommand.
- Quoting groups arguments containing spaces. Execution invokes the executable directly; shell substitutions, variable expansion, tilde expansion, redirection and pipes are not interpreted.
- A relative executable path containing a path separator resolves from the execution directory. Command names resolve through the process PATH. A discovered local dbt virtual environment contributes to PATH.
- The process working directory is the configured execution root. Profiles and artifact directories are passed as DBT_PROFILES_DIR and DBT_TARGET_PATH. Explicit target and color flags follow the command request.
- Child processes inherit the IDE environment and receive the owning root's configured environment files and IntelliJ environment overrides under the [environment contract](configuration.md). Values are passed as process environment entries, without shell evaluation or adding them to the displayed command line. SQL formatters share this environment. Native reruns retain the captured values.
- The external tool must forward the appended dbt arguments, preserve the process exit status and produce compatible output/artifacts. A launch failure or incompatible/missing results is reported; it never certifies success.
- Native failed-test reruns retain the captured prefix boundary and execution context. [Managed execution](workspace.md) owns concurrency, cancellation and completion.

## Example

Executable `uv` with prefix arguments `run dbt` and a Test request produces
`uv run dbt test --log-format json …`. Executable `zsh` with
`"scripts/dbt wrapper.zsh" analytics` produces
`zsh "scripts/dbt wrapper.zsh" analytics test --log-format json …`.
