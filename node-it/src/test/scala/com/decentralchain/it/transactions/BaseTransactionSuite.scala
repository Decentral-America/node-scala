package com.decentralchain.it.transactions

import com.decentralchain.it.*
import org.scalatest.*

trait BaseTransactionSuiteLike extends IntegrationSuiteWithThreeAddresses with NodesFromDocker {
  this: TestSuite & Nodes =>

}

abstract class BaseTransactionSuite extends funsuite.AnyFunSuite with BaseTransactionSuiteLike
