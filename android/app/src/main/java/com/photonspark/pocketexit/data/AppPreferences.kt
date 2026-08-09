package com.photonspark.pocketexit.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.security.KeyStore
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AppPreferences(context: Context) : SharedPreferences.OnSharedPreferenceChangeListener, AutoCloseable {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val secretStore = SecretStore(preferences)
    private val mutableState: MutableStateFlow<AgentConfig>

    val state: StateFlow<AgentConfig> get() = mutableState.asStateFlow()
    val current: AgentConfig get() = mutableState.value

    init {
        ensureDefaults()
        mutableState = MutableStateFlow(read())
        preferences.registerOnSharedPreferenceChangeListener(this)
    }

    fun save(config: AgentConfig) {
        val normalizedNodeId = sanitizeNodeId(config.nodeId)
        preferences.edit()
            .putString(KEY_SERVER_URL, config.normalizedServerUrl)
            .putString(KEY_NODE_ID, normalizedNodeId)
            .putString(KEY_DEVICE_NAME, config.deviceName.trim().ifBlank { Build.MODEL })
            .putString(KEY_CONTROL_POLICY, config.controlPolicy.wire)
            .putString(KEY_EXIT_POLICY, config.exitPolicy.wire)
            .putBoolean(KEY_ENABLED, config.enabled)
            .putBoolean(KEY_AUTO_START, config.autoStart)
            .apply()
        if (config.agentToken != current.agentToken) {
            secretStore.put(config.agentToken)
        }
        refresh()
    }

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        refresh()
    }

    fun applyRemotePolicies(control: Policy?, exit: Policy?) {
        val editor = preferences.edit()
        control?.let { editor.putString(KEY_CONTROL_POLICY, it.wire) }
        exit?.let { editor.putString(KEY_EXIT_POLICY, it.wire) }
        editor.apply()
        refresh()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        refresh()
    }

    override fun close() {
        preferences.unregisterOnSharedPreferenceChangeListener(this)
    }

    private fun refresh() {
        mutableState.value = read()
    }

    private fun read(): AgentConfig = AgentConfig(
        serverUrl = preferences.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL).orEmpty(),
        nodeId = preferences.getString(KEY_NODE_ID, "android-node").orEmpty(),
        deviceName = preferences.getString(KEY_DEVICE_NAME, Build.MODEL).orEmpty(),
        agentToken = secretStore.get(),
        controlPolicy = Policy.fromWire(preferences.getString(KEY_CONTROL_POLICY, null), Policy.AUTO),
        exitPolicy = Policy.fromWire(
            preferences.getString(KEY_EXIT_POLICY, null),
            Policy.CELLULAR_PREFERRED,
        ),
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        autoStart = preferences.getBoolean(KEY_AUTO_START, false),
    )

    private fun ensureDefaults() {
        val editor = preferences.edit()
        if (!preferences.contains(KEY_SERVER_URL)) editor.putString(KEY_SERVER_URL, DEFAULT_SERVER_URL)
        if (!preferences.contains(KEY_NODE_ID)) editor.putString(KEY_NODE_ID, defaultNodeId())
        if (!preferences.contains(KEY_DEVICE_NAME)) editor.putString(KEY_DEVICE_NAME, Build.MODEL)
        if (!preferences.contains(KEY_CONTROL_POLICY)) editor.putString(KEY_CONTROL_POLICY, Policy.AUTO.wire)
        if (!preferences.contains(KEY_EXIT_POLICY)) {
            editor.putString(KEY_EXIT_POLICY, Policy.CELLULAR_PREFERRED.wire)
        }
        editor.apply()
    }

    private fun defaultNodeId(): String {
        val model = sanitizeNodeId(Build.MODEL.lowercase(Locale.US))
        val suffix = UUID.randomUUID().toString().take(8)
        return "${model.ifBlank { "android" }}-$suffix"
    }

    private fun sanitizeNodeId(value: String): String = value.trim()
        .replace(Regex("[^A-Za-z0-9._-]"), "-")
        .trim('-')
        .take(64)
        .ifBlank { "android-node" }

    companion object {
        private const val FILE_NAME = "pocket_exit"
        private const val DEFAULT_SERVER_URL = "https://proxy.example.com"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_NODE_ID = "node_id"
        private const val KEY_DEVICE_NAME = "device_name"
        private const val KEY_CONTROL_POLICY = "control_policy"
        private const val KEY_EXIT_POLICY = "exit_policy"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_AUTO_START = "auto_start"
        private const val KEY_ENCRYPTED_TOKEN = "encrypted_agent_token"
    }

    private class SecretStore(private val preferences: SharedPreferences) {
        private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

        fun put(value: String) {
            if (value.isBlank()) {
                preferences.edit().remove(KEY_ENCRYPTED_TOKEN).apply()
                return
            }
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(1 + cipher.iv.size + encrypted.size)
            packed[0] = cipher.iv.size.toByte()
            cipher.iv.copyInto(packed, 1)
            encrypted.copyInto(packed, 1 + cipher.iv.size)
            preferences.edit().putString(
                KEY_ENCRYPTED_TOKEN,
                Base64.encodeToString(packed, Base64.NO_WRAP),
            ).apply()
        }

        fun get(): String {
            val encoded = preferences.getString(KEY_ENCRYPTED_TOKEN, null) ?: return ""
            return runCatching {
                val packed = Base64.decode(encoded, Base64.NO_WRAP)
                require(packed.isNotEmpty())
                val ivLength = packed[0].toInt() and 0xff
                require(ivLength in 12..16 && packed.size > 1 + ivLength)
                val iv = packed.copyOfRange(1, 1 + ivLength)
                val encrypted = packed.copyOfRange(1 + ivLength, packed.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
                String(cipher.doFinal(encrypted), Charsets.UTF_8)
            }.getOrElse {
                preferences.edit().remove(KEY_ENCRYPTED_TOKEN).apply()
                ""
            }
        }

        @Synchronized
        private fun key(): SecretKey {
            (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            generator.init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            return generator.generateKey()
        }

        companion object {
            private const val KEY_ALIAS = "pocket_exit_agent_token_v1"
            private const val TRANSFORMATION = "AES/GCM/NoPadding"
        }
    }
}
