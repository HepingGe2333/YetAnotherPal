package com.yap.app.data.local

import com.yap.app.domain.Feedback
import com.yap.app.domain.FeedbackDraft
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

/**
 * 反馈的本地落盘（`files/feedback/feedback-<ts>.json`）。
 *
 * 为什么先落盘再说：这一版没有 token、不会自动提交，用户最后是在浏览器里点「Submit」。
 * 浏览器打不开、用户改主意、手机切后台——内容都必须还在。所以「保存」永远是第一步，
 * 而且必须是**原子写**：先写 `.tmp`、`fsync`、再 `renameTo`，中途断电也不会留下半截 JSON
 * （`feedback-*.json.tmp` 只是垃圾文件，不会让下一次读取的内容错位）。
 *
 * 只做文件 IO：不读 Resources、不碰 Room、不拿 Context。测试直接给一个临时目录就能跑，
 * 真正的路径（`context.filesDir/feedback`）由 `FeedbackRepository` 注入。
 */
class FeedbackStore(
    /** 反馈目录本身（生产是 `files/feedback`）。 */
    private val root: File,
    private val appVersion: String,
    private val platform: String,
    private val now: () -> Long = System::currentTimeMillis
) {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /**
     * 落盘一条反馈，返回写好的文件。
     *
     * 文件名用毫秒时间戳；同一毫秒内写第二条时加 `-2`、`-3` 后缀（不覆盖别人）。
     */
    fun save(draft: FeedbackDraft): File {
        if (!root.isDirectory && !root.mkdirs() && !root.isDirectory) {
            throw IOException("cannot create feedback directory: ${root.absolutePath}")
        }
        val stamp = now()
        val target = uniqueTarget(stamp)
        val record = FeedbackRecord(
            kind = draft.kind.name,
            content = draft.content.trim(),
            contact = draft.contact.trim().ifEmpty { null },
            appVersion = appVersion,
            platform = platform,
            createdAt = stamp,
            createdAtIso = Instant.ofEpochMilli(stamp).toString(),
            // 存地址而不是「提交结果」：没有凭据时我们永远不知道用户到底点了没有。
            issueUrl = Feedback.issueUrl(
                draft = draft,
                appVersion = appVersion,
                platform = platform,
                atMillis = stamp
            )
        )
        writeAtomically(target, json.encodeToString(record))
        return target
    }

    private fun uniqueTarget(stamp: Long): File {
        var candidate = File(root, "feedback-$stamp.json")
        var suffix = 2
        while (candidate.exists()) {
            candidate = File(root, "feedback-$stamp-$suffix.json")
            suffix++
        }
        return candidate
    }

    /** 原子写：tmp → fsync → rename。失败时清掉 tmp，不留下「看起来像反馈」的半截文件。 */
    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                // 极少数文件系统不支持 rename 覆盖语义：退化成「先删目标再改名」，
                // 仍然不会出现写到一半的文件被当成正式文件。
                if (!target.delete() || !tmp.renameTo(target)) {
                    throw IOException("cannot move feedback file into place: ${target.absolutePath}")
                }
            }
        } catch (error: Throwable) {
            runCatching { tmp.delete() }
            throw error
        }
    }
}

/** 落盘的文件形状（字段名是给人和脚本读的，保持稳定）。 */
@Serializable
private data class FeedbackRecord(
    val kind: String,
    val content: String,
    val contact: String? = null,
    val appVersion: String,
    val platform: String,
    val createdAt: Long,
    val createdAtIso: String,
    val issueUrl: String
)
