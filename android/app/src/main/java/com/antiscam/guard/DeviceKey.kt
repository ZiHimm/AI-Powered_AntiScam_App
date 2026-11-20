package com.antiscam.guard

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * This phone's ECDSA P-256 identity ("Mum's device") for the family relay.
 * The private key lives in the Android Keystore and never leaves the phone.
 *
 * Wire format matches the mobile/ web app (WebCrypto):
 *  - public key as JWK {kty, crv, x, y}
 *  - envelope {body: "<json string>", sig: base64(r||s)}   (IEEE P1363, not DER)
 */
object DeviceKey {
    private const val ALIAS = "antiscam-family-device"
    private const val SIG_ALG = "SHA256withECDSA"
    private val ks: KeyStore by lazy { KeyStore.getInstance("AndroidKeyStore").apply { load(null) } }

    @Synchronized private fun ensure() {
        if (ks.containsAlias(ALIAS)) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build()
            )
        }.generateKeyPair()
    }

    private val publicKey: ECPublicKey get() { ensure(); return ks.getCertificate(ALIAS).publicKey as ECPublicKey }
    private val privateKey: PrivateKey get() { ensure(); return ks.getKey(ALIAS, null) as PrivateKey }

    fun jwk(): JSONObject {
        val w = publicKey.w
        return JSONObject().put("kty", "EC").put("crv", "P-256")
            .put("x", b64url(fixed32(w.affineX))).put("y", b64url(fixed32(w.affineY)))
    }

    val fingerprint: String get() = fingerprint(jwk())

    /** Same as the web app: first 6 bytes of SHA-256("x.y"), colon-separated hex. */
    fun fingerprint(jwk: JSONObject): String {
        val h = MessageDigest.getInstance("SHA-256").digest("${jwk.getString("x")}.${jwk.getString("y")}".toByteArray())
        return h.take(6).joinToString(":") { "%02x".format(it) }
    }

    fun sign(payload: JSONObject): JSONObject {
        val body = payload.toString()
        val der = Signature.getInstance(SIG_ALG).run { initSign(privateKey); update(body.toByteArray()); sign() }
        return JSONObject().put("body", body).put("sig", Base64.encodeToString(derToP1363(der), Base64.NO_WRAP))
    }

    fun publicKeyFromJwk(jwk: JSONObject): PublicKey {
        val x = BigInteger(1, unb64url(jwk.getString("x")))
        val y = BigInteger(1, unb64url(jwk.getString("y")))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), publicKey.params))
    }

    fun verify(env: JSONObject, key: PublicKey): Boolean = try {
        val sig = Base64.decode(env.getString("sig"), Base64.DEFAULT)
        Signature.getInstance(SIG_ALG).run {
            initVerify(key); update(env.getString("body").toByteArray()); verify(p1363ToDer(sig))
        }
    } catch (_: Throwable) { false }

    /** Deletes this phone's key (a new one is made on next use). Other phones will then see a new fingerprint. */
    fun reset() { if (ks.containsAlias(ALIAS)) ks.deleteEntry(ALIAS) }

    // ---------------------------------------------------------------- encoding helpers

    private fun fixed32(n: BigInteger): ByteArray {
        val b = n.toByteArray().dropWhile { it == 0.toByte() }.toByteArray()
        return ByteArray(32 - b.size) + b
    }

    private fun b64url(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    private fun unb64url(s: String): ByteArray = Base64.decode(s, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    /** DER SEQUENCE{INTEGER r, INTEGER s} → 64-byte r||s. */
    private fun derToP1363(der: ByteArray): ByteArray {
        var i = 2 // skip SEQUENCE tag + length (always < 128 bytes for P-256)
        fun readInt(): ByteArray {
            require(der[i] == 0x02.toByte()); val len = der[i + 1].toInt(); i += 2
            val v = der.copyOfRange(i, i + len).dropWhile { it == 0.toByte() }.toByteArray(); i += len
            return ByteArray(32 - v.size) + v
        }
        return readInt() + readInt()
    }

    /** 64-byte r||s → DER SEQUENCE{INTEGER r, INTEGER s}. */
    private fun p1363ToDer(sig: ByteArray): ByteArray {
        require(sig.size == 64)
        fun int(part: ByteArray): ByteArray {
            var v = part.dropWhile { it == 0.toByte() }.toByteArray()
            if (v.isEmpty()) v = byteArrayOf(0)
            if (v[0] < 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = int(sig.copyOfRange(0, 32)) + int(sig.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
