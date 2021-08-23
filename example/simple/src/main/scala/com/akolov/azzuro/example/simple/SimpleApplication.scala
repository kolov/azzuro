package com.akolov.azzuro.example.simple

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
import com.akolov.azzuro.AzzuroCommands
import com.akolov.azzuro.AzzuroError
import com.akolov.azzuro.NamedPromises
import zio.macros.accessible
import zio.logging.log
import com.akolov.azzuro.common.CommandExecutor
import io.circe.generic.auto._, io.circe.syntax._, io.circe.parser.decode
import com.akolov.azzuro.common.Serde
import com.google.protobuf.ByteString
/*
A simple application that can register and execute commands.
The command uses a domain service - UserService.
 */

object SimpleApplication extends zio.App {

  import UserService._

  type CommandsEnv = Logging with Clock with UserService
  case class ChangePasswordCommand(oldPassword: String, newPassword: String)

  override def run(args: List[String]): URIO[ZEnv, ExitCode] = {

    val io =
      for {
        _ <- AzzuroCommands.sendPermits(100000)
        _ <- AzzuroCommands.registerHandler[ChangePasswordCommand, CommandsEnv](
          { case ChangePasswordCommand(oldPassword, newPassword) =>
            val io = if (oldPassword == "admin") {
              UserService.setPassword(newPassword) *> log.debug(
                "Changed password"
              ) *> ZIO.succeed(true)
            } else {
              log.debug("Can not change") *> ZIO.succeed(false)
            }
            io
          },
          Serde[Any](
            {
              case c: ChangePasswordCommand => c.asJson.noSpaces
              case _                        => "unknown"
            },
            s => decode[ChangePasswordCommand](s).left.map(_.toString)
          ),
          commandsLayer
        )
        response <- AzzuroCommands.sendCommand(
          ChangePasswordCommand("admin", "alabala")
        )
        _ <- ZIO.never
      } yield ()

    (io.provideCustomLayer(customLayer) *>
      ZIO.sleep(2.seconds)).exitCode
  }
  lazy val customLayer = logLayer >+> NamedPromises.live >+>
    Clock.live >+> clientLayer >+> CommandExecutor.live >+> AzzuroCommands.live
  lazy val commandsLayer = logLayer >+>
    Clock.live >+> UserService.live
  val channel = ZManagedChannel {
    val builder: ManagedChannelBuilder[_] = ManagedChannelBuilder
      .forAddress("localhost", 8124)
      .usePlaintext()
    builder
  }

  lazy val clientLayer: ZLayer[Any, Throwable, Has[
    ZioCommand.CommandServiceClient.ZService[Any, Any]
  ]] = ZioCommand.CommandServiceClient.live(channel)

  lazy val logLayer: ULayer[Logging] = Slf4jLogger.make { (context, message) =>
    LogAnnotation.CorrelationId.render(
      context.get(LogAnnotation.CorrelationId)
    ) match {
      case "undefined-correlation-id" => message
      case correlationId =>
        "[correlation-id = %s] %s".format(correlationId, message)
    }
  }

}

@accessible
object UserService {
  type UserService = Has[Service]

  trait Service {
    def setPassword(newPassword: String): Task[Boolean]
  }
  val live = ZLayer.succeed(
    new Service {
      override def setPassword(
          newPassword: String
      ): Task[Boolean] = UIO.succeed(true)
    }
  )
}
