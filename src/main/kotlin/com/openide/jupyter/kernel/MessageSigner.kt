package com.openide.jupyter.kernel

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MessageSigner {

    const val DEFAULT_SIGNATURE_SCHEME: String = "hmac-sha256"

    private val supportedAlgorithms = mapOf(
        "hmac-sha256" to "HmacSHA256",
        "hmac-sha384" to "HmacSHA384",
        "hmac-sha512" to "HmacSHA512"
    )

    fun sign(key: String, header: String, parentHeader: String, metadata: String, content: String): String {
        return sign(
            key,
            DEFAULT_SIGNATURE_SCHEME,
            header.toByteArray(StandardCharsets.UTF_8),
            parentHeader.toByteArray(StandardCharsets.UTF_8),
            metadata.toByteArray(StandardCharsets.UTF_8),
            content.toByteArray(StandardCharsets.UTF_8)
        )
    }

    fun sign(key: String, signatureScheme: String, vararg parts: ByteArray): String {
        val algorithm = algorithmFor(signatureScheme)
        if (key.isEmpty()) return ""
        val mac = Mac.getInstance(algorithm)
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), algorithm))
        parts.forEach(mac::update)
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    fun verify(
        key: String,
        signatureScheme: String,
        signature: ByteArray,
        vararg parts: ByteArray
    ): Boolean {
        validateSignatureScheme(signatureScheme)
        if (key.isEmpty()) return signature.isEmpty()
        val expected = sign(key, signatureScheme, *parts)
            .toByteArray(StandardCharsets.US_ASCII)
        return MessageDigest.isEqual(expected, signature)
    }

    fun validateSignatureScheme(signatureScheme: String) {
        algorithmFor(signatureScheme)
    }

    private fun algorithmFor(signatureScheme: String): String {
        val normalized = signatureScheme.lowercase()
        return supportedAlgorithms[normalized]
            ?: throw IllegalArgumentException(
                "Unsupported Jupyter signature scheme '$signatureScheme'; " +
                    "supported schemes: ${supportedAlgorithms.keys.joinToString()}"
            )
    }
}
