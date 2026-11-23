package com.yap.app.ui.screens.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yap.app.R
import com.yap.app.domain.Feedback
import com.yap.app.domain.FeedbackDraft
import com.yap.app.domain.FeedbackError
import com.yap.app.domain.FeedbackKind
import com.yap.app.ui.components.StyledOutlinedField
import com.yap.app.ui.components.TextPill

/**
 * 反馈表单（设置页 → 反馈）。
 *
 * 三件事：
 * 1. 选类型（问题 / 建议 / 其他）+ 写内容（≤1000 字，输入时就截断）+ 可选联系方式；
 * 2. 提交时先走 [Feedback.validate] 校验，错误就地显示（不弹新弹窗、不丢用户已经写的内容）；
 * 3. 真正的保存与「生成链接」交给 ViewModel / `FeedbackRepository`，
 *    本组件只在成功后由上层打开浏览器。
 *
 * 表单状态是组件本地的 `remember`：关掉弹窗即丢弃。内容不进 SavedStateHandle、
 * 不进日志——反馈里可能有用户自己的账号信息。
 */
@Composable
fun FeedbackDialog(
    saving: Boolean,
    onDismiss: () -> Unit,
    onSubmit: (FeedbackKind, String, String) -> Unit
) {
    var kind by remember { mutableStateOf(FeedbackKind.PROBLEM) }
    var content by remember { mutableStateOf("") }
    var contact by remember { mutableStateOf("") }
    var showError by remember { mutableStateOf(false) }

    val draft = FeedbackDraft(kind = kind, content = content, contact = contact)
    val error = if (showError) Feedback.validate(draft) else null

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.feedback_dialog_title)) },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    stringResource(R.string.feedback_kind_section),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FeedbackKind.entries.forEach { option ->
                        TextPill(
                            text = stringResource(feedbackKindLabel(option)),
                            selected = kind == option,
                            onClick = { kind = option }
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                StyledOutlinedField(
                    value = content,
                    // 输入时就按上限截断：粘贴一大段也不会让计数器爆掉，更不会生成打不开的 URL。
                    onValueChange = { value -> content = value.take(Feedback.MAX_CONTENT_CHARS) },
                    label = stringResource(R.string.feedback_content_label),
                    placeholder = stringResource(R.string.feedback_content_hint),
                    singleLine = false,
                    minLines = 4,
                    maxLines = 8,
                    isError = error == FeedbackError.CONTENT_TOO_LONG,
                    supportingText = stringResource(
                        R.string.feedback_content_counter,
                        content.length,
                        Feedback.MAX_CONTENT_CHARS
                    )
                )
                Spacer(Modifier.height(10.dp))
                StyledOutlinedField(
                    value = contact,
                    onValueChange = { value -> contact = value.take(Feedback.MAX_CONTACT_CHARS) },
                    label = stringResource(R.string.feedback_contact_label),
                    placeholder = stringResource(R.string.feedback_contact_hint),
                    singleLine = true,
                    isError = error == FeedbackError.CONTACT_TOO_LONG
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = feedbackErrorText(error),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(10.dp))
                // 说清楚「存在哪」：这一版只保存到本机 + 打开浏览器提交，不写清楚会让人以为已经发走了。
                Text(
                    text = stringResource(R.string.feedback_saved_where),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving,
                onClick = {
                    if (Feedback.validate(draft) != null) {
                        showError = true
                    } else {
                        onSubmit(kind, content, contact)
                    }
                }
            ) { Text(stringResource(R.string.feedback_submit)) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) {
                Text(stringResource(R.string.feedback_cancel))
            }
        }
    )
}

/** 类型选项的文案（中文/英文各一套，见 `values{,-en}/strings.xml`）。 */
fun feedbackKindLabel(kind: FeedbackKind): Int = when (kind) {
    FeedbackKind.PROBLEM -> R.string.feedback_kind_problem
    FeedbackKind.SUGGESTION -> R.string.feedback_kind_suggestion
    FeedbackKind.OTHER -> R.string.feedback_kind_other
}

/** 校验失败时那一行红字。 */
@Composable
private fun feedbackErrorText(error: FeedbackError): String = when (error) {
    FeedbackError.CONTENT_EMPTY -> stringResource(R.string.feedback_error_empty)
    FeedbackError.CONTENT_TOO_LONG ->
        stringResource(R.string.feedback_error_too_long, Feedback.MAX_CONTENT_CHARS)
    FeedbackError.CONTACT_TOO_LONG ->
        stringResource(R.string.feedback_error_contact_too_long, Feedback.MAX_CONTACT_CHARS)
}

/**
 * 打开 GitHub Issue 预填页。
 *
 * 只接受 [Feedback.isSafeIssueUrl] 认的地址（https + 本仓库 new-issue 页）：链接是拼出来的数据，
 * 不能让它变成一个 `intent:` / 别的 host 的跳转。返回 true 表示真的发出了 Intent；
 * false 时由调用方提示「已保存，但没能打开浏览器」——内容已经落盘，不会丢。
 */
fun openFeedbackIssue(context: Context, url: String): Boolean {
    if (!Feedback.isSafeIssueUrl(url)) return false
    return runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}
