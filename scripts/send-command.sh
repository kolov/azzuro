#!/bin/sh

grpcurl \
    -d '{ "message_identifier": "1", "name": "name", "payload": { "type": "c"} }' \
    -import-path ./src/main/protobuf\
    -proto command.proto \
    -plaintext localhost:8124 io.axoniq.axonserver.grpc.command.CommandService.Dispatch

 