package com.decentralchain.api.grpc

import com.decentralchain.crypto
import com.decentralchain.utils.*
import io.grpc.*

import java.security.MessageDigest

/** gRPC server interceptor that validates API key from metadata.
  * If apiKeyHash is empty, all requests are rejected.
  */
class ApiKeyInterceptor(apiKeyHash: Array[Byte]) extends ServerInterceptor {
  private val API_KEY_HEADER: Metadata.Key[String] =
    Metadata.Key.of("X-Api-Key", Metadata.ASCII_STRING_MARSHALLER)

  override def interceptCall[ReqT, RespT](
      call: ServerCall[ReqT, RespT],
      headers: Metadata,
      next: ServerCallHandler[ReqT, RespT]
  ): ServerCall.Listener[ReqT] = {
    if (apiKeyHash.isEmpty) {
      call.close(Status.UNAUTHENTICATED.withDescription("API key not configured"), new Metadata())
      return new ServerCall.Listener[ReqT] {}
    }

    val apiKey = headers.get(API_KEY_HEADER)
    if (apiKey != null && constantTimeEquals(crypto.secureHash(apiKey.utf8Bytes), apiKeyHash)) {
      next.startCall(call, headers)
    } else {
      call.close(Status.UNAUTHENTICATED.withDescription("Invalid API key"), new Metadata())
      new ServerCall.Listener[ReqT] {}
    }
  }

  /** Constant-time comparison to prevent timing attacks */
  private def constantTimeEquals(a: Array[Byte], b: Array[Byte]): Boolean =
    MessageDigest.isEqual(a, b)
}
