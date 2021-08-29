package com.akolov.azzuro.example.simple

import com.akolov.azzuro.common.Serde
import com.akolov.azzuro.module.AzzuroCommands
import io.axoniq.axonserver.grpc.command.command.ZioCommand
import io.circe.generic.auto._
import io.circe.parser.decode
import io.circe.syntax._
import io.grpc.ManagedChannelBuilder
import scalapb.zio_grpc.ZManagedChannel
import zio._
import zio.clock.Clock
import zio.console.putStrLn
import zio.duration._
import zio.logging.slf4j.Slf4jLogger
import zio.logging.{LogAnnotation, Logging, log}
import zio.macros.accessible
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
        _ <- AzzuroCommands
          .registerHandlerR[ChangePasswordCommand, CommandsEnv, Boolean](
            { case ChangePasswordCommand(oldPassword, newPassword) =>
              if (oldPassword == "admin") {
                UserService.setPassword(newPassword) *> log.debug(
                  "Changed password"
                ) *> ZIO.succeed(true)
              } else {
                log.debug("Can not change") *> ZIO.succeed(false)
              }
            },
            Serde[ChangePasswordCommand](
              _.asJson.noSpaces,
              s => decode[ChangePasswordCommand](s).left.map(_.toString)
            ),
            Serde[Boolean](
              _.toString,
              s => Right(s.toBoolean)
            ),
            commandsLayer
          )
        response <- AzzuroCommands.sendCommand(
          ChangePasswordCommand("admin", "alabala")
        )
        _ <- putStrLn(s"Got response: $response")
        _ <- ZIO.never
      } yield ()

    (io.provideCustomLayer(customLayer) *>
      ZIO.sleep(2.seconds)).exitCode
  }
  lazy val customLayer = logLayer >+>
    Clock.live >+> clientLayer >+> AzzuroCommands.live
  lazy val commandsLayer
      : ZLayer[Any, Throwable, Logging with Clock with Has[Service]] =
    logLayer >+>
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
  val live: ULayer[Has[Service]] = ZLayer.succeed(
    new Service {
      override def setPassword(
          newPassword: String
      ): Task[Boolean] = UIO.succeed(true)
    }
  )
}
