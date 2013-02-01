package spray.can.server
package websockets

import spray.io._

import spray.http._
import java.security.MessageDigest

import spray.can.server.ServerSettings
import spray.io.Connection
import akka.actor.{Props, ActorRef}
import concurrent.duration._
import spray.io.IOConnection._
import spray.http.HttpHeaders.RawHeader
import spray.http.HttpResponse
import akka.util.ByteString

/**
 * Just like a HttpServer, but with
 *
 * - frameHandler to decide who handles the incoming websocket frames after upgrade
 * - frameSizeLimit to prevent ginormous frames from causing crashes
 * - an auto-ping interval to keep sending pings.
 *
 * It behaves identically to a normal HttpServer until the httpHandler
 * responds with an Upgrade message. It then creates/finds an actor to
 * handle the websocket frames using frameHandler, completes the websocket
 * handshake, and lets the websocket frames start flowing
 */
class SocketServer(httpHandler: MessageHandler,
                   frameHandler: Any => ActorRef,
                   settings: ServerSettings = ServerSettings(),
                   frameSizeLimit: Long = 1024 * 1024,
                   autoPingInterval: Duration = 1 second)
                  (implicit sslEngineProvider: ServerSSLEngineProvider)
                   extends HttpServer(httpHandler, settings) {

  override def createConnectionActor(connection: Connection) = {
    context.actorOf(Props(new DefaultIOConnectionActor(connection, pipelineStage)), nextConnectionActorName)
  }

  import settings.{StatsSupport => _, _}
  override val pipelineStage =
    Switching(
      ServerFrontend(settings, httpHandler, timeoutResponse) >>
      RequestChunkAggregation(RequestChunkAggregationLimit.toInt) ? (RequestChunkAggregationLimit > 0) >>
      PipeliningLimiter(100) ? (PipeliningLimit > 0) >>
      StatsSupport(statsHolder.get) ? settings.StatsSupport >>
      RemoteAddressHeaderSupport() ? RemoteAddressHeader >>
      RequestParsing(ParserSettings, VerboseErrorMessages) >>
      ResponseRendering(settings) >>
      ConnectionTimeouts(IdleTimeout) ? (ReapingCycle > 0 && IdleTimeout > 0),
      (upgradeMsg: Any) =>
        WebsocketFrontEnd(frameHandler(upgradeMsg)) >>
        Consolidation(frameSizeLimit) >>
        FrameParsing(frameSizeLimit)
    ) >>
    SslTlsSupport(sslEngineProvider) ? SSLEncryption >>
    TickGenerator(ReapingCycle) ? (ReapingCycle > 0 && (IdleTimeout > 0 || RequestTimeout > 0))

}

/**
 * Convenience building blocks to deal with the websocket upgrade
 * request (doing the calculate-hash-dance, headers, blah blah)
 */
object SocketServer{

  def apply(acceptHandler: MessageHandler,
            frameHandler: Any => ActorRef,
            settings: ServerSettings = ServerSettings(),
            frameSizeLimit: Long = 1024 * 1024,
            autoPingInterval: Duration = 1 second)
           (implicit sslEngineProvider: ServerSSLEngineProvider): SocketServer = {
    new SocketServer(acceptHandler,  frameHandler, settings, frameSizeLimit, autoPingInterval)
  }

  def calculateReturnHash(headers: List[HttpHeader]) = {
    headers.collectFirst{
      case RawHeader("sec-websocket-key", value) => (value + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes("UTF-8")
    }.map(MessageDigest.getInstance("SHA-1").digest)
      .map(new sun.misc.BASE64Encoder().encode)
  }

  def socketAcceptHeaders(returnValue: String) = List(
    HttpHeaders.RawHeader("Upgrade", "websocket"),
    HttpHeaders.RawHeader("Connection", "Upgrade"),
    HttpHeaders.RawHeader("Sec-WebSocket-Accept", returnValue)
  )

  def acceptAllFunction(x: HttpRequest) = {
    HttpResponse(
      StatusCodes.SwitchingProtocols,
      headers = socketAcceptHeaders(calculateReturnHash(x.headers).get)
    )
  }
}

