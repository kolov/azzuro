package com.akolov

import zio._
import zio.logging.Logging
import zio.clock.Clock
import io.axoniq.axonserver.grpc.command.command.ZioCommand

package object azzuro {
  type AppEnv = Clock with Logging

  type GrpcCommandClient = ZioCommand.CommandServiceClient.ZService[Any, Any]

  type AIO[A] = ZIO[Any, AzzuroError, A]
}
