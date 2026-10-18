package com.github.vbmacher.intellij.dbt.ide

import org.junit.Test
import org.junit.Assert._
import java.nio.file.Files
import scala.jdk.CollectionConverters._

/** Real Git reads use an isolated throwaway repository, never the user's index. */
final class DbtImpactGitTest {
  @Test def selectedRevisionReadsBaselineAndLeavesWorkingFilesUntouched(): Unit = {
    val directory = Files.createTempDirectory("dbt-impact-git-")
    def git(arguments: String*): String = DbtImpact.git(directory.toString, arguments.toVector).fold(error => throw new AssertionError(error), identity)
    try {
      git("init", "--quiet")
      val model = directory.resolve("orders.sql")
      Files.writeString(model, "select 1 as amount\n")
      git("add", "orders.sql")
      git("-c", "commit.gpgsign=false", "-c", "core.hooksPath=/dev/null", "-c", "user.name=dbt fixture", "-c", "user.email=dbt-fixture@example.test", "commit", "--quiet", "-m", "baseline fixture")
      git("tag", "impact-baseline")
      Files.writeString(model, "select 2 as gross_amount\n")
      val revision = git("rev-parse", "--verify", "--end-of-options", "impact-baseline^{commit}").trim
      assertTrue(revision.matches("[0-9a-f]{40,64}"))
      assertEquals("select 1 as amount\n", git("show", revision + ":orders.sql"))
      assertEquals("select 2 as gross_amount\n", Files.readString(model))
      assertEquals(" M orders.sql\n", git("status", "--porcelain"))
      assertTrue(DbtImpact.git(directory.toString, Vector("rev-parse", "--verify", "--end-of-options", "--help^{commit}")).isLeft)
    } finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally paths.close()
    }
  }
}
