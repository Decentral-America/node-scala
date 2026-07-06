package com.decentralchain.actor

import com.typesafe.config.Config
import com.decentralchain.utils.ScorexLogging
import org.apache.pekko.actor.ActorSystem

import scala.concurrent.Await
import scala.concurrent.duration.Duration

object RootActorSystem extends ScorexLogging {
  def start(id: String, config: Config)(init: ActorSystem => Unit): Unit = {
    val system = ActorSystem(id, config)
    try {
      init(system)
    } catch {
      case t: Throwable =>
        log.error(s"Error while initializing actor system $id", t)
        sys.exit(1)
    }

    Await.result(system.whenTerminated, Duration.Inf)
    sys.exit(0)
  }
}
