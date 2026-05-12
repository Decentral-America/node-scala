package com.decentralchain.it.grpc

import com.typesafe.config.Config
import com.decentralchain.it.NodeConfigs
import com.decentralchain.it.NodeConfigs.Default
import com.decentralchain.it.sync.grpc.GrpcBaseTransactionSuite
import io.grpc.{CallOptions, ManagedChannelBuilder}
import io.grpc.reflection.v1.ServerReflectionGrpc.getServerReflectionInfoMethod
import io.grpc.reflection.v1.ServerReflectionRequest
import io.grpc.stub.ClientCalls

import scala.util.Try

class GrpcReflectionApiSuite extends GrpcBaseTransactionSuite {
  override protected def nodeConfigs: Seq[Config] =
    NodeConfigs
      .Builder(Default, 1, Seq())
      .overrideBase(_.quorum(0))
      .overrideBase(_.raw("waves.extensions = [com.decentralchain.api.grpc.GRPCServerExtension\ncom.decentralchain.events.BlockchainUpdates]"))
      .buildNonConflicting()

  test("successful getServerReflectionInfo call for BU") {
    val buChannel = ManagedChannelBuilder
      .forAddress(nodes.head.nodeApiEndpoint.getHost, nodes.head.nodeExternalPort(6881))
      .usePlaintext()
      .build()
    val call    = buChannel.newCall(getServerReflectionInfoMethod, CallOptions.DEFAULT)
    val request = ServerReflectionRequest.newBuilder().setFileContainingSymbol("waves.events.grpc.BlockchainUpdatesApi").build()
    val result  = Try(ClientCalls.blockingUnaryCall(call, request))
    result.isSuccess shouldBe true
    result.get.hasFileDescriptorResponse shouldBe true
  }

  test("successful getServerReflectionInfo call for GRPC methods") {
    val call    = nodes.head.grpcChannel.newCall(getServerReflectionInfoMethod, CallOptions.DEFAULT)
    val request = ServerReflectionRequest.newBuilder().setFileContainingSymbol("waves.node.grpc.BlocksApi").build()
    val result  = Try(ClientCalls.blockingUnaryCall(call, request))
    result.isSuccess shouldBe true
    result.get.hasFileDescriptorResponse shouldBe true
  }
}
