package com.foodlog.app.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.FileOutputStream
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal interface CloudSessionPersistence {
    fun load(): CloudSession?
    fun save(session: CloudSession)
    fun clear()
    fun deviceId(): String
}

internal interface CloudSessionCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(encrypted: ByteArray): ByteArray
}

/** Sessions and device IDs never participate in Android backup or exported FoodLog backups. */
internal class CloudSessionStore internal constructor(
    directory: File,
    private val cipher: CloudSessionCipher,
) : CloudSessionPersistence {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, "cloud-session"),
        AndroidCloudSessionCipher(),
    )

    private val root = directory
    private val sessionFile = AtomicFile(File(root, "session.enc"))
    private val deviceFile = AtomicFile(File(root, "device-id"))
    private val json = Json { ignoreUnknownKeys = true }

    @Synchronized override fun load(): CloudSession? {
        val bytes = read(sessionFile, MAX_FILE_BYTES) ?: return null
        return try {
            val session = json.decodeFromString<CloudSession>(cipher.decrypt(bytes).toString(Charsets.UTF_8))
            if (session.uid.isBlank() || session.uid.length > 128 || session.username.isBlank() ||
                session.username.length > 64 || session.accessToken.isBlank() || session.accessToken.length > 8192 ||
                session.refreshToken.isBlank() || session.refreshToken.length > 2048 ||
                session.expiresAtMillis <= 0 || UUID.fromString(session.generation).toString() != session.generation) {
                throw IllegalArgumentException()
            }
            session
        } catch (_: Exception) {
            throw CloudException("无法读取安全登录状态，请重新登录")
        }
    }

    @Synchronized override fun save(session: CloudSession) {
        try {
            val plaintext = json.encodeToString(CloudSession.serializer(), session).toByteArray(Charsets.UTF_8)
            val encrypted = cipher.encrypt(plaintext)
            if (encrypted.size > MAX_FILE_BYTES) throw IOException()
            write(sessionFile, encrypted)
        } catch (_: Exception) {
            throw CloudException("无法安全保存登录状态，请检查手机存储空间后重新登录")
        }
    }

    @Synchronized override fun clear() {
        try {
            sessionFile.delete()
            if (sessionFile.baseFile.exists() || File(sessionFile.baseFile.path + ".bak").exists() ||
                File(sessionFile.baseFile.path + ".new").exists()) throw IOException()
        } catch (_: Exception) {
            throw CloudException("无法清除登录状态，请检查手机存储空间后重试")
        }
    }

    @Synchronized override fun deviceId(): String {
        val existing = read(deviceFile, 128)?.toString(Charsets.UTF_8)
        if (existing != null && runCatching { UUID.fromString(existing).toString() == existing }.getOrDefault(false)) {
            return existing
        }
        val id = UUID.randomUUID().toString()
        write(deviceFile, id.toByteArray(Charsets.UTF_8))
        return id
    }

    private fun read(file: AtomicFile, limit: Int): ByteArray? = try {
        file.openRead().use { input ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (bytes.size() + count > limit) throw IOException()
                bytes.write(buffer, 0, count)
            }
            bytes.toByteArray()
        }
    } catch (_: FileNotFoundException) {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) null
        else throw CloudException("无法读取安全登录状态，请检查手机存储空间后重试")
    } catch (_: IOException) {
        throw CloudException("无法读取安全登录状态，请检查手机存储空间后重试")
    }

    private fun write(file: AtomicFile, bytes: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            if (!root.isDirectory && !root.mkdirs()) throw IOException()
            stream = file.startWrite()
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (_: Exception) {
            stream?.let { file.failWrite(it) }
            throw CloudException("无法安全保存登录状态，请检查手机存储空间后重试")
        }
    }

    companion object { private const val MAX_FILE_BYTES = 64 * 1024 }
}

private class AndroidCloudSessionCipher : CloudSessionCipher {
    private val aad = "foodlog-cloud-session-v1".toByteArray(Charsets.UTF_8)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val encryption = Cipher.getInstance("AES/GCM/NoPadding")
        encryption.init(Cipher.ENCRYPT_MODE, key())
        encryption.updateAAD(aad)
        val iv = encryption.iv
        require(iv.size == 12)
        return byteArrayOf(1) + iv + encryption.doFinal(plaintext)
    }

    override fun decrypt(encrypted: ByteArray): ByteArray {
        require(encrypted.size >= 30 && encrypted[0] == 1.toByte())
        val decryption = Cipher.getInstance("AES/GCM/NoPadding")
        decryption.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted.copyOfRange(1, 13)))
        decryption.updateAAD(aad)
        return decryption.doFinal(encrypted.copyOfRange(13, encrypted.size))
    }

    companion object { private const val KEY_ALIAS = "foodlog.cloud.session.v1" }
}
