#!/bin/bash

DCC_VERSION=$(cut -d\" -f2 ../version.sbt)

docker build \
  --build-arg SBT_VERSION=$(cut -d= -f2 ../project/build.properties) \
  --build-arg DCC_VERSION=$DCC_VERSION \
  --pull \
  -t ghcr.io/decentral-america/node-sbt-builder:$DCC_VERSION \
  - < node-sbt-builder.Dockerfile
