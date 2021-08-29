
# Azzuro
ZIO-based Axon IQ Server client.
Write Event Sourcing applications backed by the Axon IQ Server in pure FP Scala, without any of the Axon Framework.
 

This is in early development stage. There is a sample application that registers a handler for one command type, then
sends a commands and executes it. To run it, first start Axon Server locally 

    docker run -d --rm --name axonserver -p 8024:8024 -p 8124:8124 -v `pwd`/axonserver/config:/config axoniq/axonserver

and then:

    sbt "project simple-example" "runMain com.akolov.azzuro.example.simple.SimpleApplication"

# Development

There is no working image for M1. Clone and build (mvn package) axon-server-se, then

    java -jar ../axon-server-se/axonserver/target/axonserver-4.6.0-SNAPSHOT-exec.jar
 
