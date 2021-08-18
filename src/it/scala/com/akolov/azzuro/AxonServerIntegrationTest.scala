package com.akolov.azzuro

import scalapb.zio_grpc.ZManagedChannel
import io.grpc.ManagedChannelBuilder

import scalapb.zio_grpc.ZManagedChannel
import io.grpc.ManagedChannelBuilder
import zio._
import io.axoniq.axonserver.grpc.command.command.ZioCommand

trait AxonServerIntegrationTest {

  val channel = ZManagedChannel {
    val builder: ManagedChannelBuilder[_] = ManagedChannelBuilder
      .forAddress("localhost", 8124)
      .usePlaintext()
    builder
  }

  val clientLayer: ZLayer[Any, Throwable, Has[
    ZioCommand.CommandServiceClient.ZService[Any, Any]
  ]] = ZioCommand.CommandServiceClient.live(channel)
}
