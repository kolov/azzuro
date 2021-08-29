package com.akolov.azzuro.module

import com.akolov.azzuro.{
  AIO,
  CommandNotRegistered,
  GrpcCommandClient,
  GrpcError
}
import com.akolov.azzuro.common.CommandExecutor._
import com.akolov.azzuro.common.NamedPromises._
import com.akolov.azzuro.common.{CommandExecutor, NamedPromises, Serde}
import com.google.protobuf.ByteString
import io.axoniq.axonserver.grpc.command.command._
import io.axoniq.axonserver.grpc.common.{
  ErrorMessage,
  FlowControl,
  InstructionAck,
  SerializedObject
}
import zio._
import zio.clock.Clock
import zio.logging._
import zio.macros.accessible
import zio.stream.ZStream

import java.util.UUID
import scala.reflect._

@accessible
object AzzuroCommands {
  type AzzuroCommands = Has[Service]

  type CommandHandler[C, A] = C => Task[A]
  type CommandHandlerR[R, C, A] = C => RIO[R, A]

  trait Service {
    def registerHandler[T: ClassTag, A: ClassTag](
        handler: CommandHandler[T, A],
        inSerde: Serde[T],
        outSerde: Serde[A]
    ): AIO[Unit]

    def registerHandlerR[T: ClassTag, R, A: ClassTag](
        handler: CommandHandlerR[R, T, A],
        inSerde: Serde[T],
        outSerde: Serde[A],
        env: ZLayer[Any, Throwable, R]
    ): AIO[Unit] = registerHandler[T, A](
      handler(_).provideLayer(env),
      inSerde,
      outSerde
    )
    def sendCommand[T: ClassTag](command: T): AIO[CommandResponse]

    def sendPermits(count: Long): AIO[Unit]
  }

  private def openCommandsStream
      : ZIO[NamedPromises with CommandExecutor with Logging with Has[
        GrpcCommandClient
      ], Nothing, Queue[CommandProviderOutbound]] = {
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
                  Command(commandIdentifier, name, _, payload, _, _, _, _, _)
                ),
                _,
                _
              ) =>
            val body = payload
              .map(p => p.data.toStringUtf8)
              .getOrElse("unknown")

            for {
              _ <- log.debug(
                s"About to process command $name with body [$body], $payload"
              )
              response <- CommandExecutor
                .execute(
                  name,
                  body
                )
                .fold(
                  error =>
                    io.axoniq.axonserver.grpc.command.command.CommandResponse(
                      UUID.randomUUID().toString,
                      errorCode = "1",
                      errorMessage = Some(ErrorMessage(s"$error")),
                      payload = None,
                      requestIdentifier = commandIdentifier
                    ),
                  result =>
                    io.axoniq.axonserver.grpc.command.command.CommandResponse(
                      UUID.randomUUID().toString,
                      errorCode = "",
                      errorMessage = None,
                      payload = Some(
                        SerializedObject(
                          `type` = result.`type`,
                          data = ByteString.copyFrom(result.data, "utf-8")
                        )
                      ),
                      requestIdentifier = commandIdentifier
                    )
                )
              _ <- queue.offer(
                CommandProviderOutbound(
                  io.axoniq.axonserver.grpc.command.command.CommandProviderOutbound.Request
                    .CommandResponse(
                      response
                    )
                )
              )
            } yield ()
          case outbound => log.info(s"Not processing $outbound")
        }
        .runDrain
        .flatMap(_ => log.debug("Common response stream terminated"))
        .forkDaemon
      _ <- log.debug(s"Started the response stream")
    } yield queue
  }

  /*
  def fromServicesManyM[A0: Tag, A1: Tag, A2: Tag, R, E, B](
    f: (A0, A1, A2) => ZIO[R, E, B]
  ): ZLayer[R with Has[A0] with Has[A1] with Has[A2] with Has[A3], E, B]
   */
  val live: ZLayer[Has[GrpcCommandClient] with Clock with Logging, Nothing, Has[
    Service
  ]] = ZLayer.fromServicesManyM[Logger[
    String
  ], Clock.Service, GrpcCommandClient, Any, Nothing, Has[Service]] {
    (
        log: Logger[String],
        clock: Clock.Service,
        client: GrpcCommandClient
    ) =>
      {

        ZIO
          .environment[Has[Service]]
          .provideLayer(
            (ZLayer.succeed(clock) ++
              ZLayer.succeed(log) >+>
              ZLayer.succeed(client) ++
              NamedPromises.live ++
              CommandExecutor.live) >>> layerWithDependencies
          )

      }
  }
  val layerWithDependencies: ZLayer[Logging with Has[
    GrpcCommandClient
  ] with NamedPromises with CommandExecutor, Nothing, Has[Service]] =
    ZLayer.fromServicesM[Logger[
      String
    ], GrpcCommandClient, NamedPromises.Service, CommandExecutor.Service, NamedPromises with CommandExecutor with Logging with Has[
      GrpcCommandClient
    ], Nothing, Service] {
      (
          log: Logger[String],
          client: GrpcCommandClient,
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
            _ <- log.debug(s"Send $count permits, got response $outcome")
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
                      CommandInfo.serInput(commandInfo, cmd),
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

          override def registerHandler[T: ClassTag, A: ClassTag](
              handler: CommandHandler[T, A],
              inSerde: Serde[T],
              outSerde: Serde[A]
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
              _ <- commandsExecutor.register(
                commandName,
                handler,
                inSerde,
                outSerde
              )
              _ <- log.debug(s"Completed $instructionId")
            } yield ()
          }

        }
    }
}
