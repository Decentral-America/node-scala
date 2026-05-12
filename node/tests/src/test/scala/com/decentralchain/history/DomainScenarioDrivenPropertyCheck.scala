package com.decentralchain.history

import com.decentralchain.db.WithDomain
import com.decentralchain.settings.DCCSettings
import org.scalacheck.Gen
import org.scalatest.Suite
import org.scalatestplus.scalacheck.{ScalaCheckDrivenPropertyChecks as GeneratorDrivenPropertyChecks}

trait DomainScenarioDrivenPropertyCheck extends WithDomain { suite: Suite & GeneratorDrivenPropertyChecks =>
  def scenario[S](gen: Gen[S], bs: DCCSettings = DefaultDCCSettings)(assertion: (Domain, S) => Any): Any =
    forAll(gen) { s =>
      withDomain(bs) { domain =>
        assertion(domain, s)
      }
    }
}
