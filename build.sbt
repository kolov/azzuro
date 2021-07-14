import Dependencies._
import com.typesafe.sbt.packager.docker._

ThisBuild / scalaVersion := "2.13.3"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "leantyped"

val grpcVersion = "1.34.0"

PB.targets in Compile := Seq(
  scalapb.gen(grpc = true) -> (sourceManaged in Compile).value,
  scalapb.zio_grpc.ZioCodeGenerator -> (sourceManaged in Compile).value
)

val nixDockerSettings = List(
  name := "sbt-nix-azzuro",
  dockerCommands := Seq(
    Cmd("FROM", "base-jre:latest"),
    Cmd("COPY", "1/opt/docker/lib/*.jar", "/lib/"),
    Cmd("COPY", "2/opt/docker/lib/*.jar", "/app.jar"),
    ExecCmd(
      "ENTRYPOINT",
      "java",
      "-cp",
      "/app.jar:/lib/*",
      "leantyped.azzuro.Hello"
    )
  )
)

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .settings(
    licenses += ("Apache-2.0", new URL(
      "https://www.apache.org/licenses/LICENSE-2.0.txt"
    )),
    libraryDependencies ++= Seq(
      catsCore,
      scalaTest % Test
    ),
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "io.grpc" % "grpc-netty" % grpcVersion
    ),
    libraryDependencies ++= Seq(
      "com.google.protobuf" % "protobuf-java" % "3.13.0" % "protobuf"
    ),
    scalacOptions ++= Seq(
      "-Wconf:src=src_managed/.*:silent",
      "-Wconf:any:error"
    )
  )
  .settings(nixDockerSettings: _*)
