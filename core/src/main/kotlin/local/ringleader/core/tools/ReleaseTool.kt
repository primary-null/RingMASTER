package local.ringleader.core.tools

import java.nio.file.*
import java.nio.file.attribute.PosixFilePermissions
import java.security.*
import java.security.spec.*
import java.util.Base64
import local.ringleader.core.verifyUpdateSignature

/** Private key bytes never go to stdout, logs or the repository. */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "generate" -> {
            require(args.size == 2) { "generate <private-key-path>" }
            val path = Path.of(args[1]).toAbsolutePath()
            val pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
            val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64,byteArrayOf(10)).encodeToString(pair.private.encoded) + "\n-----END PRIVATE KEY-----\n"
            // CREATE_NEW is intentional: never overwrite an owner's existing key.
            val attrs = if (path.fileSystem.supportedFileAttributeViews().contains("posix")) arrayOf(PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) else emptyArray()
            Files.newByteChannel(path,setOf(StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE),*attrs).use { it.write(java.nio.ByteBuffer.wrap(pem.toByteArray())) }
            println(Base64.getEncoder().encodeToString(pair.public.encoded))
        }
        "sign" -> {
            require(args.size == 3) { "sign <private-key-path> <manifest-path>" }
            val pem = Files.readString(Path.of(args[1])).replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").filterNot(Char::isWhitespace)
            val key = KeyFactory.getInstance("Ed25519").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(pem)))
            val manifest = Path.of(args[2])
            val sig = Signature.getInstance("Ed25519").run { initSign(key); update(Files.readAllBytes(manifest)); sign() }
            Files.writeString(manifest.resolveSibling("${manifest.fileName}.sig"),Base64.getEncoder().encodeToString(sig) + "\n")
        }
        "verify" -> {
            require(args.size == 3) { "verify <public-SPKI-base64> <manifest-path>" }
            val manifest = Path.of(args[2])
            check(verifyUpdateSignature(Files.readAllBytes(manifest),Files.readAllBytes(manifest.resolveSibling("${manifest.fileName}.sig")),listOf(args[1]))) { "Signature could not be verified" }
        }
        else -> error("Use generate, sign or verify")
    }
}
