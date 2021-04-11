<h1 align="center">🔷 Decentralchain Platform Node</h1>

> Decentralchain is an open source blockchain platform.<br /> 
You can use it to build your own decentralised applications. Decentralchain provides full blockchain ecosystem including smart contracts language called RIDE.


## 🚀️ Getting started

A quick introduction of the minimal setup you need to get a running node.

Linux systems:
```bash
sudo apt-get update
sudo apt-get install openjdk-8-jre
java -jar node/target/decentralchain-all*.jar path/to/config/decentralchain-{network}.conf
```

Mac systems (assuming already installed homebrew):
```bash
brew cask install adoptopenjdk/openjdk/adoptopenjdk8
java -jar node/target/decentralchain-all*.jar path/to/config/decentralchain-{network}.conf
```

Windows systems (assuming already installed OpenJDK 8):
```bash
java -jar node/target/decentralchain-all*.jar path/to/config/decentralchain-{network}.conf
```

## 📝 Licence

The code in this project is licensed under [MIT license](./LICENSE)

## 👏 Acknowledgements

[<img src="https://camo.githubusercontent.com/97fa03cac759a772255b93c64ab1c9f76a103681/68747470733a2f2f7777772e796f75726b69742e636f6d2f696d616765732f796b6c6f676f2e706e67">](https://www.yourkit.com/)

We use YourKit full-featured Java Profiler to make Waves node faster. YourKit, LLC is the creator of innovative and intelligent tools for profiling Java and .NET applications.

Take a look at YourKit's leading software products: YourKit Java Profiler and YourKit .NET Profiler.

We use wavesplatform as base code. 