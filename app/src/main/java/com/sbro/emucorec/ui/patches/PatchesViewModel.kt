package com.sbro.emucorec.ui.patches

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucorec.data.InstalledGameRepository
import com.sbro.emucorec.data.InstalledPs3Game
import com.sbro.emucorec.data.PatchRepository
import com.sbro.emucorec.data.Ps3PatchInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

data class PatchesUiState(
    val games: List<InstalledPs3Game> = emptyList(),
    val selectedTitleId: String? = null,
    val patches: List<Ps3PatchInfo> = emptyList(),
    val isLoading: Boolean = true,
    val hasLoadedOnce: Boolean = false,
    val patchesLoading: Boolean = false,
    /** Serial whose patch list is currently rendered; a reload of the same game keeps it. */
    val patchesLoadedSerial: String? = null,
    val isDownloading: Boolean = false,
    val downloadResult: PatchRepository.DownloadResult? = null,
    /** True once the catalogue was fetched in this screen, even when nothing new came in. */
    val catalogueSynced: Boolean = false,
    val errorMessage: String? = null,
    val togglingPatchKeys: Set<String> = emptySet(),
) {
    val selectedGame: InstalledPs3Game?
        get() = games.firstOrNull { it.titleId == selectedTitleId }
}

class PatchesViewModel(application: Application) : AndroidViewModel(application) {
    private val gameRepository = InstalledGameRepository()
    private val patchRepository = PatchRepository(application)

    private val _uiState = MutableStateFlow(PatchesUiState())
    val uiState: StateFlow<PatchesUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var patchesJob: Job? = null
    private var openSyncStarted = false

    init {
        refresh()
    }

    /**
     * The screen used to open on "no patches" until the player pressed the download button,
     * which is wrong for a first run: the catalogue can be fetched right away. Sync once per
     * screen opening, and keep the automatic pass quiet unless it actually fails - the manual
     * button still reports its result in the banner.
     */
    fun syncOnOpen() {
        if (openSyncStarted) return
        openSyncStarted = true
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadResult = null) }
            val result = withContext(Dispatchers.IO) { patchRepository.downloadPatches() }
            _uiState.update { state ->
                state.copy(
                    isDownloading = false,
                    downloadResult = result as? PatchRepository.DownloadResult.Error,
                    catalogueSynced = state.catalogueSynced ||
                        result !is PatchRepository.DownloadResult.Error,
                )
            }
            if (result !is PatchRepository.DownloadResult.Error) {
                _uiState.value.selectedTitleId?.let(::loadPatches)
            }
        }
    }

    fun refresh(preferredTitleId: String? = null) {
        val context = getApplication<Application>()
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val games = withContext(Dispatchers.IO) { gameRepository.loadInstalledGames(context) }
            val candidates = listOfNotNull(preferredTitleId, _uiState.value.selectedTitleId)
            val titleId = candidates.firstNotNullOfOrNull { candidate ->
                games.firstOrNull { it.titleId.equals(candidate, ignoreCase = true) }?.titleId
            } ?: games.firstOrNull()?.titleId

            _uiState.update {
                it.copy(
                    games = games,
                    selectedTitleId = titleId,
                    isLoading = false,
                    hasLoadedOnce = true,
                    patchesLoading = true,
                    downloadResult = null,
                    errorMessage = null,
                )
            }

            if (titleId != null) {
                loadPatches(titleId)
            } else {
                _uiState.update { it.copy(patchesLoading = false) }
            }
        }
    }

    fun selectGame(titleId: String) {
        if (titleId == _uiState.value.selectedTitleId) return
        _uiState.update {
            it.copy(
                selectedTitleId = titleId,
                patches = emptyList(),
                patchesLoading = true,
                patchesLoadedSerial = null,
            )
        }
        loadPatches(titleId)
    }

    private fun loadPatches(serial: String) {
        patchesJob?.cancel()
        patchesJob = viewModelScope.launch {
            _uiState.update { it.copy(patchesLoading = true) }
            val patches = withContext(Dispatchers.IO) { patchRepository.listPatches(serial) }
            _uiState.update {
                if (it.selectedTitleId == serial) {
                    it.copy(patches = patches, patchesLoading = false, patchesLoadedSerial = serial)
                } else {
                    it
                }
            }
        }
    }

    fun downloadPatches() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDownloading = true, downloadResult = null) }
            val result = withContext(Dispatchers.IO) { patchRepository.downloadPatches() }
            _uiState.update {
                it.copy(
                    isDownloading = false,
                    downloadResult = result,
                    catalogueSynced = it.catalogueSynced ||
                        result !is PatchRepository.DownloadResult.Error,
                )
            }
            val currentSerial = _uiState.value.selectedTitleId
            if (currentSerial != null && result is PatchRepository.DownloadResult.Success) {
                loadPatches(currentSerial)
            }
        }
    }

    fun togglePatch(patch: Ps3PatchInfo) {
        val serial = _uiState.value.selectedTitleId ?: return
        val key = patch.identityKey
        if (key in _uiState.value.togglingPatchKeys) return
        val desiredEnabled = !patch.enabled
        _uiState.update { it.copy(togglingPatchKeys = it.togglingPatchKeys + key) }
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                patchRepository.togglePatch(
                    hash = patch.hash,
                    name = patch.name,
                    serial = serial,
                    appVersion = patch.appVersion,
                    enabled = desiredEnabled
                )
            }
            _uiState.update { state ->
                val updatedPatches = if (success && state.selectedTitleId == serial) {
                    state.patches.map {
                        if (it.identityKey == key) it.copy(enabled = desiredEnabled) else it
                    }
                } else {
                    state.patches
                }
                state.copy(
                    patches = updatedPatches,
                    togglingPatchKeys = state.togglingPatchKeys - key,
                )
            }
        }
    }

    fun clearDownloadResult() {
        _uiState.update { it.copy(downloadResult = null) }
    }
}
