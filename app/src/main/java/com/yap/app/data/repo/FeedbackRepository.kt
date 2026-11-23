package com.yap.app.data.repo

import android.content.Context
import com.yap.app.BuildConfig
import com.yap.app.data.local.FeedbackStore
import com.yap.app.domain.Feedback
import com.yap.app.domain.FeedbackDraft
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 反馈的落盘入口（`files/feedback/feedback-<ts>.json`）。
 *
 * 只负责「校验 → 存档 → 给出预填链接」：**不打开浏览器、不提交 Issue**。
 * 打开浏览器是界面层的事（要 Context + Intent），提交 Issue 需要 token（这一版刻意没有），
 * 所以 `submit` 返回的 [FeedbackReceipt] 里带的是「可以打开的链接」，不是「已经提交」。
 *
 * 校验也在这里再做一次：界面会先拦，但仓库层的入口不能依赖调用方记得校验
 * （否则一条超长内容会写出一个浏览器打不开的巨长 URL）。
 */
@Singleton
class FeedbackRepository @Inject constructor(
    @ApplicationContext context: Context
) {

    private val root: File = File(context.filesDir, FEEDBACK_DIR)

    private val store = FeedbackStore(
        root = root,
        appVersion = BuildConfig.VERSION_NAME,
        platform = PLATFORM
    )

    /**
     * 保存一条反馈。
     *
     * 成功时 [FeedbackReceipt.file] 是刚写好的 JSON，[FeedbackReceipt.issueUrl] 是给浏览器用的
     * 预填链接；失败时把异常原样交给界面（界面按 `R.string.feedback_save_failed` 提示）。
     */
    suspend fun submit(draft: FeedbackDraft): Result<FeedbackReceipt> = withContext(Dispatchers.IO) {
        runCatching {
            val error = Feedback.validate(draft)
            if (error != null) throw IllegalArgumentException("invalid feedback: ${error.name}")
            val file = store.save(draft)
            FeedbackReceipt(
                file = file,
                issueUrl = Feedback.issueUrl(
                    draft = draft,
                    appVersion = BuildConfig.VERSION_NAME,
                    platform = PLATFORM,
                    atMillis = System.currentTimeMillis()
                )
            )
        }
    }

    private companion object {
        /** 目录名：`files/feedback`（与需求里的路径一致）。 */
        const val FEEDBACK_DIR = "feedback"

        /** 平台串（写进 JSON 与 Issue 正文，方便排查）。 */
        const val PLATFORM = "android"
    }
}

/** 一次成功保存的结果。 */
data class FeedbackReceipt(
    val file: File,
    val issueUrl: String
)
