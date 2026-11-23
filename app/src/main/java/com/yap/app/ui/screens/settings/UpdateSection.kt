package com.yap.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yap.app.R
import com.yap.app.data.prefs.AppSettings
import com.yap.app.data.update.UpdateCheckStatus
import com.yap.app.data.update.UpdateUiState
import com.yap.app.domain.ReleaseInfo
import com.yap.app.domain.UpdatePolicy
import com.yap.app.ui.components.SectionLabel
import com.yap.app.ui.components.SettingCard
import com.yap.app.ui.components.SettingRow
import com.yap.app.ui.theme.LocalAccentColor

/**
 * 设置页的「更新」小节。
 *
 * 语气与别处一致：不推销、不催升级。检查更新是一行可点的设置项，状态就写在它下面；
 * 自动检查默认开、24 小时最多一次，失败与限流只留一句「稍后再试」，不弹错误。
 */
@Composable
fun UpdateSection(
    settings: AppSettings,
    update: UpdateUiState,
    onCheck: () -> Unit,
    onToggleAutoCheck: (Boolean) -> Unit,
    onTogglePrereleases: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    SectionLabel(stringResource(R.string.settings_section_update), modifier = modifier)
    SettingCard {
        SettingRow(
            title = stringResource(R.string.settings_update_check_title),
            description = updateStatusText(update),
            icon = Icons.Outlined.SystemUpdate,
            trailing = {
                if (update.checking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = LocalAccentColor.current
                    )
                } else {
                    Text(
                        // 「当前版本」永远显示：检查结果再模糊，用户至少知道自己现在是哪一版。
                        "v${update.currentVersion}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            onClick = { if (!update.checking) onCheck() },
            showDivider = true
        )
        // 「有新版本」时多一行明确的动作：当前版本在上面那一行，最新版本在标题里，
        // 按钮只负责**手工**打开发布页——不自动下载、不装 APK。
        if (update.availableVersion != null && update.availableUrl.isNotBlank()) {
            val context = LocalContext.current
            SettingRow(
                title = stringResource(R.string.settings_update_install_title, update.availableVersion),
                description = stringResource(R.string.settings_update_install_desc),
                trailing = {
                    TextButton(onClick = { openReleasePage(context, update.availableUrl) }) {
                        Text(stringResource(R.string.update_dialog_download))
                    }
                },
                showDivider = true
            )
        }
        SettingRow(
            title = stringResource(R.string.settings_update_auto_title),
            description = stringResource(R.string.settings_update_auto_desc),
            icon = Icons.Outlined.NewReleases,
            trailing = {
                Switch(
                    checked = settings.updateCheckEnabled,
                    onCheckedChange = onToggleAutoCheck,
                    colors = switchColors()
                )
            },
            showDivider = true
        )
        SettingRow(
            title = stringResource(R.string.settings_update_prerelease_title),
            description = stringResource(R.string.settings_update_prerelease_desc),
            trailing = {
                Switch(
                    checked = settings.updateIncludePrereleases,
                    onCheckedChange = onTogglePrereleases,
                    colors = switchColors()
                )
            },
            showDivider = false
        )
    }
}

/** 状态行：数据层只给枚举，句子在这里按当前语言拼。 */
@Composable
private fun updateStatusText(update: UpdateUiState): String = when (update.status) {
    UpdateCheckStatus.NEVER -> stringResource(R.string.settings_update_desc)
    UpdateCheckStatus.CHECKING -> stringResource(R.string.settings_update_checking)
    UpdateCheckStatus.UP_TO_DATE -> stringResource(R.string.settings_update_up_to_date)
    UpdateCheckStatus.AVAILABLE -> stringResource(
        R.string.settings_update_available,
        update.availableVersion.orEmpty()
    )
    UpdateCheckStatus.SKIPPED_PRERELEASE -> stringResource(R.string.settings_update_skipped_prerelease)
    UpdateCheckStatus.UNAVAILABLE -> stringResource(R.string.settings_update_unavailable)
    // 失败不弹错误、不指责用户：就一行「稍后再试」，下一轮自动检查会重来。
    UpdateCheckStatus.FAILED -> stringResource(R.string.settings_update_failed)
}

/**
 * 「发现新版本」对话框：版本号、发布说明摘要、稍后 / 去下载。
 *
 * 挂在 `MainActivity`（自动检查）与设置页（手动检查）共用同一份 [UpdateUiState.prompt]，
 * 所以同一个版本不会弹两次。
 */
@Composable
fun UpdateAvailableDialog(
    release: ReleaseInfo,
    onLater: () -> Unit,
    onDownload: () -> Unit
) {
    val notes = UpdatePolicy.summarize(release.body)
    AlertDialog(
        onDismissRequest = onLater,
        title = {
            Text(stringResource(R.string.update_dialog_title, release.displayTag()))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (release.prerelease) {
                    Text(
                        stringResource(R.string.update_dialog_prerelease),
                        style = MaterialTheme.typography.labelSmall,
                        color = LocalAccentColor.current
                    )
                }
                if (release.name.isNotBlank() && release.name != release.tagName) {
                    Text(
                        release.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
                if (notes.isBlank()) {
                    Text(
                        stringResource(R.string.update_dialog_no_notes),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDownload) { Text(stringResource(R.string.update_dialog_download)) }
        },
        dismissButton = {
            TextButton(onClick = onLater) { Text(stringResource(R.string.update_dialog_later)) }
        }
    )
}

/**
 * 打开 GitHub 发布页。
 *
 * 只接受 https（[UpdatePolicy.isSafeReleaseUrl]）：备份/服务端里的 URL 是数据，
 * 不能让一个 `intent:` / `file:` 地址被当成「去下载」。打不开就算了，不弹错误。
 */
fun openReleasePage(context: Context, url: String) {
    if (!UpdatePolicy.isSafeReleaseUrl(url)) return
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
