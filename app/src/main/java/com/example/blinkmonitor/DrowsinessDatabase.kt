package com.example.blinkmonitor

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Calendar

class DrowsinessDatabase(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("drowsiness_db", Context.MODE_PRIVATE)
    private val gson = Gson()

    private val KEY_SESSIONS = "drowsiness_sessions"
    private val KEY_PERSON_STATS = "person_stats"

    private var jsonDbString: String = ""

    data class DrowsinessEvent(
        val timestamp: Long,
        val durationMs: Long,
        val eventType: EventType,
        val maxEyeBlink: Float,
        val wasAlertTriggered: Boolean
    )

    enum class EventType {
        DROWSY,
        ASLEEP,
        blink
    }

    data class PersonStats(
        val name: String,
        val totalSessions: Int = 0,
        val totalDrowsyEvents: Int = 0,
        val totalAsleepEvents: Int = 0,
        val totalDrowsyDurationMs: Long = 0,
        val totalAsleepDurationMs: Long = 0,
        val lastSeen: Long = 0,
        val averageEar: Float = 0f,
        val history: MutableList<DrowsinessEvent> = mutableListOf()
    )

    data class DailyStats(
        val date: String,
        val drowsyCount: Int = 0,
        val asleepCount: Int = 0,
        val totalDrowsyDurationMs: Long = 0,
        val totalAsleepDurationMs: Long = 0
    )

    init {
        loadDatabase()
    }

    @SuppressLint("ApplySharedPref")
    private fun loadDatabase() {
        jsonDbString = prefs.getString(KEY_PERSON_STATS, "{}") ?: "{}"
    }

    @SuppressLint("ApplySharedPref")
    private fun saveDatabase() {
        prefs.edit().putString(KEY_PERSON_STATS, jsonDbString).apply()
    }

    fun registerEvent(personName: String, event: DrowsinessEvent) {
        val type = object : TypeToken<MutableMap<String, PersonStats>>() {}.type
        val statsMap: MutableMap<String, PersonStats> = gson.fromJson(jsonDbString, type) ?: mutableMapOf()

        val currentStats = statsMap[personName] ?: PersonStats(name = personName)

        val updatedStats = PersonStats(
            name = personName,
            totalSessions = currentStats.totalSessions + 1,
            totalDrowsyEvents = currentStats.totalDrowsyEvents + if (event.eventType == EventType.DROWSY) 1 else 0,
            totalAsleepEvents = currentStats.totalAsleepEvents + if (event.eventType == EventType.ASLEEP) 1 else 0,
            totalDrowsyDurationMs = currentStats.totalDrowsyDurationMs + if (event.eventType == EventType.DROWSY) event.durationMs else 0,
            totalAsleepDurationMs = currentStats.totalAsleepDurationMs + if (event.eventType == EventType.ASLEEP) event.durationMs else 0,
            lastSeen = System.currentTimeMillis(),
            averageEar = if (currentStats.totalSessions > 0) {
                (currentStats.averageEar * currentStats.totalSessions + event.maxEyeBlink) / (currentStats.totalSessions + 1)
            } else {
                event.maxEyeBlink
            },
            history = (currentStats.history + event).takeLast(100).toMutableList()
        )

        statsMap[personName] = updatedStats
        jsonDbString = gson.toJson(statsMap)
        saveDatabase()

        saveSession(personName, event)
    }

    fun getPersonStats(personName: String): PersonStats? {
        val type = object : TypeToken<MutableMap<String, PersonStats>>() {}.type
        val statsMap: MutableMap<String, PersonStats> = gson.fromJson(jsonDbString, type) ?: mutableMapOf()
        return statsMap[personName]
    }

    fun getAllPeople(): List<String> {
        val type = object : TypeToken<MutableMap<String, PersonStats>>() {}.type
        val statsMap: MutableMap<String, PersonStats> = gson.fromJson(jsonDbString, type) ?: mutableMapOf()
        return statsMap.keys.toList()
    }

    fun getAllStats(): Map<String, PersonStats> {
        val type = object : TypeToken<MutableMap<String, PersonStats>>() {}.type
        return gson.fromJson(jsonDbString, type) ?: emptyMap()
    }

    fun getTodayStats(personName: String): DailyStats {
        val today = getTodayDateString()
        val personStats = getPersonStats(personName) ?: return DailyStats(date = today)

        val todayEvents = personStats.history.filter {
            getDateStringFromTimestamp(it.timestamp) == today
        }

        return DailyStats(
            date = today,
            drowsyCount = todayEvents.count { it.eventType == EventType.DROWSY },
            asleepCount = todayEvents.count { it.eventType == EventType.ASLEEP },
            totalDrowsyDurationMs = todayEvents.filter { it.eventType == EventType.DROWSY }.sumOf { it.durationMs },
            totalAsleepDurationMs = todayEvents.filter { it.eventType == EventType.ASLEEP }.sumOf { it.durationMs }
        )
    }

    fun addPerson(personName: String) {
        val type = object : TypeToken<MutableMap<String, PersonStats>>() {}.type
        val statsMap: MutableMap<String, PersonStats> = gson.fromJson(jsonDbString, type) ?: mutableMapOf()

        if (!statsMap.containsKey(personName)) {
            statsMap[personName] = PersonStats(name = personName)
            jsonDbString = gson.toJson(statsMap)
            saveDatabase()
        }
    }

    fun removePerson(personName: String) {
        val type = object : TypeToken<MutableMap<String, PersonStats>>() {}.type
        val statsMap: MutableMap<String, PersonStats> = gson.fromJson(jsonDbString, type) ?: mutableMapOf()
        statsMap.remove(personName)
        jsonDbString = gson.toJson(statsMap)
        saveDatabase()
    }

    fun resetPersonStats(personName: String) {
        val type = object : TypeToken<MutableMap<String, PersonStats>>() {}.type
        val statsMap: MutableMap<String, PersonStats> = gson.fromJson(jsonDbString, type) ?: mutableMapOf()
        statsMap[personName] = PersonStats(name = personName)
        jsonDbString = gson.toJson(statsMap)
        saveDatabase()
    }

    fun exportToJson(): String {
        return jsonDbString
    }

    fun importFromJson(json: String): Boolean {
        return try {
            val type = object : TypeToken<MutableMap<String, PersonStats>>() {}.type
            gson.fromJson<MutableMap<String, PersonStats>>(json, type)
            jsonDbString = json
            saveDatabase()
            true
        } catch (e: Exception) {
            false
        }
    }

    @SuppressLint("ApplySharedPref")
    private fun saveSession(personName: String, event: DrowsinessEvent) {
        val sessionsKey = "$KEY_SESSIONS:$personName"
        val sessionsJson = prefs.getString(sessionsKey, "[]") ?: "[]"
        val type = object : TypeToken<MutableList<DrowsinessEvent>>() {}.type
        val sessions: MutableList<DrowsinessEvent> = gson.fromJson(sessionsJson, type) ?: mutableListOf()

        sessions.add(event)
        while (sessions.size > 1000) sessions.removeAt(0)

        prefs.edit().putString(sessionsKey, gson.toJson(sessions)).apply()
    }

    private fun getTodayDateString(): String {
        val calendar = Calendar.getInstance()
        return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH) + 1}-${calendar.get(Calendar.DAY_OF_MONTH)}"
    }

    private fun getDateStringFromTimestamp(timestamp: Long): String {
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = timestamp
        return "${calendar.get(Calendar.YEAR)}-${calendar.get(Calendar.MONTH) + 1}-${calendar.get(Calendar.DAY_OF_MONTH)}"
    }
}