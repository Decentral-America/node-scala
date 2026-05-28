package com.decentralchain.test

import com.decentralchain.utils.{ApplicationStopReason, applicationStopHandler}

import java.util.concurrent.Semaphore

trait HasExitInterceptor {

  protected def withExitInterceptor(okIf: ApplicationStopReason)(f: Semaphore => Unit): Int = {
    var stopReasonCode = 0

    val signal = new Semaphore(1)
    signal.acquire()

    val originalHandler = applicationStopHandler.get()
    applicationStopHandler.set { reason =>
      signal.synchronized {
        stopReasonCode = reason.code
        if (reason.code == okIf.code)
          signal.release()
        throw new SecurityException("System exit is not allowed")
      }
    }

    try {
      f(signal)
      stopReasonCode
    } finally applicationStopHandler.set(originalHandler)
  }
}

@deprecated("Use HasExitInterceptor instead — SecurityManager removed in JDK 24+", "1.0")
trait HasSecurityManager extends HasExitInterceptor {
  protected def withSecurityManager(okIf: ApplicationStopReason)(f: Semaphore => Unit): Int =
    withExitInterceptor(okIf)(f)
}
