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
import java.util.UUID
import io.axoniq.axonserver.grpc.common.FlowControl
import zio.logging._
import io.axoniq.axonserver.grpc.common.InstructionAck

@accessible
object AzzuroCommands {
  type AzzuroCommands = Has[Service]

  trait Service {
    def registerHandler(
        name: String,
        handler: RIO[ZEnv, Unit]
    ): AIO[Unit]

    def sendCommand(command: Command): AIO[CommandResponse]

    def sendPermits(count: Long): AIO[Unit]
  }

  private def openCommandsStream: ZIO[Has[
    ZioCommand.CommandServiceClient.ZService[Any, Any]  
  ] with AppEnv, Nothing, Queue[CommandProviderOutbound]] = {
    for {
      queue <- Queue.bounded[CommandProviderOutbound](10)
      commandsStream = ZStream
        .fromQueue[Any, io.grpc.Status, CommandProviderOutbound](
          queue
        )
        .tap(cmdOut => log.debug(s"Got command from queue: $cmdOut"))

      commonResponseStream = ZioCommand.CommandServiceClient
        .openStream(commandsStream)
        .mapError(GrpcError.apply)
      _ <- log.debug(s"About to start the response stream")
      _ <- commonResponseStream
        .tap((cmdIn: CommandProviderInbound) =>
          log.debug(s"Got CommandProviderInbound from Axon: $cmdIn")
        )
        .mapM {
          case CommandProviderInbound(
                CommandProviderInbound.Request.Ack(
                  InstructionAck(instructionId, _, _, _)
                ),
                _,
                _
              ) =>
            NamedPromises.complete(instructionId)
            
          case outbound => log.info(s"Not processing $outbound")
        }
        .runDrain
        .flatMap(_ => log.debug("Common response stream terminated"))
        .forkDaemon
      _ <- log.debug(s"Started the response stream")
    } yield queue
  }

  val live = ZLayer.fromServicesM { 
    (log: Logger[String], client: ZioCommand.CommandServiceClient.ZService[Any, Any], namedPromises: NamedPromises.Service) =>
    for {
      q <- openCommandsStream 
    } yield new Service {
      override def sendPermits(count: Long): AIO[Unit] = for {
        outcome <- q.offer(
          CommandProviderOutbound(request =
            CommandProviderOutbound.Request
              .FlowControl(FlowControl(clientId = "test", permits = count))
          )
        )
        _ <- log.debug(s"Send $count permits")
      } yield ()

      override def sendCommand(
          command: Command
      ): AIO[CommandResponse] =
        for {
          _ <- log.debug(s"Sending command $command")
          response <- ZioCommand.CommandServiceClient
            .dispatch(command)
            .provideLayer(ZLayer.succeed(client))
            .mapError(GrpcError.apply)
          _ <- log.debug(s"Got command response: $response")
        } yield response

      override def registerHandler(
          commandName: String,
          handler: RIO[ZEnv, Unit]
      ): AIO[Unit] = {

        val instructionId = UUID.randomUUID.toString

        for {
          outcome <- q.offer(
            CommandProviderOutbound(
              CommandProviderOutbound.Request.Subscribe(
                CommandSubscription(
                  messageId = UUID.randomUUID.toString,
                  command = commandName,
                  componentName = "test",
                  clientId = "1",
                  loadFactor = 100
                )
              ),
              instructionId = instructionId
            )
          )
          _ <- log.debug(s"Waiting for $instructionId to complete")
          _ <- namedPromises.createAndAwait(instructionId)
          _ <- log.debug(s"Completed $instructionId")
        } yield ()
      }

    }
  }
}
