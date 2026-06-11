# DecentralChain Node (node-scala)

**DecentralChain** is an open-source blockchain platform forked from [Dcc 1.6.x](https://github.com/Decentral-America/DCC).
This repository (node-scala) is the Scala-based full node for the DecentralChain network, featuring the RIDE smart contract language.

## Overview

The DecentralChain node connects to the blockchain network and provides:

- Processing and validation of transactions
- Generation and storage of blocks
- Network communication with other nodes
- REST API (compatible with Dcc API)
- Extensions management

## Getting started

*Prerequisites:*
- Java 25 JDK — [Eclipse Temurin 25](https://adoptium.net/temurin/releases/?version=25) recommended
- A network configuration file from [node/](./node)

Linux:
```bash
# Install Eclipse Temurin 25 (recommended)
wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo apt-key add -
echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update && sudo apt-get install -y temurin-25-jdk
java -jar node/target/dcc-all*.jar path/to/config/decentralchain-{network}.conf
```

Mac (with Homebrew):
```bash
brew install --cask temurin@25
java -jar node/target/dcc-all*.jar path/to/config/decentralchain-{network}.conf
```

Windows (with Eclipse Temurin 25 installed):
```bash
java -jar node/target/dcc-all*.jar path/to/config/decentralchain-{network}.conf
```

## Configuration

Network-specific default configurations are available in [network-defaults.conf](./node/src/main/resources/network-defaults.conf).

## Development

The node can be built and installed wherever Java can run.

### Setup the environment

```bash
# Ubuntu — Eclipse Temurin 25 JDK
wget -O - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo apt-key add -
echo "deb https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update && sudo apt-get install -y temurin-25-jdk
# or
# brew install --cask temurin@25                        # Mac
```

Install SBT (Scala Build Tool) for your platform: [Linux](https://www.scala-sbt.org/1.0/docs/Installing-sbt-on-Linux.html) | [Mac](https://www.scala-sbt.org/1.0/docs/Installing-sbt-on-Mac.html) | [Windows](https://www.scala-sbt.org/1.0/docs/Installing-sbt-on-Windows.html)

### Clone this repo

```bash
git clone https://github.com/Decentral-America/node-scala.git
cd node-scala
```

### Compile and run tests

```bash
sbt checkPR
```

### Run integration tests (optional)

```bash
sbt node-it/docker
sbt -Ddcc.it.max-parallel-suites=1 node-it/test
```

### Build packages

```bash
sbt packageAll                   # Mainnet
sbt -Dnetwork=testnet packageAll # Testnet
```

### Install DEB package

```bash
sudo dpkg -i node/target/*.deb
```

### Configure IntelliJ IDEA (optional)

1. Click Add configuration (or Edit configurations...).
2. Click + to add a new configuration, choose Application.
3. Specify:
   - Main class: com.decentralchain.Application
   - Program arguments: /path/to/configuration
   - Use classpath of module: extension-module
4. Click OK.
5. Run this configuration.

## Contributing

Fork the repository and use a feature branch. Pull requests are welcome.

For major changes, please open an issue first. Please follow the [code of conduct](./CODE_OF_CONDUCT.md).

## Links

- **Predecessor repo:** [Decentral-America/DCC](https://github.com/Decentral-America/DCC) (based on Dcc 1.6.x)
- **Website:** [decentralchain.io](https://decentralchain.io)
- **API docs:** [DecentralChain REST API](https://docs.decentralchain.io/en/dcc-node/node-api/)

## Licence

The code in this project is licensed under [MIT license](./LICENSE)

## Acknowledgements

We use YourKit full-featured Java Profiler to make the DecentralChain node faster. YourKit, LLC is the creator of innovative and intelligent tools for profiling Java and .NET applications.
