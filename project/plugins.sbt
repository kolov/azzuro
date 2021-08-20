addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.7.5") 
 
addSbtPlugin("com.thesamet" % "sbt-protoc" % "1.0.2")

libraryDependencies += "com.thesamet.scalapb.zio-grpc" %% "zio-grpc-codegen" % "0.5.0"

