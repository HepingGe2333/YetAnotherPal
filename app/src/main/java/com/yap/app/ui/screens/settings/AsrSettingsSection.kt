package com.yap.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.yap.app.R
import com.yap.app.data.prefs.AsrSettingsRepository
import com.yap.app.data.repo.AsrTranscriber
import com.yap.app.domain.AsrDefaults
import com.yap.app.domain.AsrFailure
import com.yap.app.domain.AsrModels
import com.yap.app.domain.AsrOutcome
import com.yap.app.domain.AsrSettingsState
import com.yap.app.domain.AsrUrls
import com.yap.app.domain.AudioVideoPolicy
import com.yap.app.domain.ResolvedAsrConfig
import com.yap.app.ui.components.SectionLabel
import com.yap.app.ui.components.SettingCard
import com.yap.app.ui.components.SettingRow
import com.yap.app.ui.components.StyledOutlinedField
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** 「测试」按钮的结果：成功、接口通了但没文字、或结构化失败原因。 */
sealed interface AsrTestResult {
    data class Ok(val chars: Int) : AsrTestResult

    /** 接口可达，但测试音频（单音）没有识别出文字；这属于正常情况。 */
    data object OkEmpty : AsrTestResult

    data class Failed(val reason: AsrFailure, val status: Int? = null) : AsrTestResult
}

/**
 * 设置页「语音转写」小节（0.15）。
 *
 * 与「测试生成」一样单独一个 ViewModel：主设置 ViewModel 已经背了服务端/备份/同步一堆状态，
 * 这里只有「一次保存 + 一次探测」。
 *
 * 明文 Key 不进 [UiState]：输入框内容由 Composable 自己持有，[test] 也只是一次性参数；
 * 已保存的 Key 只以掩码（`••••1234`）出现。这样重组/状态保存都不会把 Key 带出去。
 */
@HiltViewModel
class AsrSettingsViewModel @Inject constructor(
    private val repository: AsrSettingsRepository,
    private val transcriber: AsrTranscriber
) : ViewModel() {

    data class UiState(
        val enabled: Boolean = false,
        val baseUrl: String = AsrDefaults.BASE_URL,
        val model: String = AsrDefaults.MODEL,
        val hasKey: Boolean = false,
        val maskedKey: String = "",
        val keyUnrecoverable: Boolean = false,
        val testing: Boolean = false,
        val saved: Boolean = false,
        val saveFailed: Boolean = false,
        val testResult: AsrTestResult? = null
    )

    private data class Transient(
        val testing: Boolean = false,
        val saved: Boolean = false,
        val saveFailed: Boolean = false,
        val testResult: AsrTestResult? = null
    )

    private val transient = MutableStateFlow(Transient())

    val uiState: StateFlow<UiState> = combine(repository.state, transient) { stored, extra ->
        stored.toUiState(extra)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AsrSettingsState().toUiState(Transient()))

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch { runCatching { repository.setEnabled(enabled) } }
    }

    /** 保存地址/模型/新 Key；Key 留空表示沿用旧值（与 API 编辑器一致）。 */
    fun save(baseUrl: String, model: String, apiKey: String) {
        viewModelScope.launch {
            transient.value = transient.value.copy(saved = false, saveFailed = false)
            val ok = runCatching { repository.save(baseUrl, model, apiKey) }.isSuccess
            transient.value = transient.value.copy(saved = ok, saveFailed = !ok)
            if (ok) {
                delay(SAVED_MESSAGE_MS)
                transient.value = transient.value.copy(saved = false)
            }
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            runCatching { repository.clearApiKey() }
            transient.value = transient.value.copy(saved = false, saveFailed = false)
        }
    }

    /**
     * 用当前输入发一次探测转写（没保存也能测）。
     *
     * Key 为空时用已保存的那份；两者都没有就直接给「没填 Key」，不发请求。
     */
    fun test(baseUrl: String, model: String, apiKey: String) {
        if (transient.value.testing) return
        viewModelScope.launch {
            transient.value = transient.value.copy(testing = true, testResult = null, saved = false)
            val key = apiKey.trim().ifEmpty { runCatching { repository.storedKey() }.getOrNull().orEmpty() }
            val result = if (key.isEmpty()) {
                AsrTestResult.Failed(AsrFailure.KEY_MISSING)
            } else {
                val config = ResolvedAsrConfig(
                    baseUrl = AsrUrls.normalizeBaseUrl(baseUrl),
                    model = AsrModels.normalize(model),
                    apiKey = key
                )
                when (val outcome = transcriber.probe(config)) {
                    is AsrOutcome.Transcribed ->
                        if (outcome.text.isBlank()) AsrTestResult.OkEmpty else AsrTestResult.Ok(outcome.text.length)
                    is AsrOutcome.Skipped -> AsrTestResult.Failed(outcome.reason)
                    is AsrOutcome.Failed -> AsrTestResult.Failed(outcome.reason, outcome.httpStatus)
                }
            }
            transient.value = transient.value.copy(testing = false, testResult = result)
        }
    }

    private fun AsrSettingsState.toUiState(extra: Transient): UiState = UiState(
        enabled = enabled,
        baseUrl = baseUrl,
        model = model,
        hasKey = hasKey,
        maskedKey = maskedKey,
        keyUnrecoverable = keyUnrecoverable,
        testing = extra.testing,
        saved = extra.saved,
        saveFailed = extra.saveFailed,
        testResult = extra.testResult
    )

    private companion object {
        /** 「已保存」提示停留多久：够看见，又不至于一直挂在那里。 */
        const val SAVED_MESSAGE_MS = 2_000L
    }
}

