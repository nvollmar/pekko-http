/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.http.impl.engine.client

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

import org.apache.pekko
import pekko.Done
import pekko.actor.ActorSystem
import pekko.http.impl.util._
import pekko.http.impl.engine.client.PoolFlow.{ RequestContext, ResponseContext }
import pekko.http.impl.engine.client.pool.NewHostConnectionPool
import pekko.http.impl.engine.ws.ByteStringSinkProbe
import pekko.http.scaladsl.{ ClientTransport, ConnectionContext, Http }
import pekko.http.scaladsl.Http.ServerBinding
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.model.headers.Host
import pekko.http.scaladsl.settings.{ ClientConnectionSettings, ConnectionPoolSettings }
import pekko.stream._
import pekko.stream.scaladsl.{ BidiFlow, Flow, GraphDSL, Keep, Sink, Source, TLSPlacebo }
import pekko.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import pekko.stream.testkit.{ TestPublisher, TestSubscriber }
import pekko.testkit._
import pekko.util.ByteString
import org.reactivestreams.{ Publisher, Subscriber }
import org.scalatest.concurrent.Eventually
import org.scalatest.concurrent.PatienceConfiguration.Timeout

import scala.concurrent.{ Await, Future, Promise }
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Try

/**
 * Tests the host connection pool infrastructure.
 *
 * Right now it tests against various stacks with various depths. It's debatable whether it should actually be tested
 * against plain network bytes instead to show interaction on the HTTP protocol level instead of against the server
 * API level.
 */
