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
import com.akolov.azzuro.common.CommandExecutor
import com.akolov.azzuro.common.Serde
import scala.reflect._
import io.axoniq.axonserver.grpc.common.SerializedObject
import com.google.protobuf.ByteString

@accessible
object AzzuroCommands {
  type AzzuroCommands = Has[Service]

  trait Service {
    def registerHandler[T: ClassTag](
        handler: PartialFunction[Any, Task[Any]],
        serde: Serde[Any]
    ): AIO[Unit]

    def registerHandler[T: ClassTag, R](
        handler: PartialFunction[Any, ZIO[R, Throwable, Any]],
        serde: Serde[Any],
        env: ZLayer[Any, Throwable, R]
    ): AIO[Unit] = registerHandler[T](
      handler.andThen { case io: ZIO[R, Throwable, Any] =>
        io.provideLayer(env)
      },
      serde
    )
    def sendCommand[T: ClassTag](command: T): AIO[CommandResponse]

    def sendPermits(count: Long): AIO[Unit]
  }

  private def openCommandsStream: ZIO[Has[
    ZioCommand.CommandServiceClient.ZService[Any, Any]
  ] with AppEnv with Has[CommandExecutor.Service], Nothing, Queue[
    CommandProviderOutbound
  ]] = {
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

          case CommandProviderInbound(
                CommandProviderInbound.Request.Command(
                  Command(_, name, _, payload, _, _, _, _, _)
                ),
                _,
                _
              ) =>
            val body = payload
              .map(p => p.data.toStringUtf8)
              .getOrElse("unknown")

            log.debug(
              s"About to process command $name with body [$body], $payload"
            ) *>
              CommandExecutor.execute(
                name,
                body
              )
          case outbound => log.info(s"Not processing $outbound")
        }
        .runDrain
        .flatMap(_ => log.debug("Common response stream terminated"))
        .forkDaemon
      _ <- log.debug(s"Started the response stream")
    } yield queue
  }

  def live = ZLayer.fromServicesM {
    (
        log: Logger[String],
        client: ZioCommand.CommandServiceClient.ZService[Any, Any],
        namedPromises: NamedPromises.Service,
        commandsExecutor: CommandExecutor.Service
    ) =>
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

        override def sendCommand[T: ClassTag](
            cmd: T
        ): AIO[CommandResponse] = {
          val classTag = implicitly[ClassTag[T]]
          val commandName = classTag.runtimeClass.getCanonicalName

          for {
            commandInfoOpt <- commandsExecutor.getCommandInfo(commandName)
            commandInfo <- ZIO
              .fromOption(commandInfoOpt)
              .orElseFail(CommandNotRegistered(commandName))
            command = Command(
              messageIdentifier = UUID.randomUUID().toString,
              name = commandName,
              payload = Some(
                SerializedObject(
                  `type` = commandName,
                  data = ByteString.copyFrom(
                    commandInfo.serde.ser(cmd),
                    "utf-8"
                  )
                )
              ),
              componentName = "test",
              timestamp = System.currentTimeMillis()
            )

            _ <- log.debug(s"Sending command $command")
            response <- ZioCommand.CommandServiceClient
              .dispatch(command)
              .provideLayer(ZLayer.succeed(client))
              .mapError(GrpcError.apply)
            _ <- log.debug(s"Got command response: $response")
          } yield response
        }

        override def registerHandler[T: ClassTag](
            handler: PartialFunction[Any, Task[Any]],
            serde: Serde[Any]
        ): AIO[Unit] = {

          val instructionId = UUID.randomUUID.toString
          val classTag = implicitly[ClassTag[T]]
          val commandName = classTag.runtimeClass.getCanonicalName
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
            _ <- log.debug(
              s"registered handler for $commandName, Waiting for $instructionId to complete"
            )
            _ <- namedPromises.createAndAwait(instructionId)
            _ <- commandsExecutor.register(commandName, handler, serde)
            _ <- log.debug(s"Completed $instructionId")
          } yield ()
        }

      }
  }
}
