package bug.serverlayer

import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpMethods.PUT
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.StatusCodes
import akka.stream.ActorMaterializer
import akka.stream.io.SendBytes
import akka.stream.io.SessionBytes
import akka.stream.io.SslTlsInbound
import akka.stream.io.SslTlsOutbound
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Keep
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.testkit.TestPublisher
import akka.stream.testkit.TestSubscriber
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.testkit.scaladsl.TestSource
import akka.testkit.TestKit
import akka.util.ByteString

import javax.net.ssl.SSLContext
import org.scalatest.FlatSpecLike
import org.scalatest.Matchers
import org.scalatest.Suite
import org.scalatest.concurrent.ScalaFutures
import org.testng.annotations.Test

/** Tests the Http().serverLayer used as an HTTP parser. */
class ServerLayerTest extends TestKit(ActorSystem("test-system"))
  with Suite with FlatSpecLike with Matchers with ScalaFutures {

  implicit val materializer = ActorMaterializer()
  implicit val duration = 2000.millis

  // HTTP request split into three ByteStrings
  val requestA: ByteString = ByteString(
    "PUT / HTTP/1.1\r\n" +
      "Host: localhost\r\n" +
      "Content-Type: text/plain\r\n" +
      "Content-Length: 9" +
      "\r\n\r\n" +
      "abc")
  val requestB = ByteString("def")
  val requestC = ByteString("ghi")

  // HTTP response to the request
  val response = HttpResponse(StatusCodes.OK)

  // Test sources and sinks
  val httpSource: Source[HttpResponse, TestPublisher.Probe[HttpResponse]] = TestSource.probe[HttpResponse]
  val httpSink: Sink[HttpRequest, TestSubscriber.Probe[HttpRequest]] = TestSink.probe[HttpRequest]
  val byteSource: Source[ByteString, TestPublisher.Probe[ByteString]] = TestSource.probe[ByteString]
  val byteSink: Sink[ByteString, TestSubscriber.Probe[ByteString]] = TestSink.probe[ByteString]

  /* HttpResponse ~> +-------------+ ~> SslTlsOutbound
   *                 | serverLayer |
   *  HttpRequest <~ +-------------+ <~ SslTlsInbound
   */
  val serverLayer: BidiFlow[HttpResponse, SslTlsOutbound, SslTlsInbound, HttpRequest, Unit] =
    Http().serverLayer

  /* SslTlsOutbound ~> +-------+ ~> ByteString
   *                   | stage |
   *  SslTlsInbound <~ +-------+ <~ ByteString
   */
  val stage: BidiFlow[SslTlsOutbound, ByteString, ByteString, SslTlsInbound, Unit] = {
    val session = SSLContext.getDefault.createSSLEngine.getSession
    val top = Flow[SslTlsOutbound].collect {
      case SendBytes(data) => data
    }
    val bottom = Flow[ByteString].map(SessionBytes(session, _))
    BidiFlow.wrap(top, bottom)(Keep.none)
  }

  /** Tests sending a request through the server layer
    * and then sending a response back.
    */
  @Test(groups = Array("unit"))
  def testServerLayer(): Unit = {

    /* httpSource ~> +-------------+ ~> +-------+ ~> byteSink
     *               | serverLayer |    | stage |
     *   httpSink <~ +-------------+ <~ +-------+ <~ byteSource
     */

    val runnable =
      httpSource
        .viaMat(
          serverLayer
            .atop(stage)
            .joinMat(
              Flow.wrap(byteSink, byteSource)(Keep.both))(Keep.right))(Keep.both)
        .toMat(httpSink) {
          case ((httpPub, (byteSub, bytePub)), httpSub) => ((httpPub, httpSub), (bytePub, byteSub))
        }

    val ((httpPub, httpSub), (bytePub, byteSub)) = runnable.run()

    // Send the first part of the request
    bytePub.sendNext(requestA)

    // Receive the request and parse the headers
    httpSub.request(10)
    val receivedRequest = httpSub.expectNext()
    receivedRequest.method should equal(PUT)

    // Send the remainder of the request and complete
    bytePub.sendNext(requestB)
    bytePub.sendNext(requestC)
    bytePub.sendComplete()

    // Drain the complete data from the received request
    val requestFuture = receivedRequest.entity.toStrict(duration)
    val requestData = whenReady(requestFuture) {
      case strict => strict.data
    }
    requestData.utf8String should equal("abcdefghi")

    // Send the response back
    httpPub.sendNext(response)

    // Receive the response
    byteSub.request(10)
    val receivedResponse = byteSub.expectNext()
    println("Received response:\n" + receivedResponse.utf8String)
  }

}
