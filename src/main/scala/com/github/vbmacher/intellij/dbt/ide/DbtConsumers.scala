package com.github.vbmacher.intellij.dbt.ide

import com.github.vbmacher.intellij.dbt.project._
import com.github.vbmacher.intellij.dbt.lineage.DbtLineage
import com.github.vbmacher.intellij.dbt.syntax.DbtYaml
import com.google.gson.{JsonElement, JsonObject, JsonParser}
import com.intellij.credentialStore.{CredentialAttributes, Credentials}
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components._
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration
import javax.swing._
import java.awt.{GridBagConstraints, GridBagLayout, Insets}
import scala.beans.BeanProperty
import scala.jdk.CollectionConverters._
import scala.util.control.NonFatal

final class DbtConsumersState {
  @BeanProperty var metabaseUrl: String = ""
  @BeanProperty var agentEnabled: Boolean = false
  @BeanProperty var airflowPaths: java.util.List[String] = new java.util.ArrayList[String]()
  @BeanProperty var airflowUrl: String = ""
}
@Service(Array(Service.Level.PROJECT))
@State(name = "DbtIntegrations", storages = Array(new Storage(StoragePathMacros.WORKSPACE_FILE)))
final class DbtConsumersSettings extends PersistentStateComponent[DbtConsumersState] {
  private var state = new DbtConsumersState
  override def getState: DbtConsumersState = state
  override def loadState(value: DbtConsumersState): Unit = { state = value }
}
private[dbt] final case class DbtConsumer(name: String, kind: String, owner: String, url: Option[String],
  resource: String, column: Option[String], confidence: String, evidence: String)
private[dbt] final case class DbtConsumersCache(consumers: Vector[DbtConsumer], refreshedAt: Long, status: String) {
  def stale: Boolean = refreshedAt == 0 || System.currentTimeMillis() - refreshedAt > 15 * 60 * 1000
}

