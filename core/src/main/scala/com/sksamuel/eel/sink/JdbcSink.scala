package com.sksamuel.eel.sink

import java.sql.{DriverManager, ResultSet}

import com.sksamuel.eel.{ResultsetIterator, Writer, Row, Sink}
import com.typesafe.scalalogging.slf4j.StrictLogging

import scala.language.implicitConversions

case class JdbcSink(url: String, table: String, props: JdbcSinkProps = JdbcSinkProps())
  extends Sink
    with StrictLogging {

  override def writer: Writer = new Writer {

    val conn = DriverManager.getConnection(url)
    val tables = ResultsetIterator(conn.getMetaData.getTables(null, null, null, null)).map(_.apply(3).toLowerCase)
    var created = false

    def createTable(row: Row): Unit = {
      if (!created && props.createTable && !tables.contains(table.toLowerCase)) {
        val columns = row.columns.map(c => s"${c.name} VARCHAR").mkString("(", ",", ")")
        val stmt = s"CREATE TABLE $table $columns"
        logger.debug("Creating table [$stmt]")
        conn.createStatement().executeUpdate(stmt)
      }
      created = true
    }

    override def close(): Unit = conn.close()

    override def write(row: Row): Unit = {
      createTable(row)
      val columns = row.columns.map(_.name).mkString(",")
      val values = row.fields.map(_.value).mkString("'", "','", "'")
      val stmt = s"INSERT INTO $table ($columns) VALUES ($values)"
      conn.createStatement().executeUpdate(stmt)
    }
  }
}

case class JdbcSinkProps(createTable: Boolean = false)

