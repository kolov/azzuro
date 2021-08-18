package com.akolov.azzuro

import io.axoniq.axonserver.grpc.command.command.ZioCommand
import zio.stream.ZStream
import io.axoniq.axonserver.grpc.command.command.CommandProviderOutbound
import zio._
import zio.macros.accessible
import io.axoniq.axonserver.grpc.command.command.CommandSubscription
import io.axoniq.axonserver.grpc.command.command.CommandProviderInbound
import io.grpc.Status
import io.axoniq.axonserver.grpc.command.command.Command
import io.axoniq.axonserver.grpc.command.command.CommandResponse

@accessible
object AzzuroCommands {
  type AzzuroCommands = Has[Service]

  trait Service {
    def registerHandler(
        name: String,
        handler: RIO[ZEnv, Unit]
    ): AIO[Unit]
    def sendCommand(command: Command): AIO[CommandResponse]
  }

  def safePrintLn(line: String) = console.putStrLn(line).orElseSucceed(())

  val live = ZLayer.fromEffect {
    for {
      q <- openCommandsStream
      clientLayer <- ZIO
        .service[ZioCommand.CommandServiceClient.ZService[Any, Any]]
    } yield new Service {

      override def sendCommand(
          command: Command
      ): AIO[CommandResponse] =
        ZioCommand.CommandServiceClient
          .dispatch(command)
          .provideLayer(ZLayer.succeed(clientLayer))
          .mapError(GrpcError.apply)

      override def registerHandler(
          name: String,
          handler: RIO[ZEnv, Unit]
      ): AIO[Unit] = {
        val subscription = CommandSubscription()
        val req = CommandProviderOutbound.Request.Subscribe(subscription)
        for {
          outcome <- q.offer(CommandProviderOutbound(req, "inscructionId"))
          _ <- safePrintLn(s"Offered handler for [$name]: $outcome")
        } yield ()
      }

    }
  }

  private def openCommandsStream: ZIO[Has[
    ZioCommand.CommandServiceClient.ZService[Any, Any]
  ] with zio.console.Console, Nothing, Queue[
    CommandProviderOutbound
  ]] = {
    for {
      queue <- Queue.bounded[CommandProviderOutbound](1)
      commandsStream = ZStream
        .fromQueue[Any, io.grpc.Status, CommandProviderOutbound](
          queue
        )
        .tap(cmdOut =>
          console.putStrLn(s"Got command from queue: $cmdOut").orElseSucceed(())
        )
      _ <- commandsStream.runDrain.forkDaemon
      stream = ZioCommand.CommandServiceClient
        .openStream(commandsStream)
      _ <- stream
        .tap(cmdIn => console.putStrLn(s"Got response from Axon: $cmdIn"))
        .runDrain
        .forkDaemon
    } yield queue
  }
}
