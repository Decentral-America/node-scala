# DecentralChain private node

The image is useful for developing dApps and other smart contracts on the DecentralChain blockchain.

## Getting started

To run the node,\
`docker run -d --name dcc-private-node -p 6869:6869 ghcr.io/decentral-america/node-scala:private`

To view node API documentation, open http://localhost:6869/

## Preserve blockchain state

If you want to keep the blockchain state, then just stop the container instead of killing it, and start it again when needed:\
`docker stop dcc-private-node`
`docker start dcc-private-node`

## Configuration details

The node is configured with:

- faster generation of blocks (**10 sec** interval)
- all features pre-activated
- custom chain id - **R**
- api_key `dcc-private-node`
- default miner account with all DecentralChain tokens (you can distribute these tokens to other accounts as you wish):
  ```
  rich account:
      Seed text:           waves private node seed with waves tokens
      Seed:                TBXHUUcVx2n3Rgszpu5MCybRaR86JGmqCWp7XKh7czU57ox5dgjdX4K4
      Account seed:        HewBh5uTNEGLVpmDPkJoHEi5vbZ6uk7fjKdP5ghiXKBs
      Private account key: 83M4HnCQxrDMzUQqwmxfTVJPTE9WdE7zjAooZZm2jCyV
      Public account key:  AXbaBkJNocyrVpwqTzD4TpUY8fQ6eeRto9k1m2bNCzXV
      Account address:     3M4qwDomRabJKLZxuXhwfqLApQkU592nWxF
  ```

Full node configuration is available on Github in `decentralchain.custom.conf`.
