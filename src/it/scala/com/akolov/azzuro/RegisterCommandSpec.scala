package com.akolov.azzuro

package com.akolov.azzuro

import zio.test.DefaultRunnableSpec
import io.axoniq.axonserver.grpc.command.command.Command
import zio.test.environment.TestConsole
import io.axoniq.axonserver.grpc.command.command.CommandServiceGrpc
import io.axoniq.axonserver.grpc.command.command.ZioCommand
import io.axoniq.axonserver.grpc.common.SerializedObject
import scalapb.zio_grpc.ZManagedChannel
import io.grpc.ManagedChannelBuilder
import zio._
import io.axoniq.axonserver.grpc.command.command.CommandProviderOutbound
import io.grpc.Status
import io.axoniq.axonserver.grpc.command.command.CommandResponse
import zio.test._
import Assertion._
import zio.test.TestAspect._
import zio.duration._

object RegisterCommandSpec
    extends DefaultRunnableSpec
    with AxonServerIntegrationTest {
  def spec = suite("Commands ")(
    testM("Register command succeeds") {
      case class MyCommand(name: String)
      val cmd = Command(
        messageIdentifier = "",
        name = "asd",
        payload = Some(SerializedObject("" ))
      )

      

      val io: ZIO[ZEnv with Has[AzzuroCommands.Service],AzzuroError,Unit]   = 
        for {
          _ <- AzzuroCommands.registerHandler("asd", console.putStrLn("GotCommand"))
          response <- AzzuroCommands.sendCommand(cmd)
          _ <- console.putStr(response.toString()).mapError(UnexpectedError.apply)
        } yield ()
         
      for {
        response <- io.provideCustomLayer(ZEnv.live  >+> clientLayer >+> AzzuroCommands.live)
        _ <- ZIO.sleep(2.seconds)
      } yield assert(response.toString)(
        equalTo("ss")
      )
    } @@ timeout(2.seconds)
  )
}
