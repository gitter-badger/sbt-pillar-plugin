package io.ino.sbtpillar

import com.datastax.driver.core.Cluster.Builder
import com.datastax.driver.core.querybuilder.QueryBuilder._
import com.datastax.driver.core.{ConsistencyLevel, QueryOptions}
import sbt.Keys._
import sbt._

import scala.collection.JavaConverters._
import scala.util.Try

object Plugin extends sbt.AutoPlugin {

  object PillarKeys {
    val createKeyspace = taskKey[Unit]("Create keyspace.")
    val dropKeyspace = taskKey[Unit]("Drop keyspace.")
    val migrate = taskKey[Unit]("Run pillar migrations.")
    val cleanMigrate = taskKey[Unit]("Recreate keyspace and run pillar migrations.")

    val pillarConfigFile = settingKey[File]("Path to the configuration file holding the cassandra uri")
    val pillarConfigKey = settingKey[String]("Configuration key storing the cassandra url")
    val pillarDefaultConsistencyLevelConfigKey = settingKey[String]("Configuration key storing the consistency level for the session")
    val pillarReplicationStrategyConfigKey = settingKey[String]("Configuration key storing the replication strategy to create keyspaces with")
    val pillarReplicationFactorConfigKey = settingKey[String]("Configuration key storing the replication factor to create keyspaces with")
    val pillarDatacenterNamesConfigKey = settingKey[String]("Configuration key storing a list of datacenter names required when using NetworkTopologyStrategy")
    val pillarMigrationsDir = settingKey[File]("Path to the directory holding migration files")
    val pillarExtraMigrationsDirs = settingKey[Seq[File]]("List of paths to directories holding extra migration files, applied after files from `pillarMigrationsDir`")
  }

  import Pillar.{withCassandraUrl, withSession}
  import PillarKeys._
  import com.datastax.driver.core.Session

  private def taskSettings: Seq[sbt.Def.Setting[_]] = Seq(
    createKeyspace := {
      val log = streams.value.log
      withCassandraUrl(pillarConfigFile.value, pillarConfigKey.value,
        pillarReplicationStrategyConfigKey.value, pillarReplicationFactorConfigKey.value,
        pillarDefaultConsistencyLevelConfigKey.value,
        pillarDatacenterNamesConfigKey.value,
        log) { (url, replicationStrategy, replicationFactor, defaultConsistencyLevel, datacenterNames) =>
        log.info(s"Creating keyspace ${url.keyspace} at ${url.hosts.head}:${url.port}")
        Pillar.initialize(replicationStrategy, replicationFactor, url, datacenterNames, log)
      }
    },
    dropKeyspace := {
      val log = streams.value.log
      withCassandraUrl(pillarConfigFile.value, pillarConfigKey.value,
        pillarReplicationStrategyConfigKey.value, pillarReplicationFactorConfigKey.value,
        pillarDefaultConsistencyLevelConfigKey.value,
        pillarDatacenterNamesConfigKey.value,
        log) { (url, replicationStrategy, replicationFactor, defaultConsistencyLevel, datacenterNames) =>
        log.info(s"Dropping keyspace ${url.keyspace} at ${url.hosts.head}:${url.port}")
        Pillar.destroy(url, log)
      }
    },
    migrate := {
      val log = streams.value.log
      withCassandraUrl(pillarConfigFile.value, pillarConfigKey.value,
        pillarReplicationStrategyConfigKey.value, pillarReplicationFactorConfigKey.value,
        pillarDefaultConsistencyLevelConfigKey.value,
        pillarDatacenterNamesConfigKey.value,
        log) { (url, replicationStrategy, replicationFactor, defaultConsistencyLevel, datacenterNames) =>
        val migrationsDirs = pillarMigrationsDir.value +: pillarExtraMigrationsDirs.value
        log.info(
          s"Migrating keyspace ${url.keyspace} at ${url.hosts.head}:${url.port} using migrations in [${migrationsDirs.mkString(",")}] with consistency $defaultConsistencyLevel")
        Pillar.migrate(migrationsDirs, url, defaultConsistencyLevel, log)
      }
    },
    cleanMigrate := {
      val log = streams.value.log
      withCassandraUrl(pillarConfigFile.value, pillarConfigKey.value,
        pillarReplicationStrategyConfigKey.value, pillarReplicationFactorConfigKey.value,
        pillarDefaultConsistencyLevelConfigKey.value,
        pillarDatacenterNamesConfigKey.value,
        log) { (url, replicationStrategy, replicationFactor, defaultConsistencyLevel, datacenterNames) =>
        val host = url.hosts.head

        withSession(url, Some(defaultConsistencyLevel), log) { (url, session) =>
          log.info(s"Dropping keyspace ${url.keyspace} at $host:${url.port}")
          session.execute(s"DROP KEYSPACE IF EXISTS ${url.keyspace}")

          Pillar.checkPeerSchemaVersions(session, log)

          log.info(s"Creating keyspace ${url.keyspace} at $host:${url.port}")
          Pillar.initialize(session, replicationStrategy, replicationFactor, url, datacenterNames)

          val dirs = pillarMigrationsDir.value +: pillarExtraMigrationsDirs.value
          log.info(
            s"Migrating keyspace ${url.keyspace} at $host:${url.port} using migrations in [${dirs.mkString(",")}] with consistency $defaultConsistencyLevel")
          Pillar.migrate(session, dirs, url)
        }

      }
    },
    pillarConfigKey := "cassandra.url",
    pillarReplicationStrategyConfigKey := "cassandra.replicationStrategy",
    pillarReplicationFactorConfigKey := "cassandra.replicationFactor",
    pillarDefaultConsistencyLevelConfigKey := "cassandra.defaultConsistencyLevel",
    pillarDatacenterNamesConfigKey := "cassandra.datacenters",
    pillarConfigFile := file("conf/application.conf"),
    pillarMigrationsDir := file("conf/migrations"),
    pillarExtraMigrationsDirs := Seq()
  )