/**
 * 「语音转写」小节：开关 / Base URL / 模型 / Key（掩码）/ 测试 / 保存。
 *
 * 交互与「服务端模式」保持一致：**测试**只验证当前输入、不写盘；**保存**才落盘；
 * Key 输入框从不回填明文，只显示「已保存：••••1234」，留空保存 = 沿用旧 Key，
 * 要清掉必须显式点「清除 Key」。
 */
@Composable
fun AsrSettingsSection(viewModel: AsrSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 已存的值只用来初始化输入框；用户正在打字时 flow 不会发射，所以不会被覆盖。
    var baseUrl by rememberSaveable(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var model by rememberSaveable(state.model) { mutableStateOf(state.model) }
    var apiKey by rememberSaveable { mutableStateOf("") }

    SectionLabel(stringResource(R.string.settings_section_asr))
    SettingCard {
        SettingRow(
            title = stringResource(R.string.settings_asr_title),
            description = stringResource(R.string.settings_asr_desc),
            icon = Icons.Outlined.Mic,
            trailing = {
                Switch(
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled
                )
            },
            showDivider = true
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            StyledOutlinedField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = stringResource(R.string.settings_asr_base_url),
                placeholder = stringResource(R.string.settings_asr_base_url_hint),
                enabled = !state.testing
            )
            Spacer(Modifier.height(10.dp))
            StyledOutlinedField(
                value = model,
                onValueChange = { model = it },
                label = stringResource(R.string.settings_asr_model),
                placeholder = stringResource(R.string.settings_asr_model_hint),
                enabled = !state.testing
            )
            Spacer(Modifier.height(10.dp))
            StyledOutlinedField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = stringResource(R.string.settings_asr_key),
                placeholder = stringResource(R.string.settings_asr_key_hint),
                enabled = !state.testing,
                visualTransformation = PasswordVisualTransformation()
            )
            if (state.hasKey) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.settings_asr_key_masked, state.maskedKey),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearKey) {
                        Text(stringResource(R.string.settings_asr_clear_key))
                    }
                }
            }
            if (state.keyUnrecoverable) {
                Text(
                    text = stringResource(R.string.settings_asr_key_unrecoverable),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            if (state.saveFailed) {
                Text(
                    text = stringResource(R.string.settings_asr_save_failed),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            state.testResult?.let { result ->
                Text(
                    text = asrTestMessage(result),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (result is AsrTestResult.Failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { viewModel.test(baseUrl, model, apiKey) },
                    enabled = !state.testing && baseUrl.isNotBlank()
                ) {
                    if (state.testing) {
                        CircularProgressIndicator(
                            Modifier.width(16.dp).height(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.settings_asr_testing))
                    } else {
                        Text(stringResource(R.string.settings_asr_test))
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(
                    onClick = { viewModel.save(baseUrl, model, apiKey) },
                    enabled = !state.testing && baseUrl.isNotBlank()
                ) {
                    Text(stringResource(R.string.settings_asr_save))
                }
            }
            if (state.saved) {
                Text(
                    text = stringResource(R.string.settings_asr_saved),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!state.enabled) {
                Text(
                    text = stringResource(R.string.settings_asr_off_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 测试结果 → 一条本地化的人话；数据层只给结构化原因。 */
@Composable
private fun asrTestMessage(result: AsrTestResult): String = when (result) {
    is AsrTestResult.Ok -> stringResource(R.string.settings_asr_test_ok, result.chars)
    AsrTestResult.OkEmpty -> stringResource(R.string.settings_asr_test_ok_empty)
    is AsrTestResult.Failed -> when (result.reason) {
        AsrFailure.KEY_MISSING -> stringResource(R.string.settings_asr_test_no_key)
        AsrFailure.KEY_UNRECOVERABLE -> stringResource(R.string.settings_asr_test_key_unrecoverable)
        AsrFailure.UNAUTHORIZED -> stringResource(R.string.settings_asr_test_unauthorized)
        AsrFailure.RATE_LIMITED -> stringResource(R.string.settings_asr_test_rate_limited)
        AsrFailure.BAD_REQUEST -> stringResource(R.string.settings_asr_test_bad_request, result.status ?: 0)
        AsrFailure.TIMEOUT -> stringResource(R.string.settings_asr_test_timeout)
        AsrFailure.NETWORK -> stringResource(R.string.settings_asr_test_network)
        AsrFailure.SERVER_ERROR -> stringResource(R.string.settings_asr_test_server, result.status ?: 0)
        AsrFailure.INVALID_URL -> stringResource(R.string.settings_asr_test_invalid_url)
        AsrFailure.UNPARSABLE -> stringResource(R.string.settings_asr_test_unparsable)
        AsrFailure.EMPTY_TRANSCRIPT -> stringResource(R.string.settings_asr_test_ok_empty)
        AsrFailure.TOO_LARGE -> stringResource(
            R.string.settings_asr_test_too_large,
            (AudioVideoPolicy.AUDIO_MAX_BYTES / (1024 * 1024)).toInt()
        )
        AsrFailure.RESPONSE_TOO_LARGE -> stringResource(R.string.settings_asr_test_response_too_large)
        AsrFailure.UNSUPPORTED_FORMAT -> stringResource(R.string.settings_asr_test_unsupported)
        AsrFailure.UNREADABLE_MEDIA -> stringResource(R.string.settings_asr_test_unreadable)
        AsrFailure.EMPTY_MEDIA -> stringResource(R.string.settings_asr_test_empty)
        AsrFailure.DISABLED,
        AsrFailure.NOT_MEDIA -> stringResource(R.string.settings_asr_test_unknown)
    }
}
