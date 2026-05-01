package com.openide.jupyter.kernel

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MessageSigner {

    fun sign(key: String, header: String, parentHeader: String, metadata: String, content: String): String {
        if (key.isEmpty()) return ""
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        mac.update(header.toByteArray())
        mac.update(parentHeader.toByteArray())
        mac.update(metadata.toByteArray())
        mac.update(content.toByteArray())
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }
}
