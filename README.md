
# Azzuro
ZIO-based Axon IQ Server client.
Write Event Sourcing applications backed by the Axon IQ Server in pure FP Scala, without any of the Axon Framework.
 

This is in early development stage. There is a sample application that registers a handler for one command type, then
sends a commands and executes it. To run it, first start Axon Server locally and then:

    sbt "project simple-example" "runMain com.akolov.azzuro.example.simple.SimpleApplication"
 
