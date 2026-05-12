package com.decentralchain.code
import com.decentralchain.test.FlatSpec

// See http://eed3si9n.com/herding-cats/partial-unification.html
class PartialUnificationTests extends FlatSpec {
  def foo[F[_], A](fa: F[A]): String = fa.toString

  "Partial unification" should "be enabled" in {
    val result = foo { (x: Int) =>
      x * 2
    }

    assert(result `contains` "Lambda")
  }
}
