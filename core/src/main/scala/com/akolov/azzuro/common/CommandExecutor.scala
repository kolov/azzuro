package com.akolov.azzuro.common

import zio._
import com.akolov.azzuro._
import com.akolov.azzuro.module.AzzuroCommands.CommandHandler
import zio.macros.accessible

import scala.reflect.ClassTag

case class Serde[A](ser: A => String, deser: String => Either[String, A])

@accessible
object CommandExecutor {
  type CommandExecutor = Has[Service]

  case class CommandExecutionResult(`type`: String, data: String)

  case class CommandInfo[T, A](
      name: String,
      inClassTag: ClassTag[T],
      outClassTag: ClassTag[A],
      handler: CommandHandler[T, A],
      inSerde: Serde[T],
      outSerde: Serde[A]
  ) {
    def outType = outClassTag.runtimeClass.toString
  }

  object CommandInfo {
    def execute(
        commandInfo: CommandInfo[Any, Any],
        commandSer: String
    ): AIO[CommandExecutionResult] =
      commandInfo.inSerde.deser(commandSer) match {
        case Left(_) => ZIO.fail(CommandDeserError(s"Can't deser $commandSer"))
        case Right(cmd) =>
          commandInfo.inClassTag.unapply(cmd) match {
            case Some(a) =>
              commandInfo.handler
                .apply(a)
                .mapError(UnexpectedError.apply)
                .map(r =>
                  CommandExecutionResult(
                    commandInfo.outType,
                    CommandInfo.serOutput(commandInfo, r)
                  )
                )
            case _ =>
              ZIO.fail(
                CommandDeserError(
                  s"Can't deser $commandSer to type ${commandInfo.inClassTag.runtimeClass.toString}"
                )
              )
          }
      }

    def serInput(commandInfo: CommandInfo[Any, Any], cmd: Any): String = {
      commandInfo.inClassTag.unapply(cmd) match {
        case Some(a) => commandInfo.inSerde.ser(a)
        case _       => "unknown"
      }
    }
    def serOutput(commandInfo: CommandInfo[Any, Any], cmd: Any): String = {
      commandInfo.outClassTag.unapply(cmd) match {
        case Some(a) => commandInfo.outSerde.ser(a)
        case _       => "unknown"
      }
    }
  }

  trait Service {
    def register[T: ClassTag, A: ClassTag](
        name: String,
        handler: CommandHandler[T, A],
        inSerde: Serde[T],
        outSerde: Serde[A]
    ): AIO[Unit]

    def getCommandInfo(name: String): AIO[Option[CommandInfo[Any, Any]]]
    def execute(name: String, command: String): AIO[CommandExecutionResult]
  }

  val live: ZLayer[Any, Nothing, CommandExecutor] = {

    for {
      ref <- RefM.make(
        Map.empty[String, CommandInfo[_, _]]
      )
    } yield new Service {

      override def getCommandInfo(
          name: String
      ): AIO[Option[CommandInfo[Any, Any]]] =
        ref.get
          .map(_.get(name))
          .map(_.map(_.asInstanceOf[CommandInfo[Any, Any]]))

      override def register[T: ClassTag, A: ClassTag](
          name: String,
          f: CommandHandler[T, A],
          inSerde: Serde[T],
          outSerde: Serde[A]
      ): AIO[Unit] =
        ref.update(m =>
          ZIO.succeed(
            m.updated(
              name,
              CommandInfo(
                name,
                implicitly[ClassTag[T]],
                implicitly[ClassTag[A]],
                f,
                inSerde,
                outSerde
              )
            )
          )
        )

      override def execute(
          name: String,
          commandSer: String
      ): AIO[CommandExecutionResult] = for {
        infoOpt <- getCommandInfo(name)
        info <- ZIO.fromOption(infoOpt).orElseFail(CommandNotRegistered(name))
        result <- CommandInfo
          .execute(info, commandSer)
          .mapError {
            case t: Throwable   => CommandExecutionError(t)
            case a: AzzuroError => a
          }
      } yield result
    }
  }.toLayer
}
