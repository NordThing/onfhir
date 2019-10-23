package io.onfhir.db

import java.util.concurrent.TimeUnit

import io.onfhir.config.OnfhirConfig
import org.mongodb.scala.{ClientSession, Completed, MongoException, Observable, ScalaClientSession, SingleObservable}

import scala.concurrent.duration.{Duration, FiniteDuration}
import scala.concurrent.{Await, Future}

/**
  * Transaction Session object for FHIR transactions
  */
class TransactionSession(val transactionId:String) {
  /**
    * Mongo Database transaction session
    */
  val dbSession:ClientSession = {
    val clientSession = Await.result(MongoDB.createSession().head(), FiniteDuration.apply(OnfhirConfig.fhirRequestTimeout.toMinutes, TimeUnit.MINUTES))
    clientSession.startTransaction(MongoDB.transactionOptions)
    clientSession
  }

  /**
    * Run and commit the transaction
    * @return
    */
  def commit():Future[Completed] = {
    val commitTransactionObservable: SingleObservable[Completed] = dbSession.commitTransaction()
    val commitAndRetryObservable: SingleObservable[Completed] = commitAndRetry(commitTransactionObservable)
    runTransactionAndRetry(commitAndRetryObservable).head()
  }

  /**
    * Abort the transaction
    * @return
    */
  def abort():Future[Completed] = {
    dbSession.abortTransaction().head()
  }

  /**
    * Commit transaction and retry if not successfull
    * @param observable
    * @return
    */
  private def commitAndRetry(observable: SingleObservable[Completed]): SingleObservable[Completed] = {
    observable.recoverWith({
      case e: MongoException if e.hasErrorLabel(MongoException.UNKNOWN_TRANSACTION_COMMIT_RESULT_LABEL) => {
        println("UnknownTransactionCommitResult, retrying commit operation ...")
        commitAndRetry(observable)
      }
      case e: Exception => {
        println(s"Exception during commit ...: $e")
        throw e
      }
    })
  }

  private def runTransactionAndRetry(observable: SingleObservable[Completed]): SingleObservable[Completed] = {
    observable.recoverWith({
      case e: MongoException if e.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) => {
        println("TransientTransactionError, aborting transaction and retrying ...")
        runTransactionAndRetry(observable)
      }
    })
  }
}