class HostConnectionPoolSpec extends PekkoSpecWithMaterializer(
      """
     pekko.actor {
       serialize-creators = off
       serialize-messages = off
       default-dispatcher.throughput = 100
     }
     pekko.http.client.log-unencrypted-network-bytes = 200
     pekko.http.server.log-unencrypted-network-bytes = 200
  """) with Eventually {
  lazy val singleElementBufferMaterializer = materializer
  val defaultSettings =
    ConnectionPoolSettings(system)
      .withMaxConnections(1)

  trait ClientServerImplementation {

    /** Returns a client / server implementation that include the kill switch flow in the middle */
    def get(connectionKillSwitch: SharedKillSwitch)
        : BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, Future[Http.OutgoingConnection]]

    /**
     * Specifies if the transport implementation will fail the handler request input side if an error is encountered
     * at the response output side.
     *
     * I haven't decided yet what the right behavior should be.
     */
    def failsHandlerInputWhenHandlerOutputFails: Boolean
  }

  testSet(clientServerImplementation = PassThrough)
  testSet(clientServerImplementation = PekkoHttpEngineNoNetwork)
  testSet(clientServerImplementation = PekkoHttpEngineTCP)
  // testSet(poolImplementation = NewPoolImplementation, clientServerImplementation = PekkoHttpEngineTLS)

  def testSet(clientServerImplementation: ClientServerImplementation) =
    s"NewPoolImplementation on $clientServerImplementation" should {
      implicit class EnhancedIn(name: String) {
        def inWithShutdown(body: => TestSetup): Unit = name in {
          val res = body
          Try(res.shutdown())
        }
      }

      "complete a simple request/response cycle with a strict request and response".inWithShutdown(
        new SetupWithServerProbes {
          pushRequest(HttpRequest(uri = "/simple"))

          val conn1 = expectNextConnection()
          val req = conn1.expectRequest()
          conn1.pushResponse(HttpResponse(entity = req.uri.path.toString))
          expectResponseEntityAsString() shouldEqual "/simple"
        })
      "complete a simple request/response cycle with a chunked request and response".inWithShutdown(
        new SetupWithServerProbes {
          val reqBody = Source(("Hello" :: " World" :: Nil).map(ByteString.apply))
          pushRequest(HttpRequest(uri = "/simple",
            entity = HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`, reqBody)))

          val conn1 = expectNextConnection()
          val HttpRequest(_, _, _, reqEntityIn: HttpEntity.Chunked, _) = conn1.expectRequest()
          reqEntityIn.dataBytes.runFold(ByteString.empty)(_ ++ _).awaitResult(
            3.seconds).utf8String shouldEqual "Hello World"

          val resBodyOut = conn1.pushChunkedResponse()
          val resBodyIn = expectChunkedResponseBytesAsProbe()

          resBodyOut.sendNext(ByteString("hi"))
          resBodyIn.expectUtf8EncodedString("hi")

          resBodyOut.sendComplete()
          resBodyIn.request(1) // FIXME: should we support eager completion here? (reason is substreamHandler in PrepareResponse)
          resBodyIn.expectComplete()
        })
      "complete a request/response cycle with a chunked request and response with dependent entity bytes".inWithShutdown(
        new SetupWithServerProbes {
          val reqBody = Source(("Hello" :: " World" :: Nil).map(ByteString.apply))
          pushRequest(HttpRequest(uri = "/simple",
            entity = HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`, reqBody)))

          val conn1 = expectNextConnection()
          val HttpRequest(_, _, _, reqEntityIn: HttpEntity.Chunked, _) = conn1.expectRequest()

          // response data stream is bound to request data stream
          val respDataBytes = reqEntityIn.dataBytes.map(_.map(_.toChar.toUpper.toByte))
          conn1.pushResponse(HttpResponse().withEntity(
            HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`, respDataBytes)))

          val resBodyIn = expectChunkedResponseBytesAsProbe()

          resBodyIn.expectUtf8EncodedString("HELLO")
          resBodyIn.expectUtf8EncodedString(" WORLD")

          resBodyIn.request(1) // FIXME: should we support eager completion here? (reason is substreamHandler in PrepareResponse)
          resBodyIn.expectComplete()
        })
      "not crash slot when connection is aborted after an early response while waiting for rest of request entity".inWithShutdown(
        new SetupWithServerProbes {
          // #1439
          val reqEntityProbe = pushChunkedRequest()
          responseOut.request(1)

          val conn1 = expectNextConnection()
          val reqInProbe = conn1.expectChunkedRequestBytesAsProbe()

          // request coming in
          reqEntityProbe.sendNext(ByteString("test"))
          reqInProbe.expectUtf8EncodedString("test")

          // ...
          reqEntityProbe.sendNext(ByteString("test2"))
          reqInProbe.expectUtf8EncodedString("test2")

          conn1.failHandler(new RuntimeException("test"))

          // In #1439, the next request now ran into a broken slot and the request is dropped to the floor
          pushRequest()
          val conn2 = expectNextConnection()
          conn2.expectRequest()
          conn2.pushResponse()
          expectResponse()
        })
      "open up to max-connections when enough requests are pending".inWithShutdown(
        new SetupWithServerProbes(_.withMaxConnections(2)) {
          pushRequest(HttpRequest(uri = "/1"))
          val conn1 = expectNextConnection()
          conn1.expectRequestToPath("/1")

          pushRequest(HttpRequest(uri = "/2"))
          val conn2 = expectNextConnection()
          conn2.expectRequestToPath("/2")

          pushRequest(HttpRequest(uri = "/3"))
          conn1.pushResponse(HttpResponse())
          expectResponse()
          conn1.expectRequestToPath("/3")
        })
      "only buffer a reasonable number of extra requests" in pending
      "only send next request when last response entity was read completely".inWithShutdown(
        new SetupWithServerProbes() {
          pushRequest(HttpRequest(uri = "/chunked-1"))
          pushRequest(HttpRequest(uri = "/2"))
          val conn1 = expectNextConnection()
          conn1.expectRequestToPath("/chunked-1")
          // FIXME: expectNoNewConnection()
          // conn1.expectNoRequest()

          val resp1BytesOut = conn1.pushChunkedResponse()
          val resp1BytesIn = expectChunkedResponseBytesAsProbe()
          resp1BytesOut.sendNext(ByteString("test"))
          resp1BytesIn.expectUtf8EncodedString("test")

          // FIXME: expectNoNewConnection()
          // conn1.expectNoRequest()

          resp1BytesOut.sendComplete()
          resp1BytesIn.request(1) // FIXME: should we support eager completion here?
          resp1BytesIn.expectComplete()

          conn1.expectRequestToPath("/2")
        })
      if (clientServerImplementation != PassThrough) {
        // doesn't work on PassThrough as entity is decorated by test infra
        "not get stuck on HEAD requests if payload is never subscribed".inWithShutdown(
          new SetupWithServerProbes(_.withResponseEntitySubscriptionTimeout(Duration.Inf).withMaxConnections(1)) {
            pushRequest(HttpRequest(method = HttpMethods.HEAD, uri = "/head"))
            pushRequest(HttpRequest(uri = "/2"))
            val conn1 = expectNextConnection()
            conn1.expectRequestToPath("/head")

            conn1.pushResponse(HttpResponse(entity = HttpEntity.Default(ContentTypes.`application/octet-stream`, 100,
              Source.empty)))
            val res = expectResponse()
            res.entity.contentLengthOption.get shouldEqual 100

            // HEAD requests do not require to consume entity

            // immediately expect next request
            conn1.expectRequestToPath("/2")
            conn1.pushResponse()
            expectResponse().status shouldEqual StatusCodes.OK
          })
        "not fail if HEAD request entity is subscribed".inWithShutdown(
          new SetupWithServerProbes(_.withResponseEntitySubscriptionTimeout(Duration.Inf).withMaxConnections(1)) {
            pushRequest(HttpRequest(method = HttpMethods.HEAD, uri = "/head"))
            pushRequest(HttpRequest(uri = "/2"))
            val conn1 = expectNextConnection()
            conn1.expectRequestToPath("/head")

            conn1.pushResponse(HttpResponse(entity = HttpEntity.Default(ContentTypes.`application/octet-stream`, 100,
              Source.empty)))
            val res = expectResponse()
            res.entity.contentLengthOption.get shouldEqual 100

            // HEAD requests do not require consumption of entity but users might do anyway
            res.entity.discardBytes()
            // allow a bit of time for dispatching the event
            Thread.sleep(100)

            // immediately expect next request
            conn1.expectRequestToPath("/2")
            conn1.pushResponse()
            expectResponse().status shouldEqual StatusCodes.OK
          })
      }
      "time out quickly when response entity stream is not subscribed fast enough".inWithShutdown(
        new SetupWithServerProbes {
          pendingIn(targetTrans = PassThrough) // infra seems to be missing something

          // FIXME: set subscription timeout to value relating to below `expectNoMessage`

          pushRequest(HttpRequest(uri = "/1"))
          val conn1 = expectNextConnection()
          conn1.expectRequestToPath("/1")

          val (_, chunks) =
            EventFilter.warning(pattern = ".*Response entity was not subscribed.*", occurrences = 1).intercept {
              val resBodyOut = conn1.pushChunkedResponse()
              val HttpResponse(_, _, HttpEntity.Chunked(_, chunks), _) = expectResponse()
              (resBodyOut, chunks)
            }

          val streamResult = chunks.runWith(Sink.ignore)
          Await.ready(streamResult, 3.seconds)
          streamResult.value.get.failed.get.getMessage shouldEqual
          "Response entity was not subscribed after 1 second. Make sure to read the response `entity` body or call `entity.discardBytes()` on it -- in case you deal with `HttpResponse`, use the shortcut `response.discardEntityBytes()`. GET /1 Empty -> 200 OK Chunked"
          conn1.expectError()
        })
      "time out when a connection was unused for a long time" in pending
      "time out and reconnect when a request is not handled in time" in pending
      "time out when connection cannot be established" in pending
      "fail a request if the request entity fails".inWithShutdown(new SetupWithServerProbes {
        val reqBytesOut = pushChunkedRequest(numRetries = 0)

        val conn1 = expectNextConnection()
        val reqBytesIn = conn1.expectChunkedRequestBytesAsProbe()
        reqBytesOut.sendNext(ByteString("hello"))
        reqBytesIn.expectUtf8EncodedString("hello")

        val theError = new RuntimeException("oops, could not finish sending request")

        // make sure these errors are not noisily logged
        EventFilter[RuntimeException](occurrences = 0).intercept {
          reqBytesOut.sendError(theError)

          val error = expectResponseError()
          error shouldBe theError
        }

        // FIXME: it seems on TCP cancellation might overtake the error on the server-side so we get a cancellation / regular completion here
        // conn1.serverRequests.expectError()
      })
      "fail a request if the connection stream fails while waiting for request entity bytes".inWithShutdown(
        new SetupWithServerProbes {
          val reqBytesOut = pushChunkedRequest(HttpRequest(method = HttpMethods.POST), numRetries = 0)

          val conn1 = expectNextConnection()
          val reqBytesIn = conn1.expectChunkedRequestBytesAsProbe()

          reqBytesOut.sendNext(ByteString("chunk1"))
          reqBytesIn.expectUtf8EncodedString("chunk1")

          conn1.failConnection(new RuntimeException("server temporarily out for lunch"))

          // server behavior not tested for now
          // expectRequestStreamError(reqBytesIn) // some kind of truncation error
          // reqBytesOut.expectCancellation()
          expectResponseError()
        })
      "fail a request if the connection stream fails while waiting for a response".inWithShutdown(
        new SetupWithServerProbes {
          pushRequest(HttpRequest(method = HttpMethods.POST), numRetries = 0)
          val conn1 = expectNextConnection()
          conn1.expectRequest()

          conn1.failConnection(new RuntimeException("solar wind prevented transmission"))
          expectResponseError()
        })
      "fail a request if the connection stream fails while waiting for response entity bytes".inWithShutdown(
        new SetupWithServerProbes {
          pushRequest(HttpRequest(method = HttpMethods.POST), numRetries = 0)
          val conn1 = expectNextConnection()
          conn1.expectRequest()
          val resBytesOut = conn1.pushChunkedResponse()
          val resBytesIn = expectChunkedResponseBytesAsProbe()
          resBytesOut.sendNext(ByteString("hello"))
          resBytesIn.expectUtf8EncodedString("hello")

          conn1.failConnection(new RuntimeException("solar wind prevented transmission"))
          // server behavior not tested for now
          // resBytesIn.expectError()

          // client already received response, no need to report error another time
        })
      "fail a request if the response entity stream fails during processing".inWithShutdown(new SetupWithServerProbes {
        pushRequest(HttpRequest(method = HttpMethods.POST), numRetries = 0)
        val conn1 = expectNextConnection()
        conn1.expectRequest()
        val resBytesOut = conn1.pushChunkedResponse()
        val resBytesIn = expectChunkedResponseBytesAsProbe()
        resBytesOut.sendNext(ByteString("hello"))
        resBytesIn.expectUtf8EncodedString("hello")

        resBytesOut.sendError(new RuntimeException("hard disk too soft for reading further"))
        resBytesIn.expectError()
        conn1.expectError()

        // client already received response, no need to report error another time
      })
      "create a new connection when previous one was closed regularly between requests".inWithShutdown(
        new SetupWithServerProbes {
          pushRequest(HttpRequest(uri = "/simple"))

          val conn1 = expectNextConnection()
          val req = conn1.expectRequest()
          conn1.pushResponse(HttpResponse(headers = headers.Connection("close") :: Nil, entity = req.uri.path.toString))
          expectResponseEntityAsString() shouldEqual "/simple"
          conn1.completeHandler()

          pushRequest(HttpRequest(uri = "/next"))
          val conn2 = expectNextConnection()
          conn2.expectRequestToPath("/next")
          conn2.pushResponse(HttpResponse(entity = "response"))
          expectResponseEntityAsString() shouldEqual "response"
        })

      "create a new connection when previous one timed out between requests because of keep-alive-timeout".inWithShutdown(
        new SetupWithServerProbes(_.withKeepAliveTimeout(800.millis)) {
          pushRequest(HttpRequest(uri = "/simple"))

          val conn1 = expectNextConnection()
          val req = conn1.expectRequest()
          conn1.pushResponse(HttpResponse(headers = headers.Connection("keep-alive") :: Nil,
            entity = req.uri.path.toString))

          expectResponseEntityAsString() shouldEqual "/simple"
          val receivedFirstResponse = System.nanoTime()

          // wait until pool closes connection because of keep-alive-timeout
          conn1.serverRequests.expectComplete()
          conn1.serverResponses.sendComplete()

          val lasted = System.nanoTime() - receivedFirstResponse

          lasted.nanos should be >= 800.millis // = keep-alive-timeout
          lasted.nanos should be < 2500.millis // eventually it should be done, even with a bit of slack it shouldn't be too late

          pushRequest(HttpRequest(uri = "/next"))
          val conn2 = expectNextConnection()
          conn2.expectRequestToPath("/next")
          conn2.pushResponse(HttpResponse(entity = "response"))
          expectResponseEntityAsString() shouldEqual "response"
        })
      "create a new connection when previous one was closed regularly between requests without sending a `Connection: close` header first".inWithShutdown(
        new SetupWithServerProbes {
          pushRequest(HttpRequest(uri = "/simple"))

          val conn1 = expectNextConnection()
          val req = conn1.expectRequest()
          conn1.pushResponse(HttpResponse(entity = req.uri.path.toString))
          expectResponseEntityAsString() shouldEqual "/simple"
          conn1.completeHandler()

          // Here's an inherent race condition: we might accidentally schedule the next request on the just-completing
          // connection. So we add a sleep to increase chances, we've been in the Unconnected state before the new request
          // is dispatched. If connection still happens to be in the Idle state, the request should be transparently
          // retried.
          Thread.sleep(100)

          pushRequest(HttpRequest(uri = "/next"))
          val conn2 = expectNextConnection()
          conn2.expectRequestToPath("/next")
          conn2.pushResponse(HttpResponse(entity = "response"))
          expectResponseEntityAsString() shouldEqual "response"
        })
      "create a new connection when previous one failed between requests".inWithShutdown(new SetupWithServerProbes {
        pushRequest(HttpRequest(uri = "/simple"))

        val conn1 = expectNextConnection()
        val req = conn1.expectRequest()
        conn1.pushResponse(HttpResponse(entity = req.uri.path.toString))
        expectResponseEntityAsString() shouldEqual "/simple"
        conn1.failConnection(new RuntimeException("broken connection"))

        pushRequest(HttpRequest(uri = "/next"))
        val conn2 = expectNextConnection()
        conn2.expectRequestToPath("/next")
        conn2.pushResponse(HttpResponse(entity = "response"))
        expectResponseEntityAsString() shouldEqual "response"
      })
      "support 100-continue" in pending
      "without any connections establish the number of configured min-connections".inWithShutdown(
        new SetupWithServerProbes(_.withMaxConnections(2).withMinConnections(1)) {
          // expect a new connection immediately
          val conn1 = expectNextConnection()

          // should be used for the first request
          pushRequest(HttpRequest(uri = "/simple"))
          conn1.expectRequest()
        })
      "re-establish min-connections when number of open connections falls below threshold".inWithShutdown(
        new SetupWithServerProbes(_.withMaxConnections(2).withMinConnections(1)) {

          // expect a new connection immediately
          val conn1 = expectNextConnection()

          // should be used for the first request
          pushRequest(HttpRequest(uri = "/simple"))
          conn1.expectRequestToPath("/simple")
          conn1.pushResponse(HttpResponse(headers = headers.Connection("close") :: Nil))
          expectResponse()
          conn1.completeConnection()

          expectNextConnection()
        })
      "not buffer an unreasonable number of outgoing responses".inWithShutdown(
        new SetupWithServerProbes(_.withMaxConnections(1).withMinConnections(1)) {
          val conn1 = expectNextConnection()

          def oneCycle(): Unit = {
            pushRequest()
            conn1.expectRequest(within = 100.millis.dilated)
            conn1.pushResponse()
          }

          eventually {
            // should fail eventually because backpressure kicks in and one of the expects / pushes above will timeout
            a[Throwable] should be thrownBy oneCycle()
          }
        })
      "dispatch multiple failures on different slots when request entity fails".inWithShutdown(
        new SetupWithServerProbes(_.withMaxConnections(3)) {
          val req1 = pushChunkedRequest(numRetries = 0)
          val conn1 = expectNextConnection()
          conn1.expectChunkedRequestBytesAsProbe()

          val req2 = pushChunkedRequest(numRetries = 0)
          val conn2 = expectNextConnection()
          conn2.expectChunkedRequestBytesAsProbe()

          req1.sendError(new RuntimeException("First request stumbled and fell"))
          conn1.failConnection(new RuntimeException("First connection crash-landed on mars"))

          // don't check for first error yet

          req2.sendError(new RuntimeException("Second request stumbled and flew"))
          conn2.failConnection(new RuntimeException("Second connection crash-landed on the moon"))

          expectResponseError()
          expectResponseError()

          // check that we are not still dispatchable (#1726)
          pushRequest()
          pushRequest()

          val conn3 = expectNextConnection()
          val conn4 = expectNextConnection()

          conn3.pushResponse()
          conn4.pushResponse()
          expectResponse()
          expectResponse()
        })
      "not send requests to known-to-be-closed-soon connections" in pending
      "support retries" in pending
      "strictly enforce number of established connections in longer running case" in pending
      "provide access to basic metrics as the materialized value" in pending
      "ignore the pipelining setting (for now)" in pending
      "work correctly in the presence of `Connection: close` headers" in pending
      "if connecting attempt fails, backup the next connection attempts".inWithShutdown {
        @volatile var shouldFail = true
        val connectionCounter = new AtomicInteger()

        new SetupWithServerProbes(
          _.withBaseConnectionBackoff(100.millis)
            .withMaxConnectionBackoff(2000.millis)
            .withMinConnections(1)
            .withMaxConnections(2)) {
          override def onNewConnection(requestPublisher: Publisher[HttpRequest],
              responseSubscriber: Subscriber[HttpResponse]): Future[Http.OutgoingConnection] = {
            connectionCounter.incrementAndGet()
            if (shouldFail)
              Future.failed(new RuntimeException("Server out of coffee"))
            else
              super.onNewConnection(requestPublisher, responseSubscriber)
          }

          eventually(Timeout(500.millis))(
            connectionCounter.get() should be > 0)
          val previousCounter = connectionCounter.get()

          log.debug("Pushing 2 requests")
          pushRequest()
          pushRequest()

          log.debug("Sleeping for 1000 millis")
          Thread.sleep(1000)
          // 1000 ms, should contain these backoff intervals 100 + 200 + 400  = 700ms ~ 3 requests per connection = 6 connections have been made
          val newCounter = connectionCounter.get()
          newCounter should be < (previousCounter + 6)
          newCounter should be >= (previousCounter + 2) // should have managed to do at least 2 extra connection attempts in 1000ms > 200ms + 400ms

          // now heal
          shouldFail = false
          log.debug("Healing the connection")

          // expect that both connections come up after a while
          val conn1 = expectNextConnection()
          val conn2 = expectNextConnection()

          conn1.expectRequest()
          conn2.expectRequest()
          conn1.pushResponse()
          conn2.pushResponse()
          expectResponse()
          expectResponse()
        }
      }

      def pendingIn(targetTrans: ClientServerImplementation): Unit =
        if (clientServerImplementation == targetTrans)
          pending

      abstract class TestSetup {
        lazy val requestIn = TestPublisher.probe[RequestContext]()
        lazy val responseOut = TestSubscriber.probe[ResponseContext]()

        protected def server: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]]

        protected def settings: ConnectionPoolSettings

        lazy val impl = NewHostConnectionPool(server, settings, system.log)
        val stream =
          Source.fromPublisher(requestIn)
            .via(impl)
            .runWith(Sink.fromSubscriber(responseOut))

        def pushRequest(req: HttpRequest = HttpRequest(), numRetries: Int = 5): Unit =
          requestIn.sendNext(RequestContext(req, Promise(), numRetries))

        def pushChunkedRequest(
            req: HttpRequest = HttpRequest(), numRetries: Int = 5): TestPublisher.Probe[ByteString] = {
          val probe = TestPublisher.probe[ByteString]()
          pushRequest(req.withEntity(HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`,
              Source.fromPublisher(probe))), numRetries)
          probe
        }

        def expectResponse(): HttpResponse =
          responseOut.requestNext().response.recover {
            case ex => throw new AssertionError("Expected successful response but got exception", ex)
          }.get

        def expectResponseEntityAsString(): String =
          expectResponse().entity.dataBytes.runFold(ByteString.empty)(_ ++ _).awaitResult(5.seconds.dilated).utf8String

        /** Expect a chunked response, connect a [[ByteStringSinkProbe]] to it and return it */
        def expectChunkedResponseBytesAsProbe(): ByteStringSinkProbe = {
          val HttpResponse(_, _, entity: HttpEntity.Chunked, _) = expectResponse()
          val probe = ByteStringSinkProbe()
          entity.dataBytes.runWith(probe.sink)
          probe
        }

        def expectNoRequestDemand(): Unit =
          requestIn.pending shouldEqual 0

        def expectResponseError(): Throwable =
          responseOut.requestNext().response.failed.get

        def shutdown(): Unit = {
          requestIn.sendError(new RuntimeException("TestSetup.shutdown"))
          responseOut.expectError()
        }
      }

      class SetupWithServerProbes(
          changeSettings: ConnectionPoolSettings => ConnectionPoolSettings = identity) extends TestSetup {
        override protected def settings = changeSettings(defaultSettings)

        class ServerConnection(requestPublisher: Publisher[HttpRequest], responseSubscriber: Subscriber[HttpResponse]) {
          val acceptConnectionPromise = Promise[Http.OutgoingConnection]()
          val serverRequests = TestSubscriber.probe[HttpRequest]()
          val serverResponses = TestPublisher.probe[HttpResponse]()
          val killSwitch = KillSwitches.shared("connection-kill-switch")

          def expectRequest(): HttpRequest =
            serverRequests.requestNext()

          def expectRequest(within: FiniteDuration): HttpRequest =
            serverRequests.within(within)(serverRequests.requestNext())

          def expectRequestToPath(path: String): Unit =
            expectRequest().uri.path.toString shouldEqual path

          /** Expect a chunked response, connect a [[ByteStringSinkProbe]] to it and return it */
          def expectChunkedRequestBytesAsProbe(): ByteStringSinkProbe = {
            val HttpRequest(_, _, _, entity: HttpEntity.Chunked, _) = expectRequest()
            val probe = ByteStringSinkProbe()
            entity.dataBytes.runWith(probe.sink)
            probe
          }

          def expectNoRequest(): Unit =
            serverRequests.expectNoMessage(remainingOrDefault)

          def pushResponse(response: HttpResponse = HttpResponse()) =
            serverResponses.sendNext(response)

          def pushChunkedResponse(response: HttpResponse = HttpResponse()): TestPublisher.Probe[ByteString] = {
            val res = TestPublisher.probe[ByteString]()
            pushResponse(response.withEntity(HttpEntity.Chunked.fromData(ContentTypes.`application/octet-stream`,
              Source.fromPublisher(res))))
            res
          }

          def completeHandler(): Unit = {
            serverResponses.sendComplete()
            serverRequests.expectComplete()
          }

          def failConnection(cause: Exception): Unit =
            killSwitch.abort(cause)

          def completeConnection(): Unit =
            killSwitch.shutdown()

          def failHandler(cause: Exception): Unit = {
            serverResponses.sendError(cause)
            // since this is server behavior, it's not really important to check it here
            // FIXME: verify server behavior
            expectErrorOrCompleteOnRequestSide()
          }

          def expectError(): Unit = {
            serverResponses.expectCancellation()
            expectErrorOrCompleteOnRequestSide()
          }

          def expectErrorOrCompleteOnRequestSide(): Unit =
            serverRequests.expectEventPF {
              case _: TestSubscriber.OnError =>
              case TestSubscriber.OnComplete =>
            }

          lazy val (outgoingConnection: Future[Http.OutgoingConnection], terminationWatch: Future[Done]) =
            Flow.fromSinkAndSource(
              Sink.fromSubscriber(serverRequests),
              Source.fromPublisher(serverResponses))
              .joinMat(clientServerImplementation.get(killSwitch))(Keep.right)
              .watchTermination()(Keep.both)
              .join(
                Flow.fromSinkAndSource(
                  Sink.fromSubscriber(responseSubscriber),
                  Source.fromPublisher(requestPublisher)))
              .run()(singleElementBufferMaterializer)

          def acceptConnection(): Unit =
            acceptConnectionPromise.completeWith(outgoingConnection)

          def failConnectionAttempt(cause: Throwable): Unit = {
            acceptConnectionPromise.failure(cause)
            Source.failed(cause).runWith(Sink.fromSubscriber(responseSubscriber))
            Source.fromPublisher(requestPublisher).runWith(Sink.cancelled)
          }
        }

        private lazy val serverConnections = TestProbe()

        def expectNextConnectionAttempt(): ServerConnection =
          serverConnections.expectMsgType[ServerConnection]

        def expectNextConnection(): ServerConnection = {
          val conn = serverConnections.expectMsgType[ServerConnection]
          conn.acceptConnection()
          conn
        }

        def expectNoNewConnection(within: FiniteDuration = remainingOrDefault): Unit =
          serverConnections.expectNoMessage(within)

        def onNewConnection(requestPublisher: Publisher[HttpRequest], responseSubscriber: Subscriber[HttpResponse])
            : Future[Http.OutgoingConnection] = {
          val connection = new ServerConnection(requestPublisher, responseSubscriber)
          serverConnections.ref ! connection
          connection.acceptConnectionPromise.future
        }

        protected override lazy val server: Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] =
          Flow.fromSinkAndSourceMat(
            // buffer is needed because the async subscriber/publisher boundary will otherwise request > 1
            Flow[HttpRequest].buffer(1, OverflowStrategy.backpressure)
              .toMat(Sink.asPublisher[HttpRequest](false))(Keep.right),
            Source.asSubscriber[HttpResponse])(Keep.both)
            .mapMaterializedValue {
              case (requestPublisher, responseSubscriber) =>
                onNewConnection(requestPublisher, responseSubscriber)
            }
      }
    }

  /** Transport that just passes through requests / responses */
  case object PassThrough extends ClientServerImplementation {
    def failsHandlerInputWhenHandlerOutputFails: Boolean = true
    override def get(connectionKillSwitch: SharedKillSwitch)
        : BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, Future[Http.OutgoingConnection]] =
      BidiFlow.fromGraph(PassThroughTransport)
        .atop(BidiFlow.fromFlows(connectionKillSwitch.flow[HttpResponse], connectionKillSwitch.flow[HttpRequest]))
        .mapMaterializedValue(_ => Future.successful(newOutgoingConnection()))

    object PassThroughTransport extends GraphStage[BidiShape[HttpResponse, HttpResponse, HttpRequest, HttpRequest]] {
      val reqIn = Inlet[HttpRequest]("reqIn")
      val reqOut = Outlet[HttpRequest]("reqOut")
      val resIn = Inlet[HttpResponse]("resIn")
      val resOut = Outlet[HttpResponse]("resOut")

      val shape = BidiShape(resIn, resOut, reqIn, reqOut)

      def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
        val failureCallback = getAsyncCallback[Throwable](cause => failStage(cause))
        val killSwitch = KillSwitches.shared("entity")

        object AddKillSwitch extends StreamUtils.EntityStreamOp[Unit] {
          def strictM: Unit = ()
          def apply[T, Mat](source: Source[T, Mat]): (Source[T, Mat], Unit) =
            (source.via(killSwitch.flow[T]), ())
        }
        class MonitorMessage[T <: HttpMessage](in: Inlet[T], out: Outlet[T]) extends InHandler with OutHandler {

          def onPush(): Unit = {
            val msg: T = grab(in)

            val (newEntity, res) =
              HttpEntity.captureTermination(msg.entity)

            val finalMsg: T = msg.withEntity(
              StreamUtils.transformEntityStream(newEntity, AddKillSwitch)._1
                .asInstanceOf[MessageEntity]).asInstanceOf[T] // FIXME: that cast is probably unsafe for CloseLimited

            res.onComplete { // if entity fails we report back to fail the stage
              case Failure(cause) => failureCallback.invoke(cause)
              case _              =>
            }(materializer.executionContext)

            push(out, finalMsg)
          }
          def onPull(): Unit = pull(in)

          override def onUpstreamFailure(ex: Throwable): Unit = {
            killSwitch.abort(ex)
            super.onUpstreamFailure(ex)
          }

          override def onDownstreamFinish(cause: Throwable): Unit = failStage(new RuntimeException("was cancelled"))
        }
        setHandlers(reqIn, reqOut, new MonitorMessage(reqIn, reqOut))
        setHandlers(resIn, resOut, new MonitorMessage(resIn, resOut))
      }
    }
  }

  /** Transport that runs everything through client and server engines but without actual network */
  case object PekkoHttpEngineNoNetwork extends ClientServerImplementation {
    def failsHandlerInputWhenHandlerOutputFails: Boolean = false

    override def get(connectionKillSwitch: SharedKillSwitch)
        : BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, Future[Http.OutgoingConnection]] =
      Http().serverLayer().atop(
        TLSPlacebo()).atop(
        BidiFlow.fromFlows(connectionKillSwitch.flow[ByteString], connectionKillSwitch.flow[ByteString])).atop(
        TLSPlacebo().reversed).atop(
        Http().clientLayer(Host("example.org")).reversed).mapMaterializedValue(_ =>
        Future.successful(newOutgoingConnection()))
  }

  class KillSwitchedClientTransport(connectionKillSwitch: SharedKillSwitch) extends ClientTransport {
    def connectTo(host: String, port: Int, settings: ClientConnectionSettings)(
        implicit system: ActorSystem): Flow[ByteString, ByteString, Future[Http.OutgoingConnection]] =
      Flow[ByteString]
        .via(connectionKillSwitch.flow[ByteString])
        .viaMat(ClientTransport.TCP.connectTo(host, port, settings))(Keep.right)
        .via(connectionKillSwitch.flow[ByteString])
  }

  /** Transport that uses actual top-level Http APIs to establish a plaintext HTTP connection */
  case object PekkoHttpEngineTCP extends TopLevelApiClientServerImplementation {
    protected override def bindServerSource = Http().newServerAt("localhost", 0).connectionSource()
    protected def clientConnectionFlow(serverBinding: ServerBinding, connectionKillSwitch: SharedKillSwitch)
        : Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] = {
      val clientConnectionSettings =
        ClientConnectionSettings(system).withTransport(new KillSwitchedClientTransport(connectionKillSwitch))
      Http().outgoingConnectionUsingContext(host = "localhost", port = serverBinding.localAddress.getPort,
        connectionContext = ConnectionContext.noEncryption(), settings = clientConnectionSettings)
    }
  }

  /**
   * Transport that uses actual top-level Http APIs to establish a HTTPS connection
   *
   * Currently requires an /etc/hosts entry that points pekko.example.org to a locally bindable address.
   */
  case object PekkoHttpEngineTLS extends TopLevelApiClientServerImplementation {
    protected override def bindServerSource =
      Http().newServerAt("pekko.example.org", 0).enableHttps(
        ExampleHttpContexts.exampleServerContext).connectionSource()
    protected def clientConnectionFlow(serverBinding: ServerBinding, connectionKillSwitch: SharedKillSwitch)
        : Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]] = {
      val clientConnectionSettings =
        ClientConnectionSettings(system).withTransport(new KillSwitchedClientTransport(connectionKillSwitch))
      Http().outgoingConnectionUsingContext(host = "pekko.example.org", port = serverBinding.localAddress.getPort,
        connectionContext = ExampleHttpContexts.exampleClientContext, settings = clientConnectionSettings)
    }
  }
  abstract class TopLevelApiClientServerImplementation extends ClientServerImplementation {
    def failsHandlerInputWhenHandlerOutputFails: Boolean = false

    protected def bindServerSource: Source[Http.IncomingConnection, Future[ServerBinding]]
    protected def clientConnectionFlow(serverBinding: ServerBinding, connectionKillSwitch: SharedKillSwitch)
        : Flow[HttpRequest, HttpResponse, Future[Http.OutgoingConnection]]

    override def get(connectionKillSwitch: SharedKillSwitch)
        : BidiFlow[HttpResponse, HttpResponse, HttpRequest, HttpRequest, Future[Http.OutgoingConnection]] = {
      val connectionProbe = TestProbe()
      system.log.debug("Binding server for test ...")
      val serverBinding: ServerBinding =
        bindServerSource
          .to(Sink.foreach { serverConnection =>
            connectionProbe.ref ! serverConnection
          })
          .run().awaitResult(3.seconds)
      system.log.debug(s"Server bound to [${serverBinding.localAddress}]")

      // needs to be an involved two step process:
      //   1. setup client flow and proxies on the server side to be able to return that flow immediately
      //   2. when client connection was established, grab server connection as well and attach to proxies
      //      (cannot be implemented with just mapMaterializedValue because there's no transposing constructor for BidiFlow)
      BidiFlow.fromGraph(
        GraphDSL.createGraph(Sink.asPublisher[HttpResponse](fanout = false), Source.asSubscriber[HttpRequest],
          clientConnectionFlow(serverBinding, connectionKillSwitch))((_, _, _)) {
          implicit builder => (resIn, reqOut, client) =>
            import GraphDSL.Implicits._

            builder.materializedValue ~> Sink.foreach[
              (Publisher[HttpResponse], Subscriber[HttpRequest], Future[Http.OutgoingConnection])] {
              case (resOut, reqIn, clientConn) =>
                clientConn.foreach { _ =>
                  val serverConn = connectionProbe.expectMsgType[Http.IncomingConnection]
                  Flow.fromSinkAndSource(
                    Sink.fromSubscriber(reqIn),
                    Source.fromPublisher(resOut)).join(serverConn.flow).run()
                }(system.dispatcher)
            }

            BidiShape(resIn.in, client.out, client.in, reqOut.out)
        }).mapMaterializedValue(_._3)
    }
  }

  /** Generates a new unique outgoingConnection */
  protected val newOutgoingConnection: () => Http.OutgoingConnection = {
    val portCounter = new AtomicInteger(1)

    () => {
      val connId = portCounter.getAndIncrement()
      Http.OutgoingConnection(
        InetSocketAddress.createUnresolved(s"local-$connId", connId % 65536),
        InetSocketAddress.createUnresolved("remote", 5555))
    }
  }
}
