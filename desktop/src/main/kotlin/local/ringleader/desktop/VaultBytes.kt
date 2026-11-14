package local.ringleader.desktop

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import local.ringleader.core.VaultException

/** UTF-8 for a secret. The caller wipes [secret]; this wipes its own buffer. Never builds a String. */
internal fun encodeSecretUtf8(secret: CharArray): ByteArray {
    val encoder = Charsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = CharBuffer.wrap(secret)
    val output = ByteBuffer.allocate(secret.size * 4 + 8)
    try {
        val encoded = encoder.encode(input, output, true)
        val flushed = encoder.flush(output)
        if (encoded.isError || flushed.isError || encoded.isOverflow || flushed.isOverflow) {
            throw VaultException("This secret could not be encoded.")
        }
        val size = output.position()
        val bytes = ByteArray(size)
        output.flip()
        output.get(bytes)
        return bytes
    } finally {
        output.array().fill(0)
    }
}

/** The caller wipes [bytes]. The returned array is the caller's to wipe. */
internal fun decodeSecretUtf8(bytes: ByteArray): CharArray {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    val input = ByteBuffer.wrap(bytes)
    val output = CharBuffer.allocate(bytes.size)
    try {
        val decoded = decoder.decode(input, output, true)
        val flushed = decoder.flush(output)
        if (decoded.isError || flushed.isError || decoded.isOverflow || flushed.isOverflow) {
            throw VaultException("The saved secret could not be read.")
        }
        val count = output.position()
        val chars = CharArray(count)
        output.flip()
        output.get(chars)
        return chars
    } finally {
        output.array().fill('\u0000')
    }
}