  def pillarSettings: Seq[sbt.Def.Setting[_]] = inConfig(Test)(taskSettings) ++ taskSettings

  private case class CassandraUrl(hosts: Seq[String], port: Int, keyspace: String, cassandraCredentials: Option[CassandraCredentials] = None)

  private case class CassandraCredentials(username: String, password: String)

  private object Pillar {

    import java.nio.file.Files

    import com.datastax.driver.core.Cluster
    import com.typesafe.config.ConfigFactory
    import de.kaufhof.pillar._

    import scala.util.control.NonFatal

    private val DEFAULT_DEFAULT_CONSISTENCY_LEVEL = ConsistencyLevel.QUORUM
    private val DEFAULT_REPLICATION_STRATEGY = "SimpleStrategy"
    private val DEFAULT_REPLICATION_FACTOR = 3

    def withCassandraUrl(configFile: File,
                         configKey: String,
                         repStrategyConfigKey: String,
                         repFactorConfigKey: String,
                         defaultConsistencyLevelConfigKey: String,
                         pillarDatacenterNamesConfigKey: String,
                         logger: Logger)(block: (CassandraUrl, String, Int, ConsistencyLevel, List[String]) => Unit): Unit = {
      val configFileMod = file(sys.env.getOrElse("PILLAR_CONFIG_FILE", configFile.getAbsolutePath))
      logger.info(s"Reading config from ${configFileMod.getAbsolutePath}")
      val config = ConfigFactory.parseFile(configFileMod).resolve()
      val urlString = config.getString(configKey)
      val url = parseUrl(urlString)

      val defaultConsistencyLevel = Try(ConsistencyLevel.valueOf(config.getString(defaultConsistencyLevelConfigKey))).getOrElse(DEFAULT_DEFAULT_CONSISTENCY_LEVEL)
      val replicationStrategy = Try(config.getString(repStrategyConfigKey)).getOrElse(DEFAULT_REPLICATION_STRATEGY)
      val replicationFactor = Try(config.getInt(repFactorConfigKey)).getOrElse(DEFAULT_REPLICATION_FACTOR)
      val datacenterNames = Try(config.getStringList(pillarDatacenterNamesConfigKey).asScala).getOrElse(List.empty).to[List]
      try {
        block(url, replicationStrategy, replicationFactor, defaultConsistencyLevel, datacenterNames)
      } catch {
        case NonFatal(e) =>
          logger.error(s"An error occurred while performing task: $e")
          logger.trace(e)
          throw e
      }
    }

    def withSession(url: CassandraUrl, defaultConsistencyLevel: Option[ConsistencyLevel], logger: Logger)
                   (block: (CassandraUrl, Session) => Unit): Unit = {

      implicit val iLog: sbt.Logger = logger

      val queryOptions = new QueryOptions()
      defaultConsistencyLevel.foreach(queryOptions.setConsistencyLevel)
      val defaultBuilder: Builder = new Builder()
        .addContactPointsSafe(url.hosts.toArray: _*)
        .withPort(url.port)
        .withQueryOptions(queryOptions)

      val cluster: Cluster = url.cassandraCredentials.foldLeft(defaultBuilder) {
        case (builder, cred) => builder.withCredentials(cred.username, cred.password)
      }.build

      try {
        val session = cluster.connect
        block(url, session)
      } catch {
        case NonFatal(e) =>
          logger.error(s"An error occurred while performing task: $e")
          logger.trace(e)
          throw e
      } finally {
        cluster.closeAsync()
      }
    }

