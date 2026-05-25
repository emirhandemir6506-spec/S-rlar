package com.example.api

import android.content.Context
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

enum class AiProvider(val displayName: String) {
    GEMINI("Gemini"),
    CLAUDE("Claude"),
    OPENAI("OpenAI"),
    KIMI("Kimi")
}

class AiService(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val sharedPreferences = context.getSharedPreferences("ai_terminal_prefs", Context.MODE_PRIVATE)

    fun getApiKey(provider: AiProvider): String {
        val storedKey = sharedPreferences.getString("api_key_${provider.name}", "") ?: ""
        if (storedKey.isNotEmpty()) {
            return storedKey
        }
        return when (provider) {
            AiProvider.GEMINI -> BuildConfig.GEMINI_API_KEY
            AiProvider.CLAUDE -> BuildConfig.CLAUDE_API_KEY
            AiProvider.OPENAI -> BuildConfig.OPENAI_API_KEY
            AiProvider.KIMI -> BuildConfig.KIMI_API_KEY
        }
    }

    fun setApiKey(provider: AiProvider, key: String) {
        sharedPreferences.edit().putString("api_key_${provider.name}", key).apply()
    }

    fun clearApiKey(provider: AiProvider) {
        sharedPreferences.edit().remove("api_key_${provider.name}").apply()
    }

    fun getStoredProvider(): AiProvider {
        val name = sharedPreferences.getString("active_provider", AiProvider.GEMINI.name) ?: AiProvider.GEMINI.name
        return try {
            AiProvider.valueOf(name)
        } catch (e: Exception) {
            AiProvider.GEMINI
        }
    }

    fun setStoredProvider(provider: AiProvider) {
        sharedPreferences.edit().putString("active_provider", provider.name).apply()
    }

    suspend fun queryAi(provider: AiProvider, prompt: String): String = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(provider)
        if (apiKey.isEmpty() || apiKey.startsWith("MY_") || apiKey == "MY_GEMINI_API_KEY" || apiKey == "MY_CLAUDE_API_KEY" || apiKey == "MY_OPENAI_API_KEY" || apiKey == "MY_KIMI_API_KEY") {
            return@withContext "Hata: API anahtarı ayarlanmamış! Lütfen '!set key <anahtar>' veya '!set key ${provider.name.lowercase()} <anahtar>' komutu ile anahtarınızı ekleyin."
        }

        try {
            when (provider) {
                AiProvider.GEMINI -> callGemini(apiKey, prompt)
                AiProvider.CLAUDE -> callClaude(apiKey, prompt)
                AiProvider.OPENAI -> callOpenAi(apiKey, prompt)
                AiProvider.KIMI -> callKimi(apiKey, prompt)
            }
        } catch (e: Exception) {
            "Sistem Hatası [${provider.displayName}]: ${e.localizedMessage ?: "Bilinmeyen bir iletişim hatası oluştu."}"
        }
    }

    private fun callGemini(apiKey: String, prompt: String): String {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"
        val jsonMediaType = "application/json".toMediaType()

        val requestBodyJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", prompt)
                        })
                    })
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Boş yanıt gövdesi.")
            if (!response.isSuccessful) {
                val errMsg = try {
                    JSONObject(body).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    "HTTP ${response.code}"
                }
                return "Gemini API Hatası: $errMsg"
            }

            return try {
                val root = JSONObject(body)
                val candidateField = root.getJSONArray("candidates").getJSONObject(0)
                val contentField = candidateField.getJSONObject("content")
                val partField = contentField.getJSONArray("parts").getJSONObject(0)
                partField.getString("text")
            } catch (e: Exception) {
                "Hata: Yanıt çözümlenemedi. Ham yanıt:\n$body"
            }
        }
    }

    private fun callClaude(apiKey: String, prompt: String): String {
        val url = "https://api.anthropic.com/v1/messages"
        val jsonMediaType = "application/json".toMediaType()

        val requestBodyJson = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", 1024)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .header("content-type", "application/json")
            .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Boş yanıt gövdesi.")
            if (!response.isSuccessful) {
                val errMsg = try {
                    JSONObject(body).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    "HTTP ${response.code}"
                }
                return "Claude API Hatası: $errMsg"
            }

            return try {
                val root = JSONObject(body)
                val contentArray = root.getJSONArray("content")
                contentArray.getJSONObject(0).getString("text")
            } catch (e: Exception) {
                "Hata: Yanıt çözümlenemedi. Ham yanıt:\n$body"
            }
        }
    }

    private fun callOpenAi(apiKey: String, prompt: String): String {
        val url = "https://api.openai.com/v1/chat/completions"
        val jsonMediaType = "application/json".toMediaType()

        val requestBodyJson = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Boş yanıt gövdesi.")
            if (!response.isSuccessful) {
                val errMsg = try {
                    JSONObject(body).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    "HTTP ${response.code}"
                }
                return "OpenAI API Hatası: $errMsg"
            }

            return try {
                val root = JSONObject(body)
                val choices = root.getJSONArray("choices")
                val message = choices.getJSONObject(0).getJSONObject("message")
                message.getString("content")
            } catch (e: Exception) {
                "Hata: Yanıt çözümlenemedi. Ham yanıt:\n$body"
            }
        }
    }

    private fun callKimi(apiKey: String, prompt: String): String {
        val url = "https://api.moonshot.cn/v1/chat/completions"
        val jsonMediaType = "application/json".toMediaType()

        val requestBodyJson = JSONObject().apply {
            put("model", "moonshot-v1-8k")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .post(requestBodyJson.toString().toRequestBody(jsonMediaType))
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: throw IOException("Boş yanıt gövdesi.")
            if (!response.isSuccessful) {
                val errMsg = try {
                    JSONObject(body).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    "HTTP ${response.code}"
                }
                return "Kimi API Hatası: $errMsg"
            }

            return try {
                val root = JSONObject(body)
                val choices = root.getJSONArray("choices")
                val message = choices.getJSONObject(0).getJSONObject("message")
                message.getString("content")
            } catch (e: Exception) {
                "Hata: Yanıt çözümlenemedi. Ham yanıt:\n$body"
            }
        }
    }
}
