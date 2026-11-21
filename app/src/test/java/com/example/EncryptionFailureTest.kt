package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CryptoManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EncryptionFailureTest {
    @Test
    fun `invalid AES key fails closed instead of returning input text`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val crypto = CryptoManager(context)
        val plaintext = "must never be transmitted as plaintext"

        val failure = runCatching {
            crypto.encryptTextWithKey(ByteArray(3), plaintext)
        }.exceptionOrNull()

        assertTrue("Encryption failure should be explicit", failure is IllegalStateException)
        assertTrue("Failure must not return plaintext", failure?.message?.contains("refusing to send plaintext") == true)
    }
}
