package com.github.vbmacher.intellij.dbt.navigation

import com.github.vbmacher.intellij.dbt.project.{DbtSymbol, DbtWorkspace}
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.{Key, TextRange}
import com.intellij.psi.PsiFile
import scala.jdk.CollectionConverters._

/** Parser-independent SQL evidence shared by editor features and lineage. Unknown is never an empty, known schema. */
private[dbt] final case class DbtSqlColumn(name: String, range: TextRange, inputs: Vector[DbtSymbol],
  direct: Boolean, expression: String, complete: Boolean, dataType: Option[String] = None, outputAlias: Boolean = false,
  contextInputs: Vector[(DbtSymbol, String)] = Vector.empty)
private[dbt] final case class DbtSqlProblem(range: TextRange, message: String)
private[dbt] final case class DbtSqlSuppressionComment(range: TextRange, ids: String, file: Boolean)
private[dbt] final case class DbtSqlSuppressionStatement(range: TextRange, first: Int)
private[dbt] final case class DbtSqlSuppressionLayout(statements: Vector[DbtSqlSuppressionStatement], comments: Vector[DbtSqlSuppressionComment])
private[dbt] final case class DbtSqlScope(range: TextRange, relations: Vector[(String, Vector[DbtSymbol])], complete: Boolean)
private[dbt] final case class DbtCte(name: String, range: TextRange, body: TextRange, dependencies: Vector[String], sql: String)
private[dbt] final case class DbtSqlAnalysis(columns: Vector[DbtSqlColumn], scopes: Vector[DbtSqlScope],
  ctes: Vector[DbtCte], wildcards: Vector[(TextRange, Vector[String])], issues: Vector[String],
  intermediates: Vector[DbtSqlColumn] = Vector.empty, problems: Vector[DbtSqlProblem] = Vector.empty,
  deletions: Vector[(TextRange, TextRange)] = Vector.empty, declarations: Vector[DbtSymbol] = Vector.empty,
  wildcardProblems: Vector[DbtSqlProblem] = Vector.empty) {
  def scope(offset: Int): Option[DbtSqlScope] = scopes.filter(_.range.containsOffset(offset)).sortBy(_.range.getLength).headOption
}

private[dbt] object DbtSqlAnalysis {
  def suppressionLayout(file: PsiFile): DbtSqlSuppressionLayout =
    DbtReferenceProvider.EP.getExtensionList.asScala.iterator.flatMap(_.sqlSuppressionLayout(file)).nextOption()
      .getOrElse(DbtSqlSuppressionLayout(Vector.empty, Vector.empty))
  def forCompletion(w: DbtWorkspace, file: PsiFile, offset: Int): Option[DbtSqlAnalysis] =
    DbtReferenceProvider.EP.getExtensionList.asScala.iterator.flatMap(_.sqlAnalysis(file, w, Some(offset))).nextOption()
  private val Cache = Key.create[java.util.concurrent.ConcurrentHashMap[String, DbtSqlAnalysis]]("dbt.sql.analysis")
  private val active = new ThreadLocal[Set[String]] { override def initialValue(): Set[String] = Set.empty }
  def get(w: DbtWorkspace, file: PsiFile): Option[DbtSqlAnalysis] = {
    ProgressManager.checkCanceled()
    val path = Option(file.getVirtualFile).map(_.getPath).getOrElse("")
    if (path.isEmpty || active.get()(path) || active.get().size >= 32) None
    else {
      val cache = w.synchronized {
        Option(w.getUserData(Cache)).getOrElse {
          val created = new java.util.concurrent.ConcurrentHashMap[String, DbtSqlAnalysis]()
          w.putUserData(Cache, created); created
        }
      }
      Option(cache.get(path)).orElse {
        val previous = active.get(); active.set(previous + path)
        try {
          val result = DbtReferenceProvider.EP.getExtensionList.asScala.iterator.flatMap(_.sqlAnalysis(file, w)).nextOption()
          result.foreach(cache.put(path, _)); result
        } finally active.set(previous)
      }
    }
  }
}
