package com.example.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.model.TransferDirection
import com.example.model.TransferHistoryRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class TransferHistoryRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("linko_history", Context.MODE_PRIVATE)
    private val _historyFlow = MutableStateFlow<List<TransferHistoryRecord>>(emptyList())
    val historyFlow: StateFlow<List<TransferHistoryRecord>> = _historyFlow.asStateFlow()

    init {
        loadHistory()
    }

    private fun loadHistory() {
        val jsonStr = prefs.getString("records", null) ?: return
        try {
            val arr = JSONArray(jsonStr)
            val list = mutableListOf<TransferHistoryRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    TransferHistoryRecord(
                        id = obj.getString("id"),
                        timestamp = obj.getLong("timestamp"),
                        direction = TransferDirection.valueOf(obj.getString("direction")),
                        fileCount = obj.getInt("fileCount"),
                        totalBytes = obj.getLong("totalBytes"),
                        summaryNames = obj.getString("summaryNames"),
                        peerName = obj.getString("peerName"),
                        averageSpeedMBs = obj.getDouble("averageSpeedMBs"),
                        isSuccess = obj.getBoolean("isSuccess")
                    )
                )
            }
            _historyFlow.value = list
        } catch (_: Exception) {}
    }

    fun addRecord(record: TransferHistoryRecord) {
        val current = _historyFlow.value.toMutableList()
        current.add(0, record)
        _historyFlow.value = current
        saveHistory(current)
    }

    fun clearHistory() {
        _historyFlow.value = emptyList()
        prefs.edit().remove("records").apply()
    }

    private fun saveHistory(list: List<TransferHistoryRecord>) {
        try {
            val arr = JSONArray()
            list.take(50).forEach { item ->
                arr.put(JSONObject().apply {
                    put("id", item.id)
                    put("timestamp", item.timestamp)
                    put("direction", item.direction.name)
                    put("fileCount", item.fileCount)
                    put("totalBytes", item.totalBytes)
                    put("summaryNames", item.summaryNames)
                    put("peerName", item.peerName)
                    put("averageSpeedMBs", item.averageSpeedMBs)
                    put("isSuccess", item.isSuccess)
                })
            }
            prefs.edit().putString("records", arr.toString()).apply()
        } catch (_: Exception) {}
    }
}
