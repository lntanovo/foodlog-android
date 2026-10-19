package com.foodlog.app

import android.util.AtomicFile
import com.foodlog.app.cloud.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CloudSessionStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val cipher = object : CloudSessionCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        override fun encrypt(plaintext: ByteArray): ByteArray {
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.ENCRYPT_MODE, key)
            return aes.iv + aes.doFinal(plaintext)
        }
        override fun decrypt(encrypted: ByteArray): ByteArray {
            val aes = Cipher.getInstance("AES/GCM/NoPadding")
            aes.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted.copyOfRange(0, 12)))
            return aes.doFinal(encrypted.copyOfRange(12, encrypted.size))
        }
    }
    private fun session() = CloudSession("private-user", "tester", "private-access-token", "private-refresh-token",
        5_000_000, UUID.randomUUID().toString())

    @Test fun encryptedSessionAndDeviceIdentitySurviveRecreation() {
        val directory = temporary.newFolder("session")
        val store = CloudSessionStore(directory, cipher)
        val original = session()
        val deviceId = store.deviceId()
        store.save(original)
        val bytes = File(directory, "session.enc").readBytes().toString(Charsets.UTF_8)
        assertFalse(bytes.contains(original.uid))
        assertFalse(bytes.contains(original.accessToken))
        assertFalse(bytes.contains(original.refreshToken))
        val recreated = CloudSessionStore(directory, cipher)
        assertEquals(original, recreated.load())
        assertEquals(deviceId, recreated.deviceId())
        recreated.clear()
        assertNull(recreated.load())
        assertEquals(deviceId, recreated.deviceId())
    }

    @Test fun interruptedAtomicWriteKeepsThePreviousCompleteSession() {
        val directory = temporary.newFolder("session")
        val store = CloudSessionStore(directory, cipher)
        val original = session()
        store.save(original)
        val incomplete = AtomicFile(File(directory, "session.enc")).startWrite()
        incomplete.write(byteArrayOf(1, 2, 3))
        incomplete.close()
        assertEquals(original, CloudSessionStore(directory, cipher).load())
    }

    @Test fun corruptedCiphertextCannotBeAcceptedAsAValidSession() {
        val directory = temporary.newFolder("session")
        val sentinel = temporary.newFile("food-records-retained")
        sentinel.writeText("local records")
        val store = CloudSessionStore(directory, cipher)
        store.save(session())
        val file = File(directory, "session.enc")
        val damaged = file.readBytes().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        file.writeBytes(damaged)
        try { store.load(); fail("不能接受被篡改的登录状态") } catch (error: CloudException) {
            assertFalse(error.message!!.contains("private"))
        }
        store.clear()
        assertEquals("local records", sentinel.readText())
        assertNull(store.load())
    }
}
