# renovate: image=eclipse-temurin tag=25-jre-noble
ARG baseImage=eclipse-temurin:25-jre-noble@sha256:f9bd8815e73632c22985ebb133ec49b9fc4ad5ffe0657594ac02748ad0431ab7
FROM $baseImage

ARG TARGETARCH
ARG CORRETTO_VERSION=2.5.0
ARG ROCKSDB_VERSION=10.2.1
# Supply-chain pinning: SHA-256 hashes verified from Maven Central at release time.
# Update alongside CORRETTO_VERSION.
ARG CORRETTO_AMD64_SHA256=94e92977ee52af1f50cf6744684c214f300e668ee6e1793be1802b209543dc9f
ARG CORRETTO_ARM64_SHA256=e03899f5cc33de706b5059a62107b5a3262dd2816d4295d998f0fb843db0c80d
# Maven Central publishes only SHA-1 for rocksdbjni; update alongside ROCKSDB_VERSION.
# See https://github.com/facebook/rocksdb/issues/13893 before bumping ROCKSDB_VERSION.
ARG ROCKSDB_LINUX64_SHA1=1fb1d6519cd2a7ff61478f766fa985baa890c7b9

ENV WAVES_LOG_LEVEL=INFO
ENV WAVES_LOG_JAVA_OPTS=true

ENV WAVES_HEAP_SIZE=2g
ENV WAVES_NETWORK=mainnet

SHELL ["/bin/bash", "-c"]

# Install wget for downloading runtime JARs
RUN apt-get update && apt-get upgrade -y --no-install-recommends && apt-get install -y --no-install-recommends wget && rm -rf /var/lib/apt/lists/*

# Node files
ENV DCC_DATA=/var/lib/dcc
ENV DCC_LOG=/var/log/dcc
ENV DCC_INSTALL_PATH=/usr/share/dcc
ENV DCC_CONFIG=/etc/dcc/dcc.conf
ENV DCC_LOG_LEVEL=INFO

RUN set -euo pipefail && \
    if [ "$TARGETARCH" = "amd64" ]; then \
        export CORRETTO_ARCH="linux-x86_64"; \
        export CORRETTO_SHA256="${CORRETTO_AMD64_SHA256}"; \
    elif [ "$TARGETARCH" = "arm64" ]; then \
        export CORRETTO_ARCH="linux-aarch_64"; \
        export CORRETTO_SHA256="${CORRETTO_ARM64_SHA256}"; \
    else \
        echo "ERROR: Unsupported TARGETARCH=${TARGETARCH}" >&2; exit 1; \
    fi && \
    export CORRETTO_JAR="AmazonCorrettoCryptoProvider-${CORRETTO_VERSION}-${CORRETTO_ARCH}.jar" && \
    export ROCKSDB_JAR="rocksdbjni-${ROCKSDB_VERSION}-linux64.jar" && \
    mkdir -p "${DCC_INSTALL_PATH}/lib" && \
    wget -q "https://repo1.maven.org/maven2/software/amazon/cryptools/AmazonCorrettoCryptoProvider/${CORRETTO_VERSION}/${CORRETTO_JAR}" \
         -O "${DCC_INSTALL_PATH}/lib/software.amazon.cryptools.${CORRETTO_JAR}" && \
    echo "${CORRETTO_SHA256}  ${DCC_INSTALL_PATH}/lib/software.amazon.cryptools.${CORRETTO_JAR}" | sha256sum -c - && \
    wget -q "https://repo1.maven.org/maven2/org/rocksdb/rocksdbjni/${ROCKSDB_VERSION}/${ROCKSDB_JAR}" \
         -O "${DCC_INSTALL_PATH}/lib/org.rocksdb.${ROCKSDB_JAR}" && \
    echo "${ROCKSDB_LINUX64_SHA1}  ${DCC_INSTALL_PATH}/lib/org.rocksdb.${ROCKSDB_JAR}" | sha1sum -c - && \
    # rocksdbjni was built with Java 8 — unnamed JPMS module calling System.loadLibrary().
    # JDK 25 (JEP 472) requires --enable-native-access for such calls; JDK 26 will hard-block.
    # Add Automatic-Module-Name so --enable-native-access=org.rocksdb (scoped) works instead
    # of --enable-native-access=ALL-UNNAMED (blanket). Uses python3 (available in Ubuntu Noble)
    # because 'jar' is a JDK tool absent from this JRE-only base image.
    python3 - "${DCC_INSTALL_PATH}/lib/org.rocksdb.${ROCKSDB_JAR}" << 'PEOF'
import sys, zipfile, shutil, os, tempfile
jar = sys.argv[1]
with zipfile.ZipFile(jar, 'r') as zin:
    manifest = zin.read('META-INF/MANIFEST.MF').decode('utf-8')
    entries  = [(i, zin.read(i.filename)) for i in zin.infolist()]
if 'Automatic-Module-Name' in manifest:
    sys.exit(0)  # already patched
manifest = manifest.rstrip('\r\n') + '\nAutomatic-Module-Name: org.rocksdb\n'
tmp = jar + '.patching'
with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
    for info, data in entries:
        if info.filename == 'META-INF/MANIFEST.MF':
            data = manifest.encode('utf-8')
        zout.writestr(info, data)
os.replace(tmp, jar)
print(f'patched {jar}: added Automatic-Module-Name: org.rocksdb')
PEOF

# Setup node
COPY entrypoint.sh $DCC_INSTALL_PATH/bin/entrypoint.sh
RUN mkdir -p $DCC_DATA $DCC_LOG; \
    # Create user
    groupadd -r dcc --gid=999; \
    useradd --no-log-init -r -g dcc --uid=999 --home-dir=$DCC_DATA --shell=/bin/bash dcc

RUN --mount=type=bind,source=target,target=/tmp/ \
    # Unpack tgz packages
    tar zxf /tmp/dcc.tgz -C $DCC_INSTALL_PATH --strip-components=1 --exclude '*AmazonCorrettoCryptoProvider*.jar' --exclude '*conscrypt*.jar' --exclude '*rocksdbjni*' && \
    tar zxf /tmp/dcc-grpc-server.tgz -C $DCC_INSTALL_PATH --strip-components=1 && \
    # Set permissions
    chown -R dcc:dcc $DCC_DATA $DCC_LOG $DCC_INSTALL_PATH && chmod 755 $DCC_DATA $DCC_LOG;

# Only expose REST API and mainnet P2P by default; override in docker-compose for other networks
EXPOSE 6869 6868
VOLUME $DCC_DATA
VOLUME $DCC_LOG
VOLUME $DCC_INSTALL_PATH/lib/plugins
WORKDIR $DCC_DATA

HEALTHCHECK CMD wget -q -O /dev/null http://localhost:6869/node/status || exit 1

STOPSIGNAL SIGINT
USER dcc
ENTRYPOINT ["/usr/share/dcc/bin/entrypoint.sh"]
