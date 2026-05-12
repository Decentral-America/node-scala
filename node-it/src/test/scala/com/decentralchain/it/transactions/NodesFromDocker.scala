package com.decentralchain.it.transactions

import com.decentralchain.it.{Docker, DockerBased, Node, Nodes}
import monix.eval.Coeval
import org.scalatest.Suite

trait NodesFromDocker extends Nodes with DockerBased { suite: Suite =>
  protected val dockerNodes: Coeval[Seq[Docker.DockerNode]] = dockerSingleton
    .map(_.startNodes(nodeConfigs))
    .memoize

  override protected def nodes: Seq[Node] = dockerNodes()
}
