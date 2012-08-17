package scalikejdbc

import org.scalatest._
import org.scalatest.matchers._
import org.scalatest.BeforeAndAfter
import scala.concurrent.ops._
import java.sql.SQLException
import util.control.Exception._

class DB_SQLOperationSpec extends FlatSpec with ShouldMatchers with BeforeAndAfter with Settings {

  val tableNamePrefix = "emp_DB_SQLOp" + System.currentTimeMillis().toString.substring(8)

  behavior of "DB(SQL Operation)"

  it should "be available" in {
    val db = DB(ConnectionPool.borrow())
    db should not be null
  }

  // --------------------
  // readOnly

  it should "execute query in readOnly block" in {
    val tableName = tableNamePrefix + "_queryInReadOnlyBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      db readOnly {
        implicit session =>
          GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(
            enabled = true,
            logLevel = 'info
          )
          val result = SQL("select * from " + tableName + " where name = 'name1' and id = /*'id*/123;")
            .bindByName('id -> 1)
            .map(rs => Some(rs.string("name"))).toList.apply()
          result.size should equal(1)
          GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = false)
      }
    }
  }

  it should "execute query in readOnly session" in {
    val tableName = tableNamePrefix + "_queryInReadOnlySession"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      implicit val session = db.readOnlySession()
      try {
        val result = SQL("select * from " + tableName + "") map (rs => Some(rs.string("name"))) toList () apply ()
        result.size should be > 0
      } finally {
        session.close()
      }
    }
  }

  it should "not execute update in readOnly block" in {
    val tableName = tableNamePrefix + "_cannotUpdateInReadOnlyBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      intercept[SQLException] {
        db readOnly {
          implicit session => SQL("update " + tableName + " set name = ?").bind("xxx").executeUpdate().apply()
        }
      }
    }
  }

  // --------------------
  // autoCommit

  it should "execute query in autoCommit block" in {
    val tableName = tableNamePrefix + "_queryInAutoCommitBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      val result = db autoCommit {
        implicit session =>
          SQL("select * from " + tableName + "").map(rs => Some(rs.string("name"))).toList().apply()
      }
      result.size should be > 0
    }
  }

  it should "execute query in autoCommit session" in {
    val tableName = tableNamePrefix + "_queryInAutoCommitSession"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      implicit val session = db.autoCommitSession()
      try {
        val list = SQL("select id from " + tableName + " order by id").map(rs => rs.int("id")).toList().apply()
        list(0) should equal(1)
        list(1) should equal(2)
      } finally {
        session.close()
      }
    }
  }

  it should "execute single in autoCommit block" in {
    val tableName = tableNamePrefix + "_singleInAutoCommitBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      val result = db autoCommit {
        implicit session =>
          SQL("select id from " + tableName + " where id = ?").bind(1).map(rs => rs.int("id")).toOption().apply()
      }
      result.get should equal(1)
    }
  }

  "single" should "return too many results in autoCommit block" in {
    val tableName = tableNamePrefix + "_tooManyResultsInAutoCommitBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      intercept[TooManyRowsException] {
        db autoCommit {
          implicit session =>
            SQL("select id from " + tableName + "").map(rs => Some(rs.int("id"))).toOption().apply()
        }
      }
    }
  }

  it should "execute single in autoCommit block 2" in {
    val tableName = tableNamePrefix + "_singleInAutoCommitBlock2"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      val extractName = (rs: WrappedResultSet) => rs.string("name")
      val name: Option[String] = db readOnly {
        implicit session =>
          SQL("select * from " + tableName + " where id = ?").bind(1).map(extractName).toOption().apply()
      }
      name.get should be === "name1"
    }
  }

  it should "execute list in autoCommit block" in {
    val tableName = tableNamePrefix + "_listInAutoCommitBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      val result = db autoCommit {
        implicit session =>
          SQL("select id from " + tableName + "").map(rs => Some(rs.int("id"))).toList().apply()
      }
      result.size should equal(2)
    }
  }

  it should "execute foreach in autoCommit block" in {
    val tableName = tableNamePrefix + "_asIterInAutoCommitBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      db autoCommit {
        implicit session =>
          SQL("select id from " + tableName + "").map(rs => rs.int("id")).toTraversable().apply()
            .foreach {
              case (id) => println(id)
            }
      }
    }
  }

  it should "execute update in autoCommit block" in {
    val tableName = tableNamePrefix + "_updateInAutoCommitBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val count = DB(ConnectionPool.borrow()) autoCommit {
        implicit session =>
          SQL("update " + tableName + " set name = ? where id = ?").bind("foo", 1).executeUpdate().apply()
      }
      count should equal(1)
      val name = (DB(ConnectionPool.borrow()) autoCommit {
        implicit session =>
          SQL("select name from " + tableName + " where id = ?").bind(1).map(rs => rs.string("name")).toOption().apply()
      }).get
      name should equal("foo")
    }
  }

  it should "execute update in autoCommit block after readOnly" in {
    val tableName = tableNamePrefix + "_updateInAutoCommitBlockAfterReadOnly"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val name = (DB(ConnectionPool.borrow()) readOnly {
        implicit s =>
          SQL("select name from " + tableName + " where id = ?").bind(1).map(_.string("name")).toOption().apply()
      }).get
      name should equal("name1")
      val count = DB(ConnectionPool.borrow()) autoCommit {
        implicit s =>
          SQL("update " + tableName + " set name = ? where id = ?").bind("foo", 1).executeUpdate().apply()
      }
      count should equal(1)
    }
  }

  // --------------------
  // localTx

  it should "execute single in localTx block" in {
    val tableName = tableNamePrefix + "_singleInLocalTxBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      val result = db localTx {
        implicit s =>
          SQL("select id from " + tableName + " where id = ?").bind(1).map(rs => rs.string("id")).toOption().apply()
      }
      result.get should equal("1")
    }
  }

  it should "execute list in localTx block" in {
    val tableName = tableNamePrefix + "_listInLocalTxBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      val result = db localTx {
        implicit s =>
          SQL("select id from " + tableName + "").map(rs => Some(rs.string("id"))).toList().apply()
      }
      result.size should equal(2)
    }
  }

  it should "execute update in localTx block" in {
    val tableName = tableNamePrefix + "_updateInLocalTxBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val count = DB(ConnectionPool.borrow()) localTx {
        implicit s =>
          SQL("update " + tableName + " set name = ? where id = ?").bind("foo", 1).executeUpdate().apply()
      }
      count should be === 1
      val name = (DB(ConnectionPool.borrow()) localTx {
        implicit s =>
          SQL("select name from " + tableName + " where id = ?").bind(1).map(rs => rs.string("name")).toOption().apply()
      }).getOrElse("---")
      name should equal("foo")
    }
  }

  it should "rollback in localTx block" in {
    val tableName = tableNamePrefix + "_rollbackInLocalTxBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      val count = db localTx {
        implicit s =>
          SQL("update " + tableName + " set name = ? where id = ?").bind("foo", 1).executeUpdate().apply()
      }
      count should be === 1
      db.rollbackIfActive()
      val name = (DB(ConnectionPool.borrow()) localTx {
        implicit s =>
          SQL("select name from " + tableName + " where id = ?").bind(1).map(rs => rs.string("name")).single().apply()
      }).getOrElse("---")
      name should equal("foo")
    }
  }

  // --------------------
  // withinTx

  it should "not execute query in withinTx block before beginning tx" in {
    val tableName = tableNamePrefix + "_queryInWithinTxBeforeBeginningTx"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      intercept[IllegalStateException] {
        db withinTx {
          implicit session =>
            SQL("select * from " + tableName + "").map(rs => Some(rs.string("name"))).list().apply()
        }
      }
    }
  }

  it should "execute query in withinTx block" in {
    val tableName = tableNamePrefix + "_queryInWithinTxBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      db.begin()
      val result = db withinTx {
        implicit session =>
          SQL("select * from " + tableName + "").map(rs => Some(rs.string("name"))).list().apply()
      }
      result.size should be > 0
      db.rollbackIfActive()
    }
  }

  it should "execute query in withinTx session" in {
    val tableName = tableNamePrefix + "_queryInWithinTxSession"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      db.begin()
      implicit val session = db.withinTxSession()
      try {
        val result = SQL("select * from " + tableName + "").map(rs => Some(rs.string("name"))).list().apply()
        result.size should be > 0
        db.rollbackIfActive()
      } finally {
        session.close()
      }
    }
  }

  it should "execute single in withinTx block" in {
    val tableName = tableNamePrefix + "_singleInWithinTxBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      db.begin()
      val result = db withinTx {
        implicit s =>
          SQL("select id from " + tableName + " where id = ?").bind(1).map(rs => rs.string("id")).single().apply()
      }
      result.get should equal("1")
      db.rollbackIfActive()
    }
  }

  it should "execute list in withinTx block" in {
    val tableName = tableNamePrefix + "_listInWithinTxBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      db.begin()
      val result = db withinTx {
        implicit s =>
          SQL("select id from " + tableName + "").map(rs => Some(rs.string("id"))).list().apply()
      }
      result.size should equal(2)
      db.rollbackIfActive()
    }
  }

  it should "execute update in withinTx block" in {
    val tableName = tableNamePrefix + "_updateInWithinTxBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      db.begin()
      val count = db withinTx {
        implicit s =>
          SQL("update " + tableName + " set name = ? where id = ?").bind("foo", 1).executeUpdate().apply()
      }
      count should be === 1
      val name = (db withinTx {
        implicit s =>
          SQL("select name from " + tableName + " where id = ?").bind(1).map(rs => rs.string("name")).single().apply()
      }).get
      name should equal("foo")
      db.rollback()
    }
  }

  it should "rollback in withinTx block" in {
    val tableName = tableNamePrefix + "_rollbackInWithinTxBlock"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      using(DB(ConnectionPool.borrow())) {
        db =>
          db.begin()
          val count = db withinTx {
            implicit s =>
              SQL("update " + tableName + " set name = ? where id = ?").bind("foo", 1).executeUpdate().apply()
          }
          count should be === 1
          db.rollback()
          db.begin()
          val name = (db withinTx {
            implicit s =>
              SQL("select name from " + tableName + " where id = ?").bind(1).map(rs => rs.string("name")).single().apply()
          }).get
          name should equal("name1")
      }
    }
  }

  it should "execute batch in withinTx block" in {
    val tableName = tableNamePrefix + "_batch"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      db.begin()
      val count1 = db withinTx {
        implicit s =>
          val params: Seq[Seq[Any]] = (1001 to 2000).map {
            i => Seq(i, "name" + i.toString)
          }
          SQL("insert into " + tableName + " (id, name) values (?, ?)").batch(params: _*).apply()
      }
      count1.size should equal(1000)
      val count2 = db withinTx {
        implicit s =>
          val params: Seq[Seq[(Symbol, Any)]] = (2001 to 3000).map {
            i =>
              Seq[(Symbol, Any)](
                'id -> i,
                'name -> ("name" + i.toString)
              )
          }
          SQL("insert into " + tableName + " (id, name) values ({id}, {name})").batchByName(params: _*).apply()
      }
      count2.size should equal(1000)
      db.rollback()
    }
  }

  it should "never get stuck" in {
    // Note: This is not an issue. Just only related to how to write specs.
    val tableName = tableNamePrefix + "_gettingstuck"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      val db = DB(ConnectionPool.borrow())
      db.begin()
      implicit val session = db.withinTxSession()

      val params1: Seq[Seq[Any]] = (1001 to 2000).map {
        i => Seq(i, "name" + i.toString)
      }
      val count1 = SQL("insert into " + tableName + " (id, name) values (?, ?)").batch(params1: _*).apply()
      count1.size should equal(1000)

      val params2: Seq[Seq[(Symbol, Any)]] = (2001 to 2003).map {
        i => Seq[(Symbol, Any)]('id -> i, 'name -> ("name" + i.toString))
      }
      try {
        val count2 = SQL("insert into " + tableName + " (id, name) values (?, {name})").batchByName(params2: _*).apply()
        count2.size should equal(1000)
      } catch {
        case e =>
        // Exception should be catched here. It's not a bug.
      }
      db.rollback()
    }
  }

  // --------------------
  // multi threads

  it should "work with multi threads" in {
    val tableName = tableNamePrefix + "_testingWithMultiThreads"
    ultimately(TestUtils.deleteTable(tableName)) {
      TestUtils.initialize(tableName)
      spawn {
        using(DB(ConnectionPool.borrow())) {
          db =>
            db.begin()
            implicit val session = db.withinTxSession()
            SQL("update " + tableName + " set name = ? where id = ?").bind("foo", 1).executeUpdate()
            Thread.sleep(1000L)
            val name = SQL("select name from " + tableName + " where id = ?").bind(1).map(rs => rs.string("name")).single().apply()
            name.get should equal("foo")
            db.rollback()
        }
      }
      spawn {
        using(DB(ConnectionPool.borrow())) {
          db =>
            db.begin()
            implicit val session = db.withinTxSession()
            Thread.sleep(200L)
            val name = SQL("select name from " + tableName + " where id = ?").bind(1).map(rs => rs.string("name")).single().apply()
            name.get should equal("name1")
            db.rollback()
        }
      }

      Thread.sleep(2000L)

      val name = DB(ConnectionPool.borrow()) autoCommit {
        implicit session =>
          SQL("select name from " + tableName + " where id = ?").bind(1).map(rs => rs.string("name")).single.apply()
      }
      name.get should equal("name1")
    }
  }

  it should "solve issue #30" in {
    GlobalSettings.loggingSQLAndTime = new LoggingSQLAndTimeSettings(
      enabled = true,
      logLevel = 'info
    )
    try {
      DB autoCommit { implicit session =>
        try {
          SQL("drop table issue30;").execute.apply()
        } catch { case e => }
        SQL("""
        create table issue30 (
          id bigint not null,
          data1 varchar(255) not null,
          data2 varchar(255) not null
        );""").execute.apply()
        SQL("""insert into issue30 (id, data1, data2) values(?, ?, ?)""").batch(
          (101 to 121) map { i => Seq(i, "a", "b") }: _*
        ).apply()
        SQL("""insert into issue30 (id, data1, data2) values(?, ?, ?)""").batch(
          (201 to 205) map { i => Seq(i, "a", "b") }: _*
        ).apply()
      }
    } finally {
      try {
        DB autoCommit { implicit s =>
          SQL("drop table issue30;").execute.apply()
        }
      } catch { case e => }
      GlobalSettings.loggingSQLAndTime = new LoggingSQLAndTimeSettings()
    }
  }

}
