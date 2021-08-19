package com.akolov
import zio._
import zio.logging.Logging
import zio.clock.Clock

package object azzuro {
  type AppEnv = Clock with Logging

  type AIO[A] = ZIO[AppEnv, AzzuroError, A]
}
