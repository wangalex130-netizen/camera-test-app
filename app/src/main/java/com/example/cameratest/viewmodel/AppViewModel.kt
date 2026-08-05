package com.example.cameratest.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cameratest.data.TestResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel : ViewModel() {

    private val _results = MutableStateFlow<List<TestResult>>(emptyList())
    val results: StateFlow<List<TestResult>> = _results.asStateFlow()

    private var seq = 0L

    /** 追加一条结果，自动分配 id，最多保留 200 条 */
    fun addResult(r: TestResult) {
        viewModelScope.launch {
            seq++
            _results.value = (listOf(r.copy(id = seq)) + _results.value).take(200)
        }
    }

    fun clear() {
        _results.value = emptyList()
    }
}
