package com.toss.cardapp.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// AES-256-GCM with a non-exportable Android Keystore key. Blob layout: "CA1" | 12-byte IV | ciphertext+tag.
object Vault {
    private const val ALIAS = "cardapp-store-v1"
    private val MAGIC = "CA1".toByteArray()
    private const val IV_LEN = 12

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        return MAGIC + c.iv + c.doFinal(plain)
    }

    fun decrypt(blob: ByteArray): ByteArray {
        require(blob.size > MAGIC.size + IV_LEN && blob.copyOf(MAGIC.size).contentEquals(MAGIC)) { "not a vault blob" }
        val iv = blob.copyOfRange(MAGIC.size, MAGIC.size + IV_LEN)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        return c.doFinal(blob, MAGIC.size + IV_LEN, blob.size - MAGIC.size - IV_LEN)
    }
}

class SecureFile(context: Context, name: String) {
    private val file = File(context.filesDir, name)

    fun read(): String? = if (file.exists()) String(Vault.decrypt(file.readBytes()), Charsets.UTF_8) else null

    fun write(text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(Vault.encrypt(text.toByteArray(Charsets.UTF_8)))
        check(tmp.renameTo(file)) { "rename failed for ${file.name}" }
    }

    fun delete() {
        file.delete()
    }
}
