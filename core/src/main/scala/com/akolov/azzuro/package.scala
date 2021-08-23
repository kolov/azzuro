package com.akolov

import zio._
import zio.logging.Logging
import zio.clock.Clock

package object azzuro {
  type AppEnv = Clock with Logging with NamedPromises.NamedPromises

  type AIO[A] = ZIO[Any, AzzuroError, A]
}
