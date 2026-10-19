package com.decentralchain.state

opaque type GeneratorIndex = Int
object GeneratorIndex {
  def apply(x: Int): GeneratorIndex = {
    require(x >= 0, s"Wrong generator index: $x. Expected >= 0")
    x
  }

  /** `None` for every negative input (`-1` is the `indexWhere` "not found" sentinel; anything lower is
    * malformed input, e.g. a wire `endorser_index`). Total by design: a throwing guard here once let a
    * single bad P2P message terminate the EndorseBlock subscription (audit AUD-005).
    */
  def checked(x: Int): Option[GeneratorIndex] = Option.when(x >= 0)(x)

  def seq(xs: Seq[Int]): Seq[GeneratorIndex]       = xs.map(apply)
  def unsafeSeq(xs: Seq[Int]): Seq[GeneratorIndex] = xs

  extension (self: GeneratorIndex) {
    def toInt: Int = self
  }

  def toInts(xs: Seq[GeneratorIndex]): Seq[Int] = xs

  given Ordering[GeneratorIndex]                            = Ordering[Int]
  given Conversion[GeneratorIndex, Ordered[GeneratorIndex]] = scala.math.Ordered.orderingToOrdered(_)
}
