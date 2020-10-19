package io.onfhir.api.client

import akka.http.scaladsl.model.HttpMethods
import io.onfhir.api.FHIR_INTERACTIONS
import io.onfhir.api.model.{FHIRRequest, FHIRResponse}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * Request builder for FHIR search-type interaction
 * @param onFhirClient
 * @param rtype
 * @param count
 */
class FhirSearchRequestBuilder(onFhirClient: IOnFhirClient, rtype:String, count:Option[Int] = None, var page:Option[Int] = None)
  extends FhirSearchLikeRequestBuilder(onFhirClient, FHIRRequest(interaction = FHIR_INTERACTIONS.SEARCH, requestUri = s"${onFhirClient.getBaseUrl()}/$rtype", resourceType = Some(rtype)))
    with IFhirBundleReturningRequestBuilder
  {
  type This = FhirSearchRequestBuilder

  def byHttpPost():FhirSearchRequestBuilder = {
    request.httpMethod = Some(HttpMethods.POST)
    this
  }

  def forCompartment(ctype:String, cid:String):FhirSearchRequestBuilder = {
    request.compartmentType = Some(ctype)
    request.compartmentId = Some(cid)
    this
  }

  override protected def compile(): Unit = {
    super.compile()
    if(page.isDefined)
      request.queryParams = request.queryParams ++ Map("_page" -> List(s"${page.get}"))
    //Also add the compartment as param
    if(count.isDefined)
      request.queryParams = request.queryParams ++ Map("_count" -> List(s"${count.get}"))
  }

  def executeAndReturnBundle()(implicit executionContext: ExecutionContext):Future[FHIRSearchSetBundle] = {
    execute()
        .map(r => {
          if(r.httpStatus.isFailure() || r.responseBody.isEmpty)
            throw FhirClientException("Problem in FHIR search!", Some(r))
          constructBundle(r)
        })
  }

  def toIterator()(implicit executionContext: ExecutionContext):Iterator[Future[FHIRSearchSetBundle]] = {
    new SearchSetIterator(this)
  }

  override def constructBundle(fhirResponse: FHIRResponse): FHIRSearchSetBundle = {
      try {
        new FHIRSearchSetBundle(fhirResponse.responseBody.get, this)
      } catch {
        case e:Throwable =>
          throw FhirClientException("Invalid search result bundle!", Some(fhirResponse))
      }
  }

  override def nextPage():Unit = {
    this.page = Some(this.page.getOrElse(1) + 1)
  }
}

/**
 *
 * @param rb
 * @param executionContext
 */
class SearchSetIterator(rb:FhirSearchRequestBuilder)(implicit executionContext: ExecutionContext) extends Iterator[Future[FHIRSearchSetBundle]] {
  var latestBundle:Option[FHIRSearchSetBundle] = None

  override def hasNext: Boolean = latestBundle.forall(_.hasNext())

  override def next(): Future[FHIRSearchSetBundle] = {
    latestBundle match {
      case None =>
        val temp = rb.executeAndReturnBundle()
        temp onComplete {
          case Success(v) => latestBundle = Some(v)
          case Failure(exception) =>
        }
        temp
      case Some(b) => rb.onFhirClient.next(b)
    }
  }
}
