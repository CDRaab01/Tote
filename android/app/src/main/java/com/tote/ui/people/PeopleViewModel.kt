package com.tote.ui.people

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tote.data.remote.ApiService
import com.tote.data.remote.PersonDto
import com.tote.data.remote.PersonIn
import com.tote.util.ApiErrors
import com.tote.util.UiState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The people list.
 *
 * Server-only, with no Room cache behind it — unlike the catalog. The bins have to be readable in
 * an attic with no signal because that is where they physically are; people are read at the
 * kitchen table on the way to deciding what to go and look for, and a stale wearer profile is
 * worse than an honest failure: it is what sends someone to the attic for a size the child grew
 * out of in March.
 */
@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val api: ApiService,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<PersonDto>>>(UiState.Loading)
    val state: StateFlow<UiState<List<PersonDto>>> = _state.asStateFlow()

    private val _create = MutableStateFlow<UiState<Unit>>(UiState.Idle)
    val create: StateFlow<UiState<Unit>> = _create.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            runCatching { api.people() }
                .onSuccess { _state.value = UiState.Success(it) }
                .onFailure { _state.value = UiState.Error(ApiErrors.message(it, "Couldn't load people.")) }
        }
    }

    fun addPerson(name: String, birthdate: String?) {
        viewModelScope.launch {
            _create.value = UiState.Loading
            runCatching { api.createPerson(PersonIn(name = name.trim(), birthdate = birthdate)) }
                .onSuccess {
                    _create.value = UiState.Success(Unit)
                    refresh()
                }
                .onFailure {
                    _create.value = UiState.Error(ApiErrors.message(it, "Couldn't add that person."))
                }
        }
    }

    fun clearCreateState() {
        _create.value = UiState.Idle
    }
}
