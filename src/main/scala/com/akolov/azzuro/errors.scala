package com.akolov.azzuro

import io.grpc.Status

trait AzzuroError
case class GrpcError(status: Status) extends AzzuroError
case class UnexpectedError(t: Throwable) extends AzzuroError
