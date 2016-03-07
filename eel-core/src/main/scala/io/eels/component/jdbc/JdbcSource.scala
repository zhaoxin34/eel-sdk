package io.eels.component.jdbc

import java.sql.DriverManager

import com.sksamuel.scalax.Logging
import com.sksamuel.scalax.io.Using
import io.eels._

import scala.concurrent.duration._

case class JdbcSource(url: String,
                      query: String,
                      props: JdbcSourceProps = JdbcSourceProps(100),
                      providedSchema: Option[Schema] = None) extends Source with Logging with Using {

  override def parts: Seq[Part] = {

    val part = new Part {
      def reader = new SourceReader {

        logger.info(s"Connecting to jdbc source $url...")
        val conn = DriverManager.getConnection(url)
        logger.debug(s"Connected to $url")

        val stmt = conn.createStatement()
        stmt.setFetchSize(props.fetchSize)

        logger.debug(s"Executing query [$query]...")
        val start = System.currentTimeMillis()
        val rs = stmt.executeQuery(query)
        val duration = (System.currentTimeMillis() - start).millis
        logger.info(s" === query completed in $duration ===")

        val dialect = props.dialect.getOrElse(JdbcDialect(url))
        val schema = SchemaBuilder(rs, dialect)
        logger.debug("Fetched schema: ")
        logger.debug(schema.print)

        val columnCount = schema.columns.size

        override def close(): Unit = {
          logger.debug("Closing reader")
          rs.close()
          stmt.close()
          conn.close()
        }

        var created = false

        override def iterator: Iterator[InternalRow] = new Iterator[InternalRow] {
          require(!created, "!Cannot create more than one iterator for a jdbc source!")
          created = true

          override def hasNext: Boolean = {
            val hasNext = rs.next()
            if (!hasNext) {
              logger.debug("Resultset is completed; closing stream")
              close()
            }
            hasNext
          }

          override def next: InternalRow = {
            for (k <- 1 to columnCount) yield {
              rs.getObject(k)
            }
          }
        }
      }
    }

    Seq(part)
  }

  private def fetchSchema: Schema = {
    logger.info(s"Connecting to jdbc source $url...")
    using(DriverManager.getConnection(url)) { conn =>
      logger.debug(s"Connected to $url")

      val stmt = conn.createStatement()

      val schemaQuery = s"SELECT * FROM ($query) tmp WHERE 1=0"
      logger.debug(s"Executing query for schema [$schemaQuery]...")
      val start = System.currentTimeMillis()
      val rs = stmt.executeQuery(query)
      val duration = (System.currentTimeMillis() - start).millis
      logger.info(s" === schema fetch completed in $duration ===")

      val dialect = props.dialect.getOrElse(JdbcDialect(url))
      val schema = SchemaBuilder(rs, dialect)
      rs.close()
      stmt.close()

      logger.debug("Fetched schema: ")
      logger.debug(schema.print)

      schema
    }
  }

  lazy val schema: Schema = providedSchema getOrElse fetchSchema
}

case class JdbcSourceProps(fetchSize: Int, dialect: Option[JdbcDialect] = None)

