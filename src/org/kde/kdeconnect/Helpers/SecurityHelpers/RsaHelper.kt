/*
 * SPDX-FileCopyrightText: 2015 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
*/
package org.kde.kdeconnect.Helpers.SecurityHelpers

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.preference.PreferenceManager
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

object RsaHelper {
    private const val RSA = "RSA" // KeyProperties.KEY_ALGORITHM_RSA isn't available until API 23+

    @JvmStatic
    fun initialiseRsaKeys(context: Context?) {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)

        if (!settings.contains("publicKey") || !settings.contains("privateKey")) {
            val keyPair: KeyPair
            val keyAlgorithm: String
            try {
                val newSdk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                keyAlgorithm = if (newSdk) KeyProperties.KEY_ALGORITHM_EC else RSA
                val generator = KeyPairGenerator.getInstance(keyAlgorithm)
                if (newSdk) {
                    val spec = ECGenParameterSpec("secp256r1")
                    generator.initialize(spec)
                }
                else {
                    generator.initialize(2048)
                }
                keyPair = generator.generateKeyPair()
            }
            catch (e: Exception) {
                Log.e("KDE/initializeRsaKeys", "Exception", e)
                return
            }

            val publicKey = keyPair.public.encoded
            val privateKey = keyPair.private.encoded

            settings.edit().apply {
                putString("publicKey", Base64.encodeToString(publicKey, 0))
                putString("privateKey", Base64.encodeToString(privateKey, 0))
                putString("keyAlgorithm", keyAlgorithm)
                apply()
            }
        }
    }

    /** For backwards compat: if no keyAlgorithm setting is set, it means it was generated using RSA */
    private fun algorithmFromSettings(pref: SharedPreferences) = pref.getString("keyAlgorithm", RSA)

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun getPublicKey(context: Context?): PublicKey {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        val publicKeyBytes = Base64.decode(settings.getString("publicKey", ""), 0)
        return KeyFactory.getInstance(algorithmFromSettings(settings)).generatePublic(X509EncodedKeySpec(publicKeyBytes))
    }

    @JvmStatic
    @Throws(GeneralSecurityException::class)
    fun getPrivateKey(context: Context?): PrivateKey {
        val settings = PreferenceManager.getDefaultSharedPreferences(context)
        val privateKeyBytes = Base64.decode(settings.getString("privateKey", ""), 0)
        return KeyFactory.getInstance(algorithmFromSettings(settings)).generatePrivate(PKCS8EncodedKeySpec(privateKeyBytes))
    }
}
