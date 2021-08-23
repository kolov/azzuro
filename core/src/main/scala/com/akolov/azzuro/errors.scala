package com.akolov.azzuro

import io.grpc.Status

trait AzzuroError
case class GrpcError(status: Status) extends AzzuroError
case class UnexpectedError(t: Throwable) extends AzzuroError
case class CommandNotRegistered(name: String) extends AzzuroError
case class CommandDeserError(name: String) extends AzzuroError
case class CommandExecutionError(origin: Throwable) extends AzzuroError
case class HandlerDoesNotSupportCommand(cmd: Any) extends AzzuroError
