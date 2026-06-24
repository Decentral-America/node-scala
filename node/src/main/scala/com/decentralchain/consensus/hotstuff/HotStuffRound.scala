package com.decentralchain.consensus.hotstuff

sealed trait HotStuffRound {
  def code: Byte
  def next: Option[HotStuffRound]
  def name: String
}

object HotStuffRound {
  case object Prepare extends HotStuffRound {
    val code: Byte                  = 0
    val next: Option[HotStuffRound] = Some(PreCommit)
    val name: String                = "Prepare"
  }

  case object PreCommit extends HotStuffRound {
    val code: Byte                  = 1
    val next: Option[HotStuffRound] = Some(Commit)
    val name: String                = "PreCommit"
  }

  case object Commit extends HotStuffRound {
    val code: Byte                  = 2
    val next: Option[HotStuffRound] = None
    val name: String                = "Commit"
  }

  val all: Seq[HotStuffRound] = Seq(Prepare, PreCommit, Commit)

  def fromCode(b: Byte): Option[HotStuffRound] = b match {
    case 0 => Some(Prepare)
    case 1 => Some(PreCommit)
    case 2 => Some(Commit)
    case _ => None
  }
}
