package com.sbro.emucorec.core

import org.json.JSONArray

internal class NativeSaveStateBackend(
    private val slotDetails: () -> List<SaveStateSlot> = { emptyList() },
) : SaveStateBackend {
    override fun slots(): List<SaveStateSlot> {
        val entries = JSONArray(Ps3Core.instance.saveStateSlots())
        val details = slotDetails().associateBy(SaveStateSlot::index)
        return buildList {
            for (index in 0 until entries.length()) {
                val entry = entries.getJSONObject(index)
                val slot = entry.getInt("index")
                if (slot in 0 until SaveStateController.SLOT_COUNT) {
                    val local = details[slot]
                    add(SaveStateSlot(slot, entry.getLong("modified"), entry.getLong("size"),
                        local?.statePath, local?.thumbnailPath, local?.thumbnailRevision ?: 0L))
                }
            }
        }
    }

    override fun save(slot: Int, progressId: Long) = Ps3Core.instance.requestSaveState(slot, progressId)
    override fun load(slot: Int, progressId: Long) = Ps3Core.instance.requestLoadState(slot, progressId)
}
