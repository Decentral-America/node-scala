# To build: docker build -t ghcr.io/decentral-america/ride-runner:latest -f docker/Dockerfile docker
# JDK for now, because I need jmap, e.g. ijmap -dump:format=b,file=heap-dump.hprof
# renovate: image=eclipse-temurin tag=17-jdk-jammy
FROM eclipse-temurin:17-jdk-jammy@sha256:beabb759e6f9653c843958d1d1f5cecb881dfb85aa6081e2bef099ab1260344e

SHELL ["/bin/bash", "-c"]

# async profiler
# /usr/local/async-profiler/build/libasyncProfiler.so
# NOTE: v4.4 is latest (async-profiler/async-profiler) — upgrade when convenient.
ENV ASYNCPROF_VERSION=2.9
ENV ASYNCPROF_SHA256=b9a094bc480f233f72141b7793c098800054438e0e6cfe5b7f2fe13ef4ad11f0
RUN export ASYNCPROF_ARCHIVE="async-profiler-${ASYNCPROF_VERSION}-linux-x64.tar.gz"; \
    wget --quiet "https://github.com/jvm-profiling-tools/async-profiler/releases/download/v${ASYNCPROF_VERSION}/${ASYNCPROF_ARCHIVE}" -P /tmp/ && \
      echo "${ASYNCPROF_SHA256}  /tmp/${ASYNCPROF_ARCHIVE}" | sha256sum -c - && \
      mkdir /usr/local/async-profiler; tar -xvzf /tmp/$ASYNCPROF_ARCHIVE --strip-components=1 -C /usr/local/async-profiler || exit 1;

# Additional dependencies
RUN apt-get update && apt-get install -y --no-install-recommends wget unzip gosu libjemalloc-dev graphviz || exit 1; \
    # Clean
    apt-get remove -y wget unzip && apt-get autoremove -y && apt-get autoclean && rm -rf /var/lib/apt/lists/*

ENV RIDE_LOG_LEVEL=INFO
ENV RIDE_HEAP_SIZE=2500m
ENV RIDE_NETWORK=mainnet
ENV RIDE_APP=com.decentralchain.ride.runner.entrypoints.DCCRideRunnerWithBlockchainService

# RIDE runner files
ENV RDATA=/var/lib/decentralchain-ride-runner
ENV RIDE_INSTALL_PATH=/usr/share/decentralchain-ride-runner
ENV RIDE_CONFIG=/etc/decentralchain-ride-runner/main.conf
ENV RIDE_LOGBACK_CONFIG=$RIDE_INSTALL_PATH/doc/logback.xml

# jemalloc options
# Add -e JEMALLOC_ENABLE=true to enable
ENV MALLOC_CONF="prof:true,lg_prof_interval:29,lg_prof_sample:21"

COPY target /tmp/
COPY main.conf $RIDE_CONFIG

# Setup node
COPY entrypoint.sh $RIDE_INSTALL_PATH/bin/entrypoint.sh
RUN mkdir -p $RDATA/heap-dumps/on-exit; \
    # Create user
    groupadd -r ride --gid=999; \
    useradd -r -g ride --uid=999 --home-dir=$RDATA --shell=/bin/bash ride; \
    # Unpack tgz packages
    tar zxvf /tmp/decentralchain-ride-runner.tgz -C $RIDE_INSTALL_PATH --strip-components=1; \
    # Set permissions
    chown -R ride:ride $RDATA $RIDE_INSTALL_PATH && chmod 755 $RDATA; \
    # Cleanup
    rm -rf /tmp/*

EXPOSE 6890 9095
VOLUME $RDATA
WORKDIR $RDATA

HEALTHCHECK CMD curl -f http://localhost:6890/ride/status || exit 1

STOPSIGNAL SIGINT
ENTRYPOINT ["/usr/share/decentralchain-ride-runner/bin/entrypoint.sh"]
