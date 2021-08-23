package com.akolov.azzuro.common

import zio._
import com.akolov.azzuro._
import zio.macros.accessible

case class Serde[A](ser: A => String, deser: String => Either[String, A])

@accessible
object CommandExecutor {
  type CommandExecutor = Has[Service]

  trait Service {
    def register(
        name: String,
        handler: PartialFunction[Any, Task[Any]],
        serde: Serde[Any]
    ): AIO[Unit]
    def execute(name: String, command: String): AIO[Any]
  }

  val live: ZLayer[Any, Nothing, CommandExecutor] = ZLayer.fromEffect {

    case class CommandInfo(
        name: String,
        handler: PartialFunction[Any, Task[Any]],
        serde: Serde[Any]
    )

    for {
      ref <- RefM.make(
        Map.empty[String, CommandInfo]
      )
    } yield new Service {

      override def register(
          name: String,
          f: PartialFunction[Any, Task[Any]],
          serde: Serde[Any]
      ): AIO[Unit] =
        ref.update(m =>
          ZIO.succeed(m.updated(name, CommandInfo(name, f, serde)))
        )

      override def execute(name: String, commandSer: String): AIO[Any] = for {
        m <- ref.get
        info <- ZIO
          .fromOption(m.get(name))
          .orElseFail(CommandNotRegistered(name))
        cmd <- ZIO
          .fromEither(info.serde.deser(commandSer))
          .mapError(s => CommandDeserError(s))
        result <- info.handler
          .applyOrElse(
            cmd,
            (c: Any) => ZIO.fail(HandlerDoesNotSupportCommand(c))
          )
          .mapError {
            case t: Throwable   => CommandExecutionError(t)
            case a: AzzuroError => a
          }

      } yield result

    }
  }
}
