package repositories
import java.sql.Timestamp
import javax.inject.{Inject, Singleton}

import entities.{Account, Transaction}
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import slick.jdbc.H2Profile

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class SlickAccountingRepository @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)
                                         (implicit ec: ExecutionContext)
  extends AccountingRepository with HasDatabaseConfigProvider[H2Profile] {
  import profile.api._

  private class AccountTable(tag: Tag) extends Table[Account](tag, "account") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def owner = column[String]("owner")
    def balance = column[Double]("balance")
    def createdDate = column[Timestamp]("created_date")

    override def * = (id.?, owner, balance, createdDate.?) <> ((Account.applyFromDb _).tupled, Account.unapplyToDb)
  }

  private val accounts = TableQuery[AccountTable]

  type OperationRecord = (Option[Long], Option[Long], Option[Long], Double, Timestamp)
  private class OperationTable(tag: Tag) extends Table[OperationRecord](tag, "operation") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def fromId = column[Long]("from_id")
    def toId = column[Long]("to_id")
    def amount = column[Double]("amount")
    def createdDate = column[Timestamp]("created_date")

    def fromFk = foreignKey("from_id", fromId, accounts)(_.id)
    def toFk = foreignKey("to_id", toId, accounts)(_.id)

    override def * = (id.?, fromId.?, toId.?, amount, createdDate)
  }

  private val operations = TableQuery[OperationTable]

  override def save(account: Account): Future[Account] = { // TODO: add update
    val action = (for {
      newId <- accounts.returning(accounts.map(_.id)) += account
    } yield Account(Some(newId), account.owner, account.balance, account.createdDate)).transactionally

    db.run(action)
  }

  override def delete(accountId: Long): Future[_] = db.run(accounts.filter(_.id === accountId).delete.transactionally)


  override def getById(accountId: Long): Future[Option[Account]] =
    db.run(accounts.filter(_.id === accountId).result.headOption)


  override def getByOwner(owner: String): Future[Seq[Account]] =
    db.run(accounts.filter(_.owner === owner).result)

  override def createTransaction(transaction: Transaction): Future[Transaction] = {
    def accountUpdate(account: Account) = {
      //accounts.filter(_.id === from.id).map(_.balance).update(from.balance + transaction.amount)
      val q = for {
        acc <- accounts if acc.id === account.id
      } yield acc.balance
      q.update(account.balance)
    }

    val params = (
      None,
      transaction.from.flatMap(_.id),
      transaction.to.flatMap(_.id),
      transaction.amount,
      new Timestamp(transaction.date.getTime)
    )

    // Define DB actions to update account balances, to be executed after create transaction query
    val updateActions = Seq(transaction.from.map(accountUpdate), transaction.to.map(accountUpdate))
      .filter(_.isDefined)
      .map(_.get)

    val action = {
      for {
        newId <- operations.returning(operations.map(_.id)) += params
        _ <- DBIO.seq(updateActions:_*)
      } yield Transaction(Some(newId), transaction.from, transaction.to, transaction.amount, transaction.date)
    }.transactionally

    db.run(action)
  }

  override def loadTransaction(transactionId: Long): Future[Option[Transaction]] = {
    val transactionQuery = operations.filter(_.id === transactionId)
    val fromQuery = transactionQuery.flatMap(_.fromFk)
    val toQuery = transactionQuery.flatMap(_.toFk)
    val actions = transactionQuery.result.headOption zip (fromQuery.result.headOption zip toQuery.result.headOption)

    db.run(actions).map(r => {
      r._1.map(p => {
        val (id, _, _, amount, date) = p
        Transaction(id, r._2._1, r._2._2, amount, date)
      })
    })
  }
}
