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

    /** 最近一次手动测试或扫描点击的目标，格式 host:port（供 ManualScreen 自动预填） */
    var lastTarget: String = "192.168.1.218:554"

    /** 待自动播放的 RTSP 地址（由内网/手动页跳转到画面页时设置，播放页消费后清空） */
    private val _pendingRtsp = MutableStateFlow<String?>(null)
    val pendingRtsp: StateFlow<String?> = _pendingRtsp.asStateFlow()

    fun setPendingRtsp(url: String?) { _pendingRtsp.value = url }

    /** 当前所在 Tab 的路由，由各屏幕 / 导航共享 */
    private val _route = MutableStateFlow("lan")
    val route: StateFlow<String> = _route.asStateFlow()

    fun setRoute(r: String) { _route.value = r }

    fun updateLastTarget(t: String) { lastTarget = t }

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