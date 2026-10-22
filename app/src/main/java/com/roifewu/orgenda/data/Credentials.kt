package com.roifewu.orgenda.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class Credentials(context: Context) {
    private val preferences = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val alias = "orgenda.credentials.v1"

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as SecretKey?)?.let {
            return it
        }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .apply {
                init(
                    KeyGenParameterSpec.Builder(
                            alias,
                            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build()
                )
            }
            .generateKey()
    }

    fun save(id: String, password: String) {
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, key())
                updateAAD(id.toByteArray())
            }
        val encrypted = cipher.doFinal(password.toByteArray())
        check(
            preferences
                .edit()
                .putString(id, Base64.getEncoder().encodeToString(cipher.iv + encrypted))
                .commit()
        )
    }

    fun remove(id: String) {
        check(preferences.edit().remove(id).commit())
    }

    fun read(id: String): String {
        val value =
            preferences.getString(id, null)
                ?: throw com.roifewu.orgenda.core.StorageFailure.Authentication()
        return try {
            val bytes = Base64.getDecoder().decode(value)
            val cipher =
                Cipher.getInstance("AES/GCM/NoPadding").apply {
                    init(
                        Cipher.DECRYPT_MODE,
                        key(),
                        GCMParameterSpec(128, bytes.copyOfRange(0, 12)),
                    )
                    updateAAD(id.toByteArray())
                }
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            throw com.roifewu.orgenda.core.StorageFailure.Authentication()
        }
    }
}
