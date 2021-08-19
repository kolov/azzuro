package com.akolov.azzuro

import io.axoniq.axonserver.grpc.command.command.Command
import io.axoniq.axonserver.grpc.command.command.CommandServiceGrpc
import io.axoniq.axonserver.grpc.command.command.ZioCommand
import io.axoniq.axonserver.grpc.common.SerializedObject
import scalapb.zio_grpc.ZManagedChannel
import io.grpc.ManagedChannelBuilder
import zio._
import io.axoniq.axonserver.grpc.command.command.CommandProviderOutbound
import io.grpc.Status
import io.axoniq.axonserver.grpc.command.command.CommandResponse
import zio.duration._
import java.util.UUID
import zio.logging.slf4j.Slf4jLogger
import zio.logging.LogAnnotation
import zio.logging.Logging
import zio.clock.Clock

object AzzuroApplication extends zio.App {

  val channel = ZManagedChannel {
    val builder: ManagedChannelBuilder[_] = ManagedChannelBuilder
      .forAddress("localhost", 8124)
      .usePlaintext()
    builder
  }

  val clientLayer: ZLayer[Any, Throwable, Has[
    ZioCommand.CommandServiceClient.ZService[Any, Any]
  ]] = ZioCommand.CommandServiceClient.live(channel)

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {
    val env: ULayer[Logging] = Slf4jLogger.make { (context, message) =>
      LogAnnotation.CorrelationId.render(
        context.get(LogAnnotation.CorrelationId)
      ) match {
        case "undefined-correlation-id" => message
        case correlationId =>
          "[correlation-id = %s] %s".format(correlationId, message)
      } 
    }

    (io.provideCustomLayer(
      env >+>
        Clock.live >+> clientLayer >+> AzzuroCommands.live
    ) *>
      ZIO.sleep(2.seconds)).fold(_ => ExitCode.failure, _ => ExitCode.success)
  }
  case class MyCommand(name: String)

  val io: ZIO[AppEnv with Has[AzzuroCommands.Service], AzzuroError, Unit] =
    for {
      _ <- AzzuroCommands.sendPermits(100000)
      _ <- AzzuroCommands.registerHandler(
        "main",
        console.putStrLn("GotCommand")
      )
      _ <- ZIO.sleep(4.second)
      response <- AzzuroCommands.sendCommand(
        Command(
          messageIdentifier = UUID.randomUUID().toString,
          name = "main",
          payload = Some(SerializedObject("{}")),
          componentName = "test",
          timestamp = System.currentTimeMillis()
        )
      )

      _ <- ZIO.never
    } yield ()

}
