package com.decentralchain.lang.evaluator

import com.decentralchain.lang.directives.values.V6
import com.decentralchain.lang.v1.FunctionHeader.Native
import com.decentralchain.lang.v1.compiler.Terms.{CONST_LONG, FUNCTION_CALL}
import com.decentralchain.lang.v1.evaluator.FunctionIds.{MUL_LONG, SUB_LONG, SUM_LONG}
import com.decentralchain.test.*

/** c80c4d1c39: once HotSpot compiles the *Exact intrinsics hot, they may throw a preallocated
  * ArithmeticException with no message; RIDE must still report "long overflow" on every node.
  * 30k evaluations per op are enough to get the intrinsics C2-compiled and fast-throwing.
  */
class LongOverflowMessageStabilityTest extends EvaluatorSpec {
  private def call(id: Short, a: Long, b: Long) = FUNCTION_CALL(Native(id), List(CONST_LONG(a), CONST_LONG(b)))

  property("+, - and * overflow always report \"long overflow\", also after JIT warm-up") {
    Seq(call(SUM_LONG, Long.MaxValue, 1), call(SUB_LONG, Long.MinValue, 1), call(MUL_LONG, Long.MaxValue, 2)).foreach { expr =>
      (1 to 30000).foreach { i =>
        withClue(s"iteration $i: ") {
          evalExpr(expr, V6, lastVersion) should produce("long overflow")
        }
      }
    }
  }
}
