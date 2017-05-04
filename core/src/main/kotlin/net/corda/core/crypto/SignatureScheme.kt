package net.corda.core.crypto

import java.security.Signature
import java.security.spec.AlgorithmParameterSpec

/**
 * This class is used to define a digital signature scheme.
 * @param schemeNumberID we assign a number ID for more efficient on-wire serialisation. Please ensure uniqueness between schemes.
 * @param schemeCodeName code name for this signature scheme (e.g. RSA_SHA256, ECDSA_SECP256K1_SHA256, ECDSA_SECP256R1_SHA256, EDDSA_ED25519_SHA512, SPHINCS-256_SHA512).
 * @param providerName the provider's name (e.g. "BC").
 * @param algorithmName which signature algorithm is used (e.g. RSA, ECDSA. EdDSA, SPHINCS-256).
 * @param signatureName a signature-scheme name as required to create [Signature] objects (e.g. "SHA256withECDSA")
 * @param algSpec parameter specs for the underlying algorithm. Note that RSA is defined by the key size rather than algSpec.
 * eg. ECGenParameterSpec("secp256k1").
 * @param keySize the private key size (currently used for RSA only).
 * @param desc a human-readable description for this scheme.
 */
data class SignatureScheme(
        val schemeNumberID: Int,
        val schemeCodeName: String,
        val providerName: String,
        val algorithmName: String,
        val signatureName: String,
        val algSpec: AlgorithmParameterSpec?,
        val keySize: Int,
        val desc: String)