/** No polling. Network access happens only on an explicit Refresh action. */
@Service(Array(Service.Level.PROJECT))
final class DbtConsumers(project: Project) {
  @volatile private var cache = DbtConsumersCache(Vector.empty, 0, "Disconnected. Configure Metabase URL and API key in dbt Integrations.")
  def snapshot: DbtConsumersCache = cache
  private def credential: CredentialAttributes = new CredentialAttributes("dbt Workspace Metabase " + project.getLocationHash)
  private[ide] def apiKey: String = Option(PasswordSafe.getInstance().getPassword(credential)).getOrElse("")
  def hasSecret: Boolean = apiKey.nonEmpty
  def setSecret(secret: String): Unit = PasswordSafe.getInstance().set(credential, if (secret.isEmpty) null else new Credentials("api-key", secret))
  def clear(): Unit = { cache = DbtConsumersCache(Vector.empty, 0, "Disconnected. Refresh Metabase metadata explicitly.") }
  def refresh(w: DbtWorkspace): DbtConsumersCache = refresh(w, Duration.ofSeconds(10))
  private[ide] def refresh(w: DbtWorkspace, requestTimeout: Duration): DbtConsumersCache = try {
    val url = project.getService(classOf[DbtConsumersSettings]).getState.metabaseUrl
    val key = Option(PasswordSafe.getInstance().getPassword(credential)).filter(_.nonEmpty)
    if (DbtAirflow.safeUrl(url).isEmpty || key.isEmpty) {
      cache = cache.copy(status = "Disconnected. Configure Metabase URL and API key in dbt Integrations."); cache
    } else {
      val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build()
      val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(60)
      def get(path: String): Either[String, JsonElement] = try {
        com.intellij.openapi.progress.ProgressManager.checkCanceled()
        if (System.nanoTime() > deadline) return Left("Metabase refresh exceeded the 60 second limit.")
        val requestDeadline = math.min(deadline, System.nanoTime() + requestTimeout.toNanos)
        val request = HttpRequest.newBuilder(URI.create(url.stripSuffix("/") + path)).timeout(requestTimeout)
          .header("x-api-key", key.get).GET().build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val stream = response.body()
        val body = java.util.concurrent.CompletableFuture.supplyAsync(() => stream.readNBytes(4000001))
        val bytes = try {
          body.get(math.max(1L, requestDeadline - System.nanoTime()), java.util.concurrent.TimeUnit.NANOSECONDS)
        } finally { body.cancel(true); stream.close() }
        if (response.statusCode() == 401 || response.statusCode() == 403) Left("Permission denied. Check the Metabase API key and metadata permissions.")
        else if (response.statusCode() != 200) Left("Metabase metadata request failed (HTTP " + response.statusCode() + ").")
        else if (bytes.length > 4000000) Left("Metabase response exceeds the 4 MB metadata limit.")
        else Right(JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
      } catch {
        case canceled: com.intellij.openapi.progress.ProcessCanceledException => throw canceled
        case NonFatal(_) => Left("Metabase is unavailable or returned invalid metadata.")
      }
      val refreshed = for {
        cards <- get("/api/card")
        databases <- get("/api/database")
        tables <- {
          val list = if (databases.isJsonArray) databases.getAsJsonArray.asScala.toVector
          else Option(databases.getAsJsonObject.getAsJsonArray("data")).toVector.flatMap(_.asScala)
          if (list.size > 20) Left("Metabase exceeds the 20 database metadata limit.")
          else list.foldLeft[Either[String, Vector[JsonObject]]](Right(Vector.empty)) { (acc, db) => for {
            previous <- acc
            metadata <- get("/api/database/" + db.getAsJsonObject.get("id").getAsInt + "/metadata")
          } yield previous ++ Option(metadata.getAsJsonObject.getAsJsonArray("tables")).toVector.flatMap(_.asScala).map(_.getAsJsonObject) }
        }
        _ <- Either.cond(cards.isJsonArray && cards.getAsJsonArray.size() <= 1000, (), "Metabase question response is unsupported or exceeds 1000 questions.")
        dashboards <- get("/api/dashboard")
      } yield {
        val questions = DbtImpactUi.read { DbtConsumers.mapQuestions(DbtWorkspace.get(project), url, cards.getAsJsonArray.asScala.toVector.map(_.getAsJsonObject), tables) }
        val list = if (dashboards.isJsonArray) dashboards.getAsJsonArray.asScala.toVector else Vector.empty
        val dashboardConsumers = list.take(100).flatMap { d =>
          val dashboard = d.getAsJsonObject
          get("/api/dashboard/" + dashboard.get("id").getAsInt).toOption.toVector.flatMap { detail =>
            val cardIds = Option(detail.getAsJsonObject.getAsJsonArray("dashcards")).toVector.flatMap(_.asScala)
              .flatMap(c => Option(c.getAsJsonObject.get("card_id")).filterNot(_.isJsonNull).map(_.getAsString)).toSet
            questions.filter(q => cardIds(q.evidence.takeWhile(_ != ':'))).map(q => q.copy(name = DbtConsumers.string(dashboard, "name"),
              kind = "Metabase dashboard", url = Some(url.stripSuffix("/") + "/dashboard/" + dashboard.get("id").getAsInt),
              confidence = "inferred via question", evidence = q.name + ": " + q.evidence))
          }
        }
        DbtConsumersCache((questions ++ dashboardConsumers).distinct, System.currentTimeMillis(),
          if (list.size > 100) "Partial: first 100 dashboards only." else "Metadata refreshed. Dashboard details unavailable to this key are omitted.")
      }
      cache = refreshed.fold(error => cache.copy(status = error), identity); cache
    }
  } catch {
    case canceled: com.intellij.openapi.progress.ProcessCanceledException => throw canceled
    case NonFatal(_) => cache = cache.copy(status = "Metabase returned unsupported or invalid metadata; previous cache retained."); cache
  }
}
private[dbt] object DbtConsumers {
  def exposures(w: DbtWorkspace, resource: Option[DbtSymbol] = None): Vector[DbtConsumer] = {
    val candidates = resource.toVector.flatMap(s => s +: DbtLineage.traverse(DbtLineage.edges(w), s, downstream = true, 100))
      .map(DbtResourceIdentity.key(w, _)).toSet
    w.symbols.filter(s => s.kind == "exposure" && (resource.isEmpty || candidates(DbtResourceIdentity.key(w, s)))).sortBy(s => (s.metadata, s.isStale)).distinctBy(DbtResourceIdentity.key(w, _)).map { s =>
      val m = s.mapping
      val owner = m.flatMap(DbtYaml.map(_, "owner")).flatMap(DbtYaml.string(_, "name")).getOrElse("")
      DbtConsumer(s.name, "dbt exposure", owner, m.flatMap(DbtYaml.string(_, "url")).flatMap(DbtAirflow.safeUrl),
        DbtResourceIdentity.key(w, s), None, (if (s.isStale) "declared (stale local artifact)" else if (s.metadata) "declared (local artifact)" else "declared"), s.file.getVirtualFile.getPath + ":" + s.range.getStartOffset)
    }
  }
  def string(json: JsonObject, key: String): String = Option(json.get(key)).filter(e => e.isJsonPrimitive && !e.isJsonNull).map(_.getAsString).getOrElse("")
  private def normalize(s: String): String = s.replace("\"", "").replace("`", "").toLowerCase(java.util.Locale.ROOT)
  private[ide] def mapQuestions(w: DbtWorkspace, base: String, cards: Vector[JsonObject], tables: Vector[JsonObject]): Vector[DbtConsumer] = {
    val relations = w.symbols.filter(s => DbtSymbol.RelationKinds(s.kind) && !s.isProperty).map(s => s -> DbtResourceData.get(w, s))
    cards.flatMap { card =>
      val query = Option(card.getAsJsonObject("dataset_query"))
      val native = query.flatMap(q => Option(q.getAsJsonObject("native"))).map(n => string(n, "query")).getOrElse("")
      val mbql = query.flatMap(q => Option(q.getAsJsonObject("query")))
      val source = mbql.flatMap(q => Option(q.get("source-table"))).filter(_.isJsonPrimitive).map(_.getAsString)
      val matchedTables = tables.filter(t => source.contains(string(t, "id")))
      val fields = mutableFieldIds(mbql.toVector)
      relations.flatMap { case (resource, data) =>
        val attrs = data.attributes
        val relation = attrs.get("relation_name").orElse(for (db <- attrs.get("database"); schema <- attrs.get("schema"))
          yield db + "." + schema + "." + attrs.getOrElse("identifier", attrs.getOrElse("alias", resource.name)))
        val matches = matchedTables.filter(t => relation.exists { r =>
          val physical = string(t, "schema") + "." + string(t, "name")
          if (string(t, "db_name").nonEmpty) normalize(r) == normalize(string(t, "db_name") + "." + physical)
          else normalize(r).endsWith("." + normalize(physical))
        })
        val nativeMatch = relation.exists(r => normalize(native).contains(normalize(r)))
        if (matches.isEmpty && !nativeMatch) Vector.empty
        else {
          val collisions = relations.count { case (otherResource, other) =>
            val a = other.attributes
            val physical = a.get("relation_name").orElse(for (db <- a.get("database"); schema <- a.get("schema"))
              yield db + "." + schema + "." + a.getOrElse("identifier", a.getOrElse("alias", otherResource.name)))
            physical.exists(r => relation.exists(normalize(_) == normalize(r)))
          }
          val columns = matches.flatMap(t => Option(t.getAsJsonArray("fields")).toVector.flatMap(_.asScala))
            .map(_.getAsJsonObject).filter(f => fields(string(f, "id"))).map(f => string(f, "name")).distinct
          val exact = !resource.isStale && matches.nonEmpty && matches.forall(t => string(t, "db_name").nonEmpty) && collisions <= 1 && resource.version.isEmpty
          val confidence = if (exact) "direct physical metadata" else "inferred; stale metadata, target/package/version or SQL ownership unproven"
          (if (columns.isEmpty) Vector(None) else columns.map(Some(_))).map(column => DbtConsumer(string(card, "name"),
            "Metabase question", "", Some(base.stripSuffix("/") + "/question/" + string(card, "id")), DbtResourceIdentity.key(w, resource),
            column, confidence, string(card, "id") + ":" + relation.getOrElse("unknown") + column.map("." + _).getOrElse("")))
        }
      }
    }
  }
  private def mutableFieldIds(roots: Vector[JsonObject]): Set[String] = {
    def walk(e: JsonElement, depth: Int): Vector[String] = if (depth > 30) Vector.empty else if (e.isJsonArray) {
      val xs = e.getAsJsonArray.asScala.toVector
      (if (xs.headOption.exists(x => x.isJsonPrimitive && x.getAsString == "field")) xs.lift(1).filter(_.isJsonPrimitive).map(_.getAsString).toVector else Vector.empty) ++
        xs.flatMap(walk(_, depth + 1))
    } else if (e.isJsonObject) e.getAsJsonObject.entrySet().asScala.toVector.flatMap(x => walk(x.getValue, depth + 1)) else Vector.empty
    roots.flatMap(walk(_, 0)).toSet
  }
}

final class DbtConsumersConfigurable(project: Project) extends Configurable {
  private val url = new JTextField()
  private val secret = new JPasswordField()
  private val agent = new JCheckBox("Enable local read-only MCP server (start explicitly from Tools)")
  private val airflow = new DbtAirflowSettingsPanel(project)
  private val panel = new JPanel(new GridBagLayout)
  private val metabase = new JPanel(new GridBagLayout)
  metabase.setBorder(BorderFactory.createTitledBorder("Metabase"))
  Vector[JComponent](new JLabel("Metabase URL (optional)"), url,
    new JLabel("Metabase API key (stored in IDE Password Safe)"), secret).zipWithIndex.foreach { case (field, row) =>
    val c = new GridBagConstraints
    c.gridx = 0; c.gridy = row; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL
    c.anchor = GridBagConstraints.NORTHWEST; c.insets = new Insets(3, 4, 3, 4)
    metabase.add(field, c)
  }
  secret.setToolTipText("Stored in IDE Password Safe. Clear this field and click Apply to remove the saved API key.")
  Vector[JComponent](metabase, agent, airflow.component).zipWithIndex.foreach { case (field, row) =>
    val c = new GridBagConstraints
    c.gridx = 0; c.gridy = row; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL
    c.anchor = GridBagConstraints.NORTHWEST; c.insets = new Insets(3, 4, 3, 4)
    if (field == airflow.component) c.weighty = 1
    panel.add(field, c)
  }
  private def settings = project.getService(classOf[DbtConsumersSettings])
  override def getDisplayName: String = "dbt Integrations"
  override def createComponent(): JComponent = { reset(); panel }
  override def isModified: Boolean = url.getText != settings.getState.metabaseUrl || agent.isSelected != settings.getState.agentEnabled || new String(secret.getPassword) != project.getService(classOf[DbtConsumers]).apiKey || airflow.isModified
  override def apply(): Unit = {
    val value = url.getText.trim
    if (value.nonEmpty && DbtAirflow.safeUrl(value).isEmpty) throw new com.intellij.openapi.options.ConfigurationException("Use an http(s) Metabase URL without embedded credentials.")
    airflow.validate()
    airflow.apply()
    settings.getState.metabaseUrl = value; settings.getState.agentEnabled = agent.isSelected
    val service = project.getService(classOf[DbtConsumers])
    service.setSecret(new String(secret.getPassword))
    service.clear(); if (!agent.isSelected) project.getService(classOf[DbtAgent]).stop()
  }
  override def reset(): Unit = { url.setText(settings.getState.metabaseUrl); agent.setSelected(settings.getState.agentEnabled); secret.setText(project.getService(classOf[DbtConsumers]).apiKey); airflow.reset() }
  override def disposeUIResources(): Unit = secret.setText("")
}
