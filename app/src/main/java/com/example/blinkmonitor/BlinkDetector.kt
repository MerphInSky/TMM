package com.example.blinkmonitor

class BlinkDetector(
    private val earBufferSize: Int = 5,
    private val blinkHistorySize: Int = 15,

    // Оптимизированные пороги для реальных условий
    private val earOpenThreshold: Float = 0.20f,      // выше этого - глаза точно открыты
    private val earHalfClosedThreshold: Float = 0.14f, // 0.14-0.20 - полузакрыты
    private val earClosedThreshold: Float = 0.11f,     // ниже этого - закрыты

    private val drowsyDurationMs: Long = 2000,      // 2 секунды полузакрытия = сонливость
    private val asleepDurationMs: Long = 4000       // 4 секунды закрытия = сон
) {

    private val earBuffer = ArrayDeque<Float>(earBufferSize)
    private val blinkHistory = ArrayDeque<BlinkRecord>(blinkHistorySize)

    private var currentState: EyeState = EyeState.OPEN
    private var stateStartTime: Long = System.currentTimeMillis()

    data class DetectionResult(
        val smoothedEar: Float,
        val rawEar: Float,
        val eyeState: EyeState,
        val avgBlinkDurationSec: Float?,
        val blinkFrequencyPerMin: Float?,
        val humanState: HumanState,
        val isAsleep: Boolean,
        val timeInCurrentStateMs: Long
    )

    enum class EyeState {
        OPEN,        // глаз открыт
        HALF_CLOSED, // полузакрыт (сонливость)
        CLOSED       // закрыт (засыпание/сон)
    }

    enum class HumanState {
        AWAKE,       // бодрствует
        DROWSY,      // сонливый
        ASLEEP       // спит
    }

    data class BlinkRecord(
        val timestampSec: Float,
        val state: EyeState,
        val durationSec: Float
    )

    fun processEAR(rawEar: Double): DetectionResult {
        val currentTime = System.currentTimeMillis()

        // Сглаживание EAR через медианный фильтр для устранения шума
        if (earBuffer.size >= earBufferSize) earBuffer.removeFirst()
        earBuffer.addLast(rawEar.toFloat())
        val smoothedEar = medianFilter(earBuffer)

        // Определение состояния глаза по порогам
        val newState = when {
            smoothedEar > earOpenThreshold -> EyeState.OPEN
            smoothedEar > earHalfClosedThreshold -> EyeState.HALF_CLOSED
            else -> EyeState.CLOSED
        }

        // Обработка смены состояния
        if (newState != currentState) {
            val durationInState = currentTime - stateStartTime
            val durationSec = durationInState / 1000f

            // Сохраняем запись о завершившемся состоянии (для статистики)
            if (currentState == EyeState.HALF_CLOSED || currentState == EyeState.CLOSED) {
                val record = BlinkRecord(
                    timestampSec = stateStartTime / 1000f,
                    state = currentState,
                    durationSec = durationSec
                )

                if (blinkHistory.size >= blinkHistorySize) blinkHistory.removeFirst()
                blinkHistory.addLast(record)
            }

            currentState = newState
            stateStartTime = currentTime
        }

        // Расчет статистики по истории закрытий/полузакрытий
        val recentBlinks = blinkHistory.filter {
            it.state == EyeState.HALF_CLOSED || it.state == EyeState.CLOSED
        }

        val avgBlinkDurationSec = if (recentBlinks.isNotEmpty()) {
            recentBlinks.map { it.durationSec }.average().toFloat()
        } else null

        val blinkFrequencyPerMin = if (recentBlinks.size >= 2) {
            val oldest = recentBlinks.first()
            val newest = recentBlinks.last()
            val timeSpanSec = newest.timestampSec - oldest.timestampSec
            if (timeSpanSec > 0) {
                (recentBlinks.size - 1) * 60f / timeSpanSec
            } else null
        } else null

        // Определение состояния человека
        val timeInCurrentState = currentTime - stateStartTime

        // Сон только если глаза полностью закрыты больше 4 секунд
        val isAsleep = (currentState == EyeState.CLOSED && timeInCurrentState >= asleepDurationMs)

        // Сонливость если:
        // - глаза полузакрыты больше 2 секунд, ИЛИ
        // - глаза закрыты но меньше 4 секунд (короткое моргание не считается сонливостью)
        val isDrowsy = when (currentState) {
            EyeState.HALF_CLOSED -> timeInCurrentState >= drowsyDurationMs
            EyeState.CLOSED -> timeInCurrentState >= 500 && timeInCurrentState < asleepDurationMs
            else -> false
        }

        val humanState = when {
            isAsleep -> HumanState.ASLEEP
            isDrowsy -> HumanState.DROWSY
            else -> HumanState.AWAKE
        }

        return DetectionResult(
            smoothedEar = smoothedEar,
            rawEar = rawEar.toFloat(),
            eyeState = currentState,
            avgBlinkDurationSec = avgBlinkDurationSec,
            blinkFrequencyPerMin = blinkFrequencyPerMin,
            humanState = humanState,
            isAsleep = isAsleep,
            timeInCurrentStateMs = timeInCurrentState
        )
    }

    fun getEarBuffer(): List<Float> = earBuffer.toList()

    fun reset() {
        earBuffer.clear()
        blinkHistory.clear()
        currentState = EyeState.OPEN
        stateStartTime = System.currentTimeMillis()
    }

    private fun medianFilter(buffer: ArrayDeque<Float>): Float {
        if (buffer.isEmpty()) return 0f
        val sorted = buffer.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid]
        else (sorted[mid - 1] + sorted[mid]) / 2f
    }
}