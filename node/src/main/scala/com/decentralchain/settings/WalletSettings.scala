package com.decentralchain.settings

import pureconfig.*
import java.io.File

import com.decentralchain.common.state.ByteStr

case class WalletSettings(file: Option[File], password: Option[String], seed: Option[ByteStr]) derives ConfigReader
