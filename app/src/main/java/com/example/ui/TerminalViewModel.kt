package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.api.AiProvider
import com.example.api.AiService
import com.example.db.TerminalDatabase
import com.example.db.TerminalLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.io.BufferedReader
import java.io.InputStreamReader
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okio.ByteString
import java.util.concurrent.TimeUnit

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TerminalDatabase.getDatabase(application)
    private val logDao = db.terminalLogDao()
    private val aiService = AiService(application)

    private suspend fun safeInsertLog(type: String, text: String, provider: String) {
        try {
            val maxChars = 8000
            val processedText = if (text.length > maxChars) {
                text.take(maxChars) + "\n... [Çıktı çok uzun olduğu için kırpıldı / Truncated due to length] ..."
            } else {
                text
            }
            logDao.insertLog(TerminalLog(
                timestamp = System.currentTimeMillis(),
                type = type,
                text = processedText,
                provider = provider
            ))
        } catch (e: Exception) {
            Log.e("TerminalVM", "Error inserting log safely", e)
        }
    }

    val logs: StateFlow<List<TerminalLog>> = logDao.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentProvider = MutableStateFlow(aiService.getStoredProvider())
    val currentProvider: StateFlow<AiProvider> = _currentProvider.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    private var historyIndex = -1

    // Remote Connection States
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // Infinite for WebSockets
        .build()

    private var webSocket: WebSocket? = null
    private val _isRemoteMode = MutableStateFlow(false)
    val isRemoteMode: StateFlow<Boolean> = _isRemoteMode.asStateFlow()
    private var remoteUrl: String = ""

    init {
        viewModelScope.launch {
            try {
                // Use first() to do a one-time check on application startup
                val list = logDao.getAllLogs().first()
                if (list.isEmpty()) {
                    showWelcome()
                }
            } catch (e: Exception) {
                Log.e("TerminalVM", "Initial DB check error", e)
                try {
                    showWelcome()
                } catch (ex: Exception) {
                    Log.e("TerminalVM", "Failed to fallback on welcome screen", ex)
                }
            }
        }
    }

    private suspend fun showWelcome() {
        val welcomeText = """
            ==================================================
              ___  ___   _____ ___ __  __ ___ _  _  _   _    
             / _ \|_ _| |_   _| __|  \/  |_ _| \| |/_\ | |   
            | (_) || |    | | | _|| |\/| || || .` / _ \| |__ 
             \___/|___|   |_| |___|_|  |_|___|_|\_/_/ \_\____|
                                                               
                  🚀 ANDROID ARTIFICIAL INTELLIGENCE TERMINAL
            ==================================================
            Aktif AI Sağlayıcı: ${currentProvider.value.displayName}
            Modeller: Gemini, Claude-3.5, GPT-4, Kimi-v1
            
            Uzak Terminal Bağlantısı (Claude Code Proxy):
              Örnek: !connect ws://192.168.1.50:8080/ws
            
            Kullanabileceğiniz Komutlar:
              <soru>              -> Prefix olmadan doğrudan AI'a sorar!
              !ai <soru>          -> Seçili AI modeline soru sorar.
              !run <komut>        -> Android sisteminde yerel komut çalıştırır.
              !provider <isim>    -> Model değiştirir (gemini/claude/openai/kimi).
              !set key <key>      -> Aktif modele API anahtarı ekler.
              !connect <ws_url>   -> Uzak Claude-Code sunucusuna bağlanır.
              !clear              -> Ekranı ve geçmişi temizler.
              !help               -> Bu yardım menüsünü gösterir.
            
            Sistem Hazır. Bir komut girin...
        """.trimIndent()
        
        safeInsertLog("SYSTEM", welcomeText, currentProvider.value.name)
    }

    fun setProvider(provider: AiProvider) {
        _currentProvider.value = provider
        aiService.setStoredProvider(provider)
        viewModelScope.launch {
            safeInsertLog("SYSTEM", "[Sistem] Aktif sağlayıcı değiştirildi: ${provider.displayName}", provider.name)
        }
    }

    fun handleCommand(rawInput: String) {
        val input = rawInput.trim()
        if (input.isEmpty()) return

        val currentHist = _history.value.toMutableList()
        currentHist.remove(input)
        currentHist.add(input)
        _history.value = currentHist
        historyIndex = -1

        viewModelScope.launch {
            try {
                safeInsertLog("COMMAND", input, if (_isRemoteMode.value) "REMOTE" else currentProvider.value.name)

                if (_isRemoteMode.value) {
                    if (input == "!disconnect" || input == "!exit") {
                        disconnectFromRemote("Kullanıcı isteği ile bağlantı kesildi.")
                    } else {
                        // Send to remote WebSocket host
                        _isLoading.value = true
                        val sent = webSocket?.send(input) ?: false
                        _isLoading.value = false
                        if (!sent) {
                            safeInsertLog("ERROR", "[Uzak Terminal] Hata: Komut gönderilemedi. Sunucu ile bağlantı kesilmiş olabilir.", "REMOTE")
                        }
                    }
                } else {
                    processCommand(input)
                }
            } catch (e: Exception) {
                Log.e("TerminalVM", "Error handling command: '$input'", e)
                safeInsertLog("ERROR", "Sistem İşlem Hatası: ${e.localizedMessage ?: "Bilinmeyen bir hata oluştu."}", currentProvider.value.name)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun processCommand(input: String) {
        if (input.startsWith("!ai ")) {
            val prompt = input.substring(4).trim()
            if (prompt.isEmpty()) {
                safeInsertLog("ERROR", "Hata: Boş bir AI sorusu giremezsiniz.", currentProvider.value.name)
                return
            }
            executeAiQuery(prompt)
        } else if (input.startsWith("!run ")) {
            val cmd = input.substring(5).trim()
            if (cmd.isEmpty()) {
                safeInsertLog("ERROR", "Hata: Çalıştırılacak bir komut girilmedi.", currentProvider.value.name)
                return
            }
            executeLocalCommand(cmd)
        } else if (input.startsWith("!connect ")) {
            val url = input.substring(9).trim()
            if (url.isEmpty()) {
                safeInsertLog("ERROR", "Hata: Bağlanılacak bir WebSocket URL'si girin. Örnek: '!connect ws://192.168.1.50:8080/ws'", currentProvider.value.name)
                return
            }
            connectToRemote(url)
        } else if (input == "!disconnect") {
            safeInsertLog("ERROR", "Uzak bir terminale bağlı değilsiniz.", currentProvider.value.name)
        } else if (input.startsWith("!set key ")) {
            val content = input.substring(9).trim()
            val parts = content.split(" ", limit = 2)
            if (parts.size == 1) {
                val key = parts[0]
                aiService.setApiKey(currentProvider.value, key)
                safeInsertLog("SYSTEM", "[Sistem] ${currentProvider.value.displayName} için API Anahtarı başarıyla kaydedildi.", currentProvider.value.name)
            } else {
                val provStr = parts[0].uppercase()
                val key = parts[1]
                try {
                    val targetProv = AiProvider.valueOf(provStr)
                    aiService.setApiKey(targetProv, key)
                    safeInsertLog("SYSTEM", "[Sistem] ${targetProv.displayName} için API Anahtarı başarıyla kaydedildi.", targetProv.name)
                } catch (e: Exception) {
                    safeInsertLog("ERROR", "Hata: Geçersiz sağlayıcı adı '$provStr'.", currentProvider.value.name)
                }
            }
        } else if (input == "!clear") {
            logDao.clearLogs()
            showWelcome()
        } else if (input == "!help") {
            showHelp()
        } else if (input == "!provider") {
            val nextProvider = when (currentProvider.value) {
                AiProvider.GEMINI -> AiProvider.CLAUDE
                AiProvider.CLAUDE -> AiProvider.OPENAI
                AiProvider.OPENAI -> AiProvider.KIMI
                AiProvider.KIMI -> AiProvider.GEMINI
            }
            setProvider(nextProvider)
        } else if (input.startsWith("!provider ")) {
            val provStr = input.substring(10).trim().uppercase()
            try {
                val targetProv = AiProvider.valueOf(provStr)
                setProvider(targetProv)
            } catch (e: Exception) {
                safeInsertLog("ERROR", "Hata: Geçersiz sağlayıcı adı '$provStr'. (Mevcut olanlar: GEMINI, CLAUDE, OPENAI, KIMI)", currentProvider.value.name)
            }
        } else {
            // Check if user input is starting with any local system commands
            val commandList = listOf("ls", "pwd", "whoami", "uname", "id", "getprop", "echo", "date", "cat")
            val firstWord = input.split(" ")[0]
            if (commandList.contains(firstWord)) {
                executeLocalCommand(input)
            } else {
                // TREAT AS DIRECT AI QUERY
                executeAiQuery(input)
            }
        }
    }

    private fun connectToRemote(url: String) {
        _isLoading.value = true
        remoteUrl = url
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                webSocket = client.newWebSocket(request, RemoteWebSocketListener())
            } catch (e: Exception) {
                Log.e("TerminalVM", "Failed to connect to web socket: $url", e)
                withContext(Dispatchers.Main) {
                    _isLoading.value = false
                    safeInsertLog("ERROR", "Uzak bağlantı başlatılamadı: ${e.localizedMessage ?: "Bağlantı hatası"}", currentProvider.value.name)
                }
            }
        }
    }

    private suspend fun disconnectFromRemote(message: String) {
        try {
            webSocket?.close(1000, "Normal closure")
        } catch (e: Exception) {
            Log.e("TerminalVM", "WebSocket close error", e)
        }
        webSocket = null
        _isRemoteMode.value = false
        _isLoading.value = false
        safeInsertLog("SYSTEM", "[Uzak Terminal] Bağlantı kesildi. Gerekçe: $message", currentProvider.value.name)
    }

    private suspend fun executeAiQuery(prompt: String) {
        _isLoading.value = true
        val response = aiService.queryAi(currentProvider.value, prompt)
        _isLoading.value = false
        safeInsertLog("OUTPUT", "[${currentProvider.value.displayName}] $response", currentProvider.value.name)
    }

    private suspend fun executeLocalCommand(cmd: String) {
        _isLoading.value = true
        val resultText = withContext(Dispatchers.IO) {
            var process: Process? = null
            try {
                // Run command through Android shell with merged stdout and stderr
                process = ProcessBuilder("sh", "-c", cmd)
                    .redirectErrorStream(true)
                    .start()

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()

                // Limit local commands to 10 seconds timeout to prevent hanging UI
                val ranSuccessfully = withTimeoutOrNull(10000L) {
                    var line: String?
                    while (true) {
                        yield() // Check for coroutine cancellation
                        line = reader.readLine() ?: break
                        output.append(line).append("\n")
                    }
                    process.waitFor()
                    true
                }

                if (ranSuccessfully == null) {
                    // Time out
                    try {
                        process.destroy()
                    } catch (ex: Exception) {
                        Log.e("TerminalVM", "Process kill failed", ex)
                    }
                    output.append("\n[ERR] Komut zaman aşımına uğradı (10 saniye limit). İşlem sonlandırıldı.")
                }

                val text = output.toString().trim()
                if (text.isEmpty()) "[Sistem] Komut çalıştırıldı ancak çıktı üretmedi." else text
            } catch (e: Exception) {
                Log.e("TerminalVM", "Shell execution exception for command: '$cmd'", e)
                try {
                    process?.destroy()
                } catch (ex: Exception) {
                    // Ignore
                }
                "Sistem hatası: Komut yürütülemedi. ${e.localizedMessage ?: "Bilinmeyen bir hata oluştu."}"
            }
        }
        _isLoading.value = false

        safeInsertLog("OUTPUT", resultText, currentProvider.value.name)
    }

    private suspend fun showHelp() {
        val helpText = """
            =================== AI YARDIM MENÜSÜ ===================
            💡 Komut detayları ve kullanım biçimleri:
            
            1. Doğrudan Soru Girişi:
               Bir '!' Karakteri içermeyen her metin doğrudan seçili AI sağlayıcısına iletilir.
               Örnek: "Yazılımcılar için kahve neden önemlidir?"
            
            2. !ai <soru>
               Aktif yapay zekaya (şu an: ${currentProvider.value.displayName}) soruyu yönlendirir.
            
            3. !connect <ws_url>
               Uzak bir Claude-Code WebSocket sunucusuna/proxy'sine bağlanır.
               Örnek: !connect ws://192.168.1.50:8080/ws
               Bağlandıktan sonra tüm girdileriniz uzak terminale (Claude-Code) yönlendirilir.
               Uzak moddan çıkmak için '!disconnect' yazmanız yeterlidir.
            
            4. !run <komut>
               Android emülatörü veya fiziksel cihazda yerel kabuk (bash) komutu koşturur.
               Örnek: !run "uname -a"
            
            5. !provider <model>
               Modeller arası hızlı geçiş yapar (gemini, claude, openai, kimi).
            
            6. !set key <key>
               Yapay zeka modellerinin API anahtarını günceller.
            
            7. !clear
               Terminal loglarını temizler.
            ========================================================
        """.trimIndent()
        
        safeInsertLog("SYSTEM", helpText, currentProvider.value.name)
    }

    fun navigateHistory(up: Boolean): String {
        val list = _history.value
        if (list.isEmpty()) return ""

        if (up) {
            if (historyIndex == -1) {
                historyIndex = list.size - 1
            } else if (historyIndex > 0) {
                historyIndex--
            }
        } else {
            if (historyIndex != -1 && historyIndex < list.size - 1) {
                historyIndex++
            } else {
                historyIndex = -1
                return ""
            }
        }

        return if (historyIndex in list.indices) list[historyIndex] else ""
    }

    inner class RemoteWebSocketListener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            viewModelScope.launch {
                _isRemoteMode.value = true
                _isLoading.value = false
                safeInsertLog("SYSTEM", "[Uzak Terminal] Bağlantı başarıyla kuruldu! (${remoteUrl})\nArtık tüm komutlarınız uzak sunucuya iletilecek. Çıkmak için '!disconnect' yazın.", "REMOTE")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            viewModelScope.launch {
                safeInsertLog("OUTPUT", text, "REMOTE")
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val text = try { bytes.utf8() } catch (e: Exception) { "" }
            if (text.isEmpty()) return
            viewModelScope.launch {
                safeInsertLog("OUTPUT", text, "REMOTE")
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            viewModelScope.launch {
                try {
                    disconnectFromRemote("Sunucu bağlantıyı kapatıyor: $reason")
                } catch (e: Exception) {
                    Log.e("TerminalVM", "Error in WebSocket onClosing launch", e)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            viewModelScope.launch {
                try {
                    disconnectFromRemote("Bağlantı hatası: ${t.localizedMessage ?: t.message ?: "Bilinmeyen bağlantı hatası"}")
                } catch (e: Exception) {
                    Log.e("TerminalVM", "Error in WebSocket onFailure launch", e)
                }
            }
        }
    }
}
