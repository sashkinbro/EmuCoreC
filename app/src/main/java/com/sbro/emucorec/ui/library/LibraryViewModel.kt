package com.sbro.emucorec.ui.library

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sbro.emucorec.R
import com.sbro.emucorec.core.CoreBinaryFingerprint
import com.sbro.emucorec.core.CoreMaintenanceRepository
import com.sbro.emucorec.core.CoreUpdateResetAction
import com.sbro.emucorec.core.InstallStateBus
import com.sbro.emucorec.core.decideCoreUpdateResetAction
import com.sbro.emucorec.data.AppPreferences
import com.sbro.emucorec.data.InstalledGameRepository
import com.sbro.emucorec.data.InstalledPs3Game
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class LibraryUiState(
    val items: List<InstalledPs3Game> = emptyList(),
    val query: String = "",
    val isLoading: Boolean = true,
    val hasLoadedOnce: Boolean = false,
    val showCoreResetDialog: Boolean = false
)

class LibraryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = InstalledGameRepository()
    private var allItems: List<InstalledPs3Game> = emptyList()
    private var pendingCoreFingerprint: String? = null

    private val _uiState = MutableStateFlow(LibraryUiState())
    val uiState: StateFlow<LibraryUiState> = _uiState.asStateFlow()

    init {
        refresh()
        viewModelScope.launch {
            val context = getApplication<Application>()
            val currentFingerprint = withContext(Dispatchers.IO) {
                CoreBinaryFingerprint.current(context)
            }
            pendingCoreFingerprint = currentFingerprint
            val preferences = AppPreferences(context)
            val hadExistingInstall = preferences.onboardingCompleted
            when (
                decideCoreUpdateResetAction(
                    preferences.lastCoreBinaryFingerprint,
                    currentFingerprint,
                    hadExistingInstall
                )
            ) {
                CoreUpdateResetAction.STORE_SILENTLY ->
                    preferences.lastCoreBinaryFingerprint = currentFingerprint
                CoreUpdateResetAction.PROMPT ->
                    _uiState.value = _uiState.value.copy(showCoreResetDialog = true)
                CoreUpdateResetAction.NONE -> Unit
            }
        }
        viewModelScope.launch {
            InstallStateBus.events.collect {
                // Skip the replayed event that arrives right after creation:
                // init's refresh already covers that state, and re-scanning
                // immediately would duplicate the initial load.
                if (_uiState.value.hasLoadedOnce) {
                    refresh()
                }
            }
        }
    }

    fun refresh() {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val isFirstLoad = !_uiState.value.hasLoadedOnce
            allItems = scanGames(context)
            // The first scan after onboarding can run before the picked folder
            // (or a storage migration) has settled. Retry the initial load a
            // few times when it comes back empty; keep isLoading = true so
            // the loading animation stays visible instead of flashing empty.
            if (isFirstLoad && allItems.isEmpty()) {
                for (attempt in 1..3) {
                    kotlinx.coroutines.delay(1_200)
                    allItems = scanGames(context)
                    if (allItems.isNotEmpty()) break
                }
            }
            publishState()
        }
    }

    private fun scanGames(context: Context): List<InstalledPs3Game> =
        runCatching { repository.loadInstalledGames(context) }
            .getOrElse { error ->
                Log.e("LibraryViewModel", "Game library scan failed", error)
                allItems
            }

    fun deleteInstalledGame(titleId: String, onComplete: (Boolean) -> Unit) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = runCatching { repository.deleteByTitleId(context, titleId) }
                .getOrElse { error ->
                    Log.e("LibraryViewModel", "Game deletion failed", error)
                    false
                }
            if (deleted) {
                allItems = runCatching { repository.loadInstalledGames(context) }
                    .getOrElse { error ->
                        Log.e("LibraryViewModel", "Game library scan failed", error)
                        allItems
                    }
                publishState()
                InstallStateBus.notifyCompleted()
            }
            withContext(Dispatchers.Main) {
                onComplete(deleted)
            }
        }
    }

    fun resetGeneratedCoreState() {
        if (!_uiState.value.showCoreResetDialog) return
        _uiState.value = _uiState.value.copy(showCoreResetDialog = false)
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            CoreMaintenanceRepository(context).resetGeneratedCoreState()
            AppPreferences(context).lastCoreBinaryFingerprint = resolveCoreFingerprint(context)
        }
    }

    fun dismissCoreResetPrompt() {
        if (!_uiState.value.showCoreResetDialog) return
        _uiState.value = _uiState.value.copy(showCoreResetDialog = false)
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            AppPreferences(context).lastCoreBinaryFingerprint = resolveCoreFingerprint(context)
        }
    }

    private suspend fun resolveCoreFingerprint(context: Application): String? =
        pendingCoreFingerprint ?: CoreBinaryFingerprint.current(context)

    fun updateQuery(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        publishState()
    }

    private fun publishState() {
        val query = _uiState.value.query.trim()
        val filteredItems = allItems.filter {
            query.isBlank() ||
                it.title.contains(query, ignoreCase = true) ||
                it.titleId.contains(query, ignoreCase = true)
        }
        _uiState.value = _uiState.value.copy(
            items = filteredItems,
            isLoading = false,
            hasLoadedOnce = true
        )
    }

    fun addGameDirectory(uri: android.net.Uri, onResult: (Boolean, String) -> Unit) {
        val context = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            val preferences = com.sbro.emucorec.data.AppPreferences(context)
            preferences.addGameDirectory(uri.toString())

            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }

            allItems = runCatching { repository.loadInstalledGames(context) }
                .getOrElse { error ->
                    Log.e("LibraryViewModel", "Game library scan failed", error)
                    allItems
                }
            publishState()
            InstallStateBus.notifyCompleted()

            val displayName = com.sbro.emucorec.core.DocumentPathResolver.getDisplayName(context, uri.toString())
            withContext(Dispatchers.Main) {
                onResult(true, context.getString(R.string.direct_boot_added_success, displayName))
            }
        }
    }
}

