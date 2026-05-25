package com.example.ui

import android.app.Application
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
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader

class TerminalViewModel(application: Application) : AndroidViewModel(application) {
    private val db = TerminalDatabase.getDatabase(application)
    private val logDao = db.terminalLogDao()
    private val aiService = AiService(application)

    val logs: StateFlow<List<TerminalLog>> = logDao.getAllLogs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentProvider = MutableStateFlow(aiService.getStoredProvider())
    val currentProvider: StateFlow<AiProvider> = _currentProvider.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _history = MutableStateFlow<List<String>>(emptyList())
    private var historyIndex = -1

    init {
        viewModelScope.launch {
            logDao.getAllLogs().collect { list ->
                if (list.isEmpty()) {
                    showWelcome()
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
            
            Kullanabileceğiniz Komutlar:
              !ai <soru>          -> Seçili AI modeline soru sorar.
              !run <komut>        -> Android sisteminde yerel komut çalıştırır.
              !provider <isim>    -> Model değiştirir (gemini/claude/openai/kimi).
              !set key <key>      -> Aktif modele API anahtarı ekler.
              !clear              -> Ekranı ve geçmişi temizler.
              !help               -> Bu yardım menüsünü gösterir.
            
            Sistem Hazır. Bir komut girin...
        """.trimIndent()
        logDao.insertLog(TerminalLog(
            timestamp = System.currentTimeMillis(),
            type = "SYSTEM",
            text = welcomeText,
            provider = currentProvider.value.name
        ))
    }

    fun setProvider(provider: AiProvider) {
        _currentProvider.value = provider
        aiService.setStoredProvider(provider)
        viewModelScope.launch {
            logDao.insertLog(TerminalLog(
                timestamp = System.currentTimeMillis(),
                type = "SYSTEM",
                text = "[Sistem] Aktif sağlayıcı değiştirildi: ${provider.displayName}",
                provider = provider.name
            ))
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
            logDao.insertLog(TerminalLog(
                timestamp = System.currentTimeMillis(),
                type = "COMMAND",
                text = input,
                provider = currentProvider.value.name
            ))

            processCommand(input)
        }
    }

    private suspend fun processCommand(input: String) {
        if (input.startsWith("!ai ")) {
            val prompt = input.substring(4).trim()
            if (prompt.isEmpty()) {
                logDao.insertLog(TerminalLog(
                    timestamp = System.currentTimeMillis(),
                    type = "ERROR",
                    text = "Hata: Boş bir AI sorusu giremezsiniz.",
                    provider = currentProvider.value.name
                ))
                return
            }
            executeAiQuery(prompt)
        } else if (input.startsWith("!run ")) {
            val cmd = input.substring(5).trim()
            if (cmd.isEmpty()) {
                logDao.insertLog(TerminalLog(
                    timestamp = System.currentTimeMillis(),
                    type = "ERROR",
                    text = "Hata: Çalıştırılacak bir komut girilmedi.",
                    provider = currentProvider.value.name
                ))
                return
            }
            executeLocalCommand(cmd)
        } else if (input.startsWith("!set key ")) {
            val content = input.substring(9).trim()
            val parts = content.split(" ", limit = 2)
            if (parts.size == 1) {
                val key = parts[0]
                aiService.setApiKey(currentProvider.value, key)
                logDao.insertLog(TerminalLog(
                    timestamp = System.currentTimeMillis(),
                    type = "SYSTEM",
                    text = "[Sistem] ${currentProvider.value.displayName} için API Anahtarı başarıyla kaydedildi.",
                    provider = currentProvider.value.name
                ))
            } else {
                val provStr = parts[0].uppercase()
                val key = parts[1]
                try {
                    val targetProv = AiProvider.valueOf(provStr)
                    aiService.setApiKey(targetProv, key)
                    logDao.insertLog(TerminalLog(
                        timestamp = System.currentTimeMillis(),
                        type = "SYSTEM",
                        text = "[Sistem] ${targetProv.displayName} için API Anahtarı başarıyla kaydedildi.",
                        provider = targetProv.name
                    ))
                } catch (e: Exception) {
                    logDao.insertLog(TerminalLog(
                        timestamp = System.currentTimeMillis(),
                        type = "ERROR",
                        text = "Hata: Geçersiz sağlayıcı adı '$provStr'.",
                        provider = currentProvider.value.name
                    ))
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
                logDao.insertLog(TerminalLog(
                    timestamp = System.currentTimeMillis(),
                    type = "ERROR",
                    text = "Hata: Geçersiz sağlayıcı adı '$provStr'. (Mevcut olanlar: GEMINI, CLAUDE, OPENAI, KIMI)",
                    provider = currentProvider.value.name
                ))
            }
        } else {
            val commandList = listOf("ls", "pwd", "whoami", "uname", "id", "getprop", "echo", "date", "cat")
            val firstWord = input.split(" ")[0]
            if (commandList.contains(firstWord)) {
                executeLocalCommand(input)
            } else {
                logDao.insertLog(TerminalLog(
                    timestamp = System.currentTimeMillis(),
                    type = "ERROR",
                    text = "Bilinmeyen komut: '$input'. AI sorgusu için '!ai <mesaj>' veya yardım için '!help' kullanın.",
                    provider = currentProvider.value.name
                ))
            }
        }
    }

    private suspend fun executeAiQuery(prompt: String) {
        _isLoading.value = true
        val response = aiService.queryAi(currentProvider.value, prompt)
        _isLoading.value = false
        logDao.insertLog(TerminalLog(
            timestamp = System.currentTimeMillis(),
            type = "OUTPUT",
            text = "[${currentProvider.value.displayName}] $response",
            provider = currentProvider.value.name
        ))
    }

    private suspend fun executeLocalCommand(cmd: String) {
        _isLoading.value = true
        val resultText = try {
            val process = Runtime.getRuntime().exec(cmd)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                output.append("[ERROR] ").append(line).append("\n")
            }
            process.waitFor()
            val text = output.toString().trim()
            if (text.isEmpty()) "[Sistem] Komut çalıştırıldı ancak çıktı üretmedi." else text
        } catch (e: Exception) {
            "Sistem hatası: Komut yürütülemedi. ${e.localizedMessage}"
        }
        _isLoading.value = false

        logDao.insertLog(TerminalLog(
            timestamp = System.currentTimeMillis(),
            type = "OUTPUT",
            text = resultText,
            provider = currentProvider.value.name
        ))
    }

    private suspend fun showHelp() {
        val helpText = """
            =================== AI YARDIM MENÜSÜ ===================
            💡 Komut detayları ve kullanım biçimleri:
            
            1. !ai <soru>
               Aktif yapay zekaya (şu an: ${currentProvider.value.displayName}) soruyu yönlendirir.
               Örnek: !ai "Kotlin Coroutines nedir?"
            
            2. !run <komut>
               Android emülatörü veya fiziksel cihazda yerel kabuk (bash) komutu koşturur.
               Örnek: !run "uname -a" veya !run "ls -la /sdcard"
            
            3. !provider <model>
               Modeller arası hızlı geçiş yapar.
               Geçerli modeller: gemini, claude, openai, kimi
               Örnek: !provider claude
               Not: Parametresiz '!provider' sıradaki modele geçer.
            
            4. !set key <key>     (Geçerli model için)
               !set key <model> <key> (Belirli model için)
               Yapay zeka modellerinin API anahtarını günceller.
               Örnek: !set key sk-ant-sid...
               Örnek: !set key kimi eyJhbGc...
            
            5. !clear
               Terminal loglarını ve yerel Room geçmişini siler.
            
            6. !help
               Bu komut listesini ve ipuçlarını ekrana basar.
            ========================================================
        """.trimIndent()
        logDao.insertLog(TerminalLog(
            timestamp = System.currentTimeMillis(),
            type = "SYSTEM",
            text = helpText,
            provider = currentProvider.value.name
        ))
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
}
