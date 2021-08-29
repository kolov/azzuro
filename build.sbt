import Dependencies._
import com.typesafe.sbt.packager.docker._

ThisBuild / scalaVersion := "2.13.3"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / organization := "leantyped"

val grpcVersion = "1.39.0"
val zioVersion = "1.0.10"
val circeVersion = "0.14.1"

val nixDockerSettings = List(
  name := "azzuro",
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
ThisBuild / testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
ThisBuild /  PB.protocVersion := "3.17.3"

val commonSettings = Seq(
  scalacOptions ++= Seq(
    "-Ymacro-annotations",
    "-Wconf:src=src_managed/.*:silent",
    "-Wconf:any:error"
  )
)
lazy val core = (project in file("core"))
  .settings(
    Defaults.itSettings,
    commonSettings,
    licenses += ("Apache-2.0", new URL(
      "https://www.apache.org/licenses/LICENSE-2.0.txt"
    )),
    libraryDependencies ++= Seq(
      catsCore,
      scalaTest % Test
    ), 
    libraryDependencies ++= Seq(
      "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion,
      "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf",
      "io.grpc" % "grpc-netty" % grpcVersion
    ),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-macros" % zioVersion,
      "dev.zio" %% "zio-logging-slf4j" % "0.5.11"
    ),
    libraryDependencies ++= Seq(
      "com.google.protobuf" % "protobuf-java" % "3.13.0"
    ),
    libraryDependencies ++= Seq(
      "ch.qos.logback" % "logback-classic" % "1.1.7"
    ),
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio-test" % zioVersion % "it,test",
      "dev.zio" %% "zio-test-sbt" % zioVersion % "it,test",
      "dev.zio" %% "zio-test-magnolia" % zioVersion % "it,test" // optional
    ),
   
    Compile / PB.targets := Seq(
      PB.gens.java -> (Compile / sourceManaged).value / "scalapb",
      scalapb
        .gen(grpc = true) -> (Compile / sourceManaged).value / "scalapb",
      scalapb.zio_grpc.ZioCodeGenerator -> (Compile / sourceManaged).value / "scalapb"
    )
    
  )
  .configs(IntegrationTest)

lazy val `simple-example` = (project in file("example/simple"))
  .dependsOn(core)
  .enablePlugins(JavaAppPackaging)
  .enablePlugins(DockerPlugin)
  .settings(
    commonSettings ++ nixDockerSettings,
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % circeVersion)
  )

lazy val root = (project in file("."))
  .aggregate(`simple-example`, core)
