package com.decentralchain.account

import com.decentralchain.common.utils.EitherExt2.*
import com.decentralchain.test.PropSpec
import org.scalatest.Inside

class AccountOrAliasTests extends PropSpec with Inside {

  property("Account should get parsed correctly") {
    AddressOrAlias.fromString("3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet() shouldBe an[Address]
    AddressOrAlias.fromString("address:3DdAmAhx8nwm8c6rEYnabSMJkayZGv4TUab").explicitGet() shouldBe an[Address]
  }

  property("Alias should get parsed correctly") {
    inside(AddressOrAlias.fromString("alias:?:sasha").explicitGet()) { case alias: Alias =>
      alias.name shouldBe "sasha"
      alias.chainId shouldBe '?'
    }

    val alias2 = Alias.fromString("alias:?:sasha").explicitGet()
    alias2.name shouldBe "sasha"
    alias2.chainId shouldBe '?'
  }

  property("Alias can be from other network") {
    AddressOrAlias.fromString("alias:Q:sasha") shouldBe Alias.createWithChainId("sasha", 'Q'.toByte, Some('Q'.toByte))
  }

  property("Malformed aliases cannot be reconstructed") {
    AddressOrAlias.fromString("alias::sasha") should beLeft
    AddressOrAlias.fromString("alias:T: sasha") should beLeft
    AddressOrAlias.fromString("alias:T:sasha\nivanov") should beLeft
    AddressOrAlias.fromString("alias:T:s") should beLeft
    AddressOrAlias.fromString("alias:TTT:sasha") should beLeft

    Alias.fromString("alias:T: sasha") should beLeft
    Alias.fromString("alias:T:sasha\nivanov") should beLeft
    Alias.fromString("alias::sasha") should beLeft
    Alias.fromString("alias:T:s") should beLeft
    Alias.fromString("alias:TTT:sasha") should beLeft

    Alias.fromString("aliaaas:W:sasha") should beLeft
  }

  property("Unknown address schemes cannot be parsed") {
    AddressOrAlias.fromString("postcode:119072") should beLeft
  }
}
