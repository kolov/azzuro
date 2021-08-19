#!/bin/sh
docker run -d --rm --name axonserver -p 8024:8024 -p 8124:8124 -v `pwd`/axonserver/config:/config axoniq/axonserver