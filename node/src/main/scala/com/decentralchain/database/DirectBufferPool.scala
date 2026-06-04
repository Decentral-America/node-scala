package com.decentralchain.database

import java.nio.ByteBuffer
import scala.collection.mutable

/** Thread-local pool of direct ByteBuffers.
  *
  * Replaces the internal JDK API `sun.nio.ch.Util.getTemporaryDirectBuffer` / `releaseTemporaryDirectBuffer` with a
  * public-API-only implementation. The JDK internal was sealed in Java 16+ (JEP 396) and removed from unnamed module
  * access in Java 25 (JEP 403 finalization), causing `IllegalAccessError` at runtime.
  *
  * Design: each thread maintains a small stack of reusable direct ByteBuffers. When a buffer is requested, the pool
  * returns one of sufficient capacity (or allocates a new one). When released, the buffer is returned to the pool for
  * reuse. This eliminates per-call `ByteBuffer.allocateDirect` overhead, which is expensive because the JVM must
  * negotiate off-heap memory with the OS.
  *
  * This is the same strategy used by Netty's `PooledByteBufAllocator`, gRPC-Java's `BufferPool`, and the JDK's own
  * `sun.nio.ch.Util` — but using only `java.nio.ByteBuffer.allocateDirect` (public API).
  */
object DirectBufferPool {

  // Per-thread buffer cache. Using a simple ArrayDeque as a LIFO stack.
  // Each entry is a direct ByteBuffer. Buffers are reused if capacity >= requested size.
  // Max cached buffers per thread — prevents unbounded growth in bursty workloads.
  private val MaxCachedPerThread = 16

  private val cache: ThreadLocal[mutable.ArrayDeque[ByteBuffer]] =
    ThreadLocal.withInitial(() => new mutable.ArrayDeque[ByteBuffer](MaxCachedPerThread))

  /** Get a direct ByteBuffer with at least `capacity` bytes. The buffer's position is 0 and limit is `capacity`. */
  def get(capacity: Int): ByteBuffer = {
    val pool = cache.get()
    // Search for a buffer with sufficient capacity (LIFO order for cache locality)
    var i    = pool.length - 1
    var best = -1
    while (i >= 0) {
      if (pool(i).capacity() >= capacity) {
        best = i
        i = -1 // found, break
      } else {
        i -= 1
      }
    }
    if (best >= 0) {
      val buf = pool.remove(best)
      buf.clear()
      buf.limit(capacity)
      buf
    } else {
      ByteBuffer.allocateDirect(capacity)
    }
  }

  /** Return a direct ByteBuffer to the pool for reuse. The buffer must not be used after this call. */
  def release(buf: ByteBuffer): Unit =
    if (buf != null && buf.isDirect) {
      val pool = cache.get()
      if (pool.length < MaxCachedPerThread) {
        pool.append(buf)
      }
      // else: drop the buffer — it will be GC'd and the off-heap memory reclaimed by the JVM's direct buffer cleaner
    }
}
