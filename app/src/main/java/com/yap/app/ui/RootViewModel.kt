package com.yap.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.prefs.SettingsRepository
import com.yap.app.data.update.UpdateCoordinator
import com.yap.app.data.update.UpdateUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class RootViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
    /** 更新检查：前台启动时静默查一次（24h 节流由它自己判断）。 */
    private val updateCoordinator: UpdateCoordinator
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** 「发现新版本」的对话框状态（挂在 MainActivity，全 App 只有一份）。 */
    val updateState: StateFlow<UpdateUiState> = updateCoordinator.state

    init {
        // 前台启动的静默检查：受设置开关与 24h 节流约束；失败/限流不会弹任何东西。
        viewModelScope.launch { updateCoordinator.maybeCheckOnStart() }
    }

    /** 用户收起对话框。 */
    fun dismissUpdatePrompt() = updateCoordinator.dismissPrompt()
}
