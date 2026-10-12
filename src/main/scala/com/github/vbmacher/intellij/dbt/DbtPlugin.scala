package com.github.vbmacher.intellij.dbt

/** Entry point of the dbt plugin. Holds plugin-wide constants; functionality will be added later. */
object DbtPlugin {

  /** Plugin ID, must match the `id` in `META-INF/plugin.xml`. */
  final val Id = "com.github.vbmacher.intellij-dbt"

  /** File which marks the root of a dbt project. */
  final val ProjectFileName = "dbt_project.yml"
}
