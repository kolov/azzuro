addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.5")
//addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.1.13")

val zioGrpcVersion = "0.4.2"

addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.0")

libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % zioGrpcVersion

