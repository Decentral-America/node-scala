#!/bin/bash

DCC_VERSION=$(cut -d\" -f2 version.sbt)

docker run \
  -v "$PWD":/src \
  -e HOME=/opt/sbt/home \
  -w /src \
  --rm -it ghcr.io/decentral-america/node-sbt-builder:$DCC_VERSION \
  /bin/sh -c "sbt --batch packageAll"
