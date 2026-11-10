package com.sbro.emucorec.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex

data class SaveStateSlot(
    val index: Int,
    val modifiedSeconds: Long,
    val sizeBytes: Long,
    val statePath: String? = null,
    val thumbnailPath: String? = null,
    val thumbnailRevision: Long = 0L,
) {
    val slot: Int get() = index
    val timestamp: Long get() = modifiedSeconds * 1000
    val exists: Boolean get() = true
    val isQuick: Boolean get() = index == SaveStateRepository.QUICK_SLOT
}

enum class SaveStateOutcome { Success, Empty, Busy, Failed }

internal interface SaveStateBackend {
    fun slots(): List<SaveStateSlot>
    fun save(slot: Int, progressId: Long): Boolean
    fun load(slot: Int, progressId: Long): Boolean
}

internal class SaveStateController(private val backend: SaveStateBackend) {
    private val mutableBusy = MutableStateFlow(false)
    private val mutableSlots = MutableStateFlow(emptyList<SaveStateSlot>())
    val busy: StateFlow<Boolean> = mutableBusy
    val slots: StateFlow<List<SaveStateSlot>> = mutableSlots
    private val operationMutex = Mutex()

    fun refresh() { mutableSlots.value = backend.slots() }
    suspend fun save(slot: Int) = operate(slot, load = false)
    suspend fun load(slot: Int) = operate(slot, load = true)

    private suspend fun operate(slot: Int, load: Boolean): SaveStateOutcome {
        require(slot in 0 until SLOT_COUNT)
        if (!operationMutex.tryLock()) return SaveStateOutcome.Busy
        mutableBusy.value = true
        try {
            if (load) {
                refresh()
                if (mutableSlots.value.none { it.index == slot }) return SaveStateOutcome.Empty
            }
            val success = awaitNativeOperationCompletion({ progressId ->
                if (load) backend.load(slot, progressId) else backend.save(slot, progressId)
            })
            refresh()
            return if (success) SaveStateOutcome.Success else SaveStateOutcome.Failed
        } finally {
            mutableBusy.value = false
            operationMutex.unlock()
        }
    }

    companion object { const val SLOT_COUNT = 10 }
}
