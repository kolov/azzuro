package com.akolov.azzuro.common

import zio.macros.accessible

import zio._
import zio.logging.Logging
import zio.logging.Logger

@accessible
object NamedPromises {

  type NamedPromises = Has[Service]

  trait Service {
    def createAndAwait(name: String): UIO[Unit]
    def complete(name: String): UIO[Unit]
  }

  val live: ZLayer[Logging, Nothing, NamedPromises] = ZLayer.fromServiceM {
    log: Logger[String] =>
      RefM
        .make[Map[String, Promise[Nothing, Unit]]](Map.empty)
        .map(map =>
          new Service {

            override def createAndAwait(name: String): UIO[Unit] =
              for {
                promise <- Promise.make[Nothing, Unit]
                _ <- map
                  .update(m => ZIO.effectTotal(m.updated(name, promise)))
                _ <- promise.await
              } yield ()

            override def complete(name: String): UIO[Unit] = map.update { m =>
              for {
                _ <- m
                  .get(name)
                  .map(p => {
                    log.debug(s"Completing $name") *> p.complete(ZIO.unit)
                  })
                  .getOrElse(
                    log.debug(s"Did not find $name") *> ZIO.unit
                  )
                updated <- ZIO.effectTotal(m.removed(name))
              } yield updated
            }

          }
        )
  }
}
