import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{ExceptionHandler, Route}
import akka.stream.ActorMaterializer
import datadog.trace.agent.test.base.HttpServerTest
import datadog.trace.agent.test.base.HttpServerTest.ServerEndpoint._
import groovy.lang.Closure

import scala.concurrent.Await

object AkkaHttpTestWebServer {
  implicit val system = ActorSystem("my-system")
  implicit val materializer = ActorMaterializer()

  val exceptionHandler = ExceptionHandler {
    case ex: Exception => complete(HttpResponse(status = EXCEPTION.getStatus).withEntity(ex.getMessage))
  }

  val route = {
    extractRequest { req =>
      val endpoint = HttpServerTest.ServerEndpoint.forPath(req.uri.path.toString())
      handleExceptions(exceptionHandler) {
        complete(HttpServerTest.controller(endpoint, new Closure[HttpResponse](()) {
          def doCall(): HttpResponse = {
            val resp = HttpResponse(status = endpoint.getStatus) //.withHeaders(headers.Type)resp.contentType = "text/plain"
            endpoint match {
              case SUCCESS => resp.withEntity(endpoint.getBody)
              case QUERY_PARAM => resp.withEntity(req.uri.queryString().orNull)
              case REDIRECT => resp.withHeaders(headers.Location(endpoint.getBody))
              case ERROR => resp.withEntity(endpoint.getBody)
              case EXCEPTION => throw new Exception(endpoint.getBody)
              case _ => HttpResponse(status = NOT_FOUND.getStatus).withEntity(NOT_FOUND.getBody)
            }
          }
        }))
      }
    }
  }

  private var binding: ServerBinding = null

  def start(port: Int): Unit = synchronized {
    if (null == binding) {
      import scala.concurrent.duration._
      binding = Await.result(Http().bindAndHandle(Route.handlerFlow(route), "localhost", port), 10 seconds)
    }
  }

  def stop(): Unit = synchronized {
    if (null != binding) {
      binding.unbind()
      system.terminate()
      binding = null
    }
  }
}
