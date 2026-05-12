package com.decentralchain.database

import com.decentralchain.common.state.ByteStr
import com.decentralchain.crypto.DigestLength
import com.wavesplatform.protobuf.*

package object protobuf {
  implicit class BlockMetaExt(final val blockMeta: BlockMeta) extends AnyVal {
    def id: ByteStr =
      (if (blockMeta.headerHash.size() == DigestLength) blockMeta.headerHash else blockMeta.signature).toByteStr
  }
}
