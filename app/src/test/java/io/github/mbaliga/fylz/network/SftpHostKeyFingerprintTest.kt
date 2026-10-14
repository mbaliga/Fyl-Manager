package io.github.mbaliga.fylz.network

import net.schmizz.sshj.common.Buffer
import java.security.PublicKey
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * P0.11, defect 11: the pre-fix host-key verifier hashed [PublicKey.getEncoded], the X.509/DER
 * encoding -- not the SSH wire-format blob `ssh-keygen -lf` fingerprints -- so no real fingerprint
 * could ever match one a user copied in, no matter how carefully. [sha256Fingerprint] and
 * [hostKeyVerifierFor] are the fix; verified here against two real keys `ssh-keygen` generated,
 * with their expected `ssh-keygen -lf` output captured as fixtures.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SftpHostKeyFingerprintTest {

    /** A `.pub` file's base64 field is already exactly the SSH wire-format blob
     * `Buffer.PlainBuffer().putPublicKey(key).compactData` would produce for the same key --
     * decoding it straight back into a [PublicKey] is the realistic parse path a live SSH
     * handshake's `HostKeyVerifier.verify(hostname, port, key)` callback hands us. */
    private fun loadPublicKey(resourceName: String): PublicKey {
        val line = requireNotNull(javaClass.classLoader?.getResourceAsStream("sftp/$resourceName")) {
            "Missing test fixture: sftp/$resourceName"
        }.bufferedReader().readText().trim()
        val base64Blob = line.split(Regex("\\s+"))[1]
        val rawBytes = Base64.getDecoder().decode(base64Blob)
        return Buffer.PlainBuffer(rawBytes).readPublicKey()
    }

    // Expected output of `ssh-keygen -lf <file>.pub` for each fixture, captured when generating them.
    private val ed25519Key by lazy { loadPublicKey("ed25519_test.pub") }
    private val ed25519Sha256 = "SHA256:K8/cZUz/eMMAWy6eXAmzKM1s0ptQkcgzw8skdfHmYoo"
    private val ed25519Md5 = "39:b4:6e:ff:b3:f7:dd:1b:f4:3e:14:bb:62:e4:95:8b"

    private val rsaKey by lazy { loadPublicKey("rsa_test.pub") }
    private val rsaSha256 = "SHA256:NdAVKLYL9RFEL4xupGNYDxDhfrThDvz7dKl+5D4kmpw"

    @Test
    fun `sha256Fingerprint matches ssh-keygen -lf for a real ed25519 key`() {
        assertEquals(ed25519Sha256, sha256Fingerprint(ed25519Key))
    }

    @Test
    fun `sha256Fingerprint matches ssh-keygen -lf for a real RSA key`() {
        assertEquals(rsaSha256, sha256Fingerprint(rsaKey))
    }

    @Test
    fun `a verifier pinned to the SHA256 form accepts the matching key and rejects a different one`() {
        val verifier = hostKeyVerifierFor(ed25519Sha256)
        assertTrue(verifier.verify("example.com", 22, ed25519Key))
        assertFalse(verifier.verify("example.com", 22, rsaKey))
    }

    @Test
    fun `a verifier pinned to a bare, unprefixed SHA256 fingerprint still works`() {
        val bare = ed25519Sha256.removePrefix("SHA256:")
        val verifier = hostKeyVerifierFor(bare)
        assertTrue(verifier.verify("example.com", 22, ed25519Key))
    }

    @Test
    fun `a verifier pinned to the legacy MD5 form accepts the matching key`() {
        val verifier = hostKeyVerifierFor(ed25519Md5)
        assertTrue(verifier.verify("example.com", 22, ed25519Key))
        assertFalse(verifier.verify("example.com", 22, rsaKey))
    }

    @Test
    fun `isPlausibleHostKeyFingerprint accepts every real shape and rejects garbage`() {
        assertTrue(isPlausibleHostKeyFingerprint(ed25519Sha256))
        assertTrue(isPlausibleHostKeyFingerprint(ed25519Sha256.removePrefix("SHA256:")))
        assertTrue(isPlausibleHostKeyFingerprint(ed25519Md5))
        assertTrue(isPlausibleHostKeyFingerprint("MD5:$ed25519Md5"))
        assertFalse(isPlausibleHostKeyFingerprint(""))
        assertFalse(isPlausibleHostKeyFingerprint("not a fingerprint"))
        assertFalse(isPlausibleHostKeyFingerprint("SHA256:"))
    }

    @Test
    fun `SftpProviderConfig accepts a legacy MD5 fingerprint, not just SHA-256`() {
        val config = SftpProviderConfig(
            id = "test",
            displayName = "Test",
            host = "example.com",
            username = "user",
            password = "pw".toCharArray(),
            pinnedHostKeySha256 = ed25519Md5,
        )
        assertEquals(ed25519Md5, config.pinnedHostKeySha256)
    }

    @Test
    fun `CapturingHostKeyVerifier remembers the key but never accepts a connection`() {
        // This is what makes trust-on-first-use safe: probeHostKeyFingerprint's verifier can
        // observe a key, but a probe can never itself complete as a trusted connection.
        val verifier = CapturingHostKeyVerifier()
        assertNull(verifier.capturedKey)
        assertFalse(verifier.verify("example.com", 22, ed25519Key))
        assertEquals(ed25519Key, verifier.capturedKey)
    }
}