    def initialize(replicationStrategy: String, replicationFactor: Int, url: CassandraUrl, datacenterNames: List[String], logger: Logger): Unit = {
      withSession(url, None, logger) { (url, session) =>
        initialize(session, replicationStrategy, replicationFactor, url, datacenterNames)
      }
    }

    def initialize(session: Session, replicationStrategy: String, replicationFactor: Int, url: CassandraUrl, datacenterNames: List[String]) {
      val replStrategy = replicationStrategy match {
        case "SimpleStrategy" => SimpleStrategy(replicationFactor = replicationFactor)
        case "NetworkTopologyStrategy" => NetworkTopologyStrategy(datacenterNames.map(CassandraDataCenter(_, replicationFactor)))
      }
      Migrator(Registry(Seq.empty), CassandraMigrator.appliedMigrationsTableNameDefault).initialize(session, url.keyspace, replStrategy)
    }

    def destroy(url: CassandraUrl, logger: Logger): Unit = {
      withSession(url, None, logger) { (url, session) =>
        Migrator(Registry(Seq.empty), CassandraMigrator.appliedMigrationsTableNameDefault).destroy(session, url.keyspace)
      }
    }

    def migrate(migrationsDirs: Seq[File], url: CassandraUrl, defaultConsistencyLevel: ConsistencyLevel, logger: Logger): Unit = {
      withSession(url, Some(defaultConsistencyLevel), logger) { (url, session) =>
        migrate(session, migrationsDirs, url)
      }
    }

    def migrate(session: Session, migrationsDirs: Seq[File], url: CassandraUrl): Unit = {
      val registry = Registry(migrationsDirs.flatMap(loadMigrations))
      session.execute(s"USE ${url.keyspace}")
      Migrator(registry, CassandraMigrator.appliedMigrationsTableNameDefault).migrate(session)
    }

    def checkPeerSchemaVersions(session: Session, logger: Logger): Unit = {
      import scala.collection.JavaConverters._
      val schemaByPeer = session.execute(select("peer", "schema_version").from("system", "peers")).all().asScala.map { row =>
        (row.getInet("peer"), row.getUUID("schema_version"))
      }.toMap

      if (schemaByPeer.values.toSet.size > 1) {
        val peerSchemaVersions = schemaByPeer.map { case (peer, schemaVersion) => s"peer: $peer, schema_version: $schemaVersion" }.mkString("\n")
        logger.warn(s"There are peers with different schema versions:\n$peerSchemaVersions")
      }
    }

    private def parseUrl(urlString: String): CassandraUrl = {
      val uri = new URI(urlString)
      val additionalHosts = Option(uri.getQuery) match {
        case Some(query) => query.split('&').map(_.split('=')).filter(param => param(0) == "host").map(param => param(1)).toSeq
        case None => Seq.empty
      }
      Option(uri.getUserInfo).map(_.split(':')) match {
        case Some(Array(user, pass)) => CassandraUrl(Seq(uri.getHost) ++ additionalHosts, uri.getPort, uri.getPath.substring(1), Some(CassandraCredentials(user, pass)))
        case _ => CassandraUrl(Seq(uri.getHost) ++ additionalHosts, uri.getPort, uri.getPath.substring(1))
      }
    }

    private def loadMigrations(migrationsDir: File): List[Migration] = {
      val parser = de.kaufhof.pillar.Parser()
      val files = Path.allSubpaths(migrationsDir).map(_._1).filterNot(f => f.isDirectory || f.getName.head == '.').toSeq
      if (files.nonEmpty) {
        files.map { file =>
          val in = Files.newInputStream(file.toPath)
          try {
            parser.parse(in)
          } finally {
            in.close()
          }
        }.toList
      } else {
        throw new IllegalArgumentException("The pillarMigrationsDir does not contain any migration files - wrong configuration?")
      }
    }

    private implicit class RichClusterBuilder(builder: Cluster.Builder) {

      /** Add contact points ignoring errors for single contact points.
        * Cluster.Builder.addContactPoints fails/throws as soon as a single node/host address cannot be resolved via InetAddress.
        */
      def addContactPointsSafe(addresses: String*)(implicit logger: Logger): Cluster.Builder = {

        require(addresses.nonEmpty, "At least 1 contact point must be specified.")

        val (built, exceptions) = addresses.foldLeft((builder, List.empty[Throwable])) { case ((b, es), address) =>
          try {
            (b.addContactPoint(address), es)
          } catch {
            case e: Throwable =>
              logger.warn(s"Failed to add contact point $address: $e")
              (b, e :: es)
          }
        }

        if (exceptions.length == addresses.length) {
          logger.error(s"All contact points failed on addContactPoint, rethrowing exception for last contact point.")
          throw exceptions.head
        }

        built
      }

    }

  }

}
