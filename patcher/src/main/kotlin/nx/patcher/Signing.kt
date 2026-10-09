package nx.patcher

import com.android.apksig.ApkSigner
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * The key patched apps are signed with. It is created once and kept, because Android only
 * accepts updates signed with the same key.
 */
class SigningKey(val privateKey: PrivateKey, val certificate: X509Certificate) {
    companion object {
        private const val KEY_FILE = "signing-key.pk8"
        private const val CERT_FILE = "signing-cert.der"

        fun loadOrCreate(dir: File): SigningKey {
            val keyFile = File(dir, KEY_FILE)
            val certFile = File(dir, CERT_FILE)
            if (keyFile.exists() && certFile.exists()) {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                }
                return SigningKey(key, cert)
            }
            return generate().also {
                dir.mkdirs()
                keyFile.writeBytes(it.privateKey.encoded)
                certFile.writeBytes(it.certificate.encoded)
            }
        }

        fun generate(): SigningKey {
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(4096) }.generateKeyPair()
            val name = X500Name("CN=NX Manager")
            val now = System.currentTimeMillis()
            val holder = X509v3CertificateBuilder(
                name,
                BigInteger(64, SecureRandom()),
                Date(now - TimeUnit.DAYS.toMillis(1)),
                Date(now + TimeUnit.DAYS.toMillis(365L * 30)),
                name,
                SubjectPublicKeyInfo.getInstance(pair.public.encoded),
            ).build(JcaContentSignerBuilder("SHA256withRSA").build(pair.private))
            return SigningKey(pair.private, JcaX509CertificateConverter().getCertificate(holder))
        }
    }
}

/** Signs [input] into [output] with APK Signature Scheme v2 and v3, aligning entries. */
fun signApk(input: File, output: File, key: SigningKey) {
    val signer = ApkSigner.SignerConfig.Builder("NX", key.privateKey, listOf(key.certificate)).build()
    ApkSigner.Builder(listOf(signer))
        .setInputApk(input)
        .setOutputApk(output)
        .setV1SigningEnabled(false)
        .setV2SigningEnabled(true)
        .setV3SigningEnabled(true)
        .setAlignmentPreserved(false)
        // Android 15+ devices may use 16 KB memory pages.
        .setLibraryPageAlignmentBytes(16 * 1024)
        .build()
        .sign()
}
