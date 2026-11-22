package com.yap.app.di

import com.yap.app.data.prefs.AsrSettingsRepository
import com.yap.app.data.repo.AsrConfigSource
import com.yap.app.data.repo.AsrMediaSource
import com.yap.app.data.repo.AudioVideoRepository
import com.yap.app.data.repo.SessionMedia
import com.yap.app.domain.MessageAttachment
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * ASR（语音/视频转写）的装配。
 *
 * 两个依赖面都抽成接口，目的只有一个：纯 JVM 单测能注入假的配置源与假的字节源
 * （`AsrTranscriberTest`），生产实现仍然各只有一份。
 *
 * - [AsrConfigSource] → [AsrSettingsRepository]（DataStore + Keystore 加密 Key）；
 * - [AsrMediaSource] → [AudioVideoRepository.readBytes]（本地文件 / 服务端媒体，含大小守卫）。
 */
@Module
@InstallIn(SingletonComponent::class)
object AsrModule {

    @Provides
    @Singleton
    fun provideAsrConfigSource(impl: AsrSettingsRepository): AsrConfigSource = impl

    @Provides
    @Singleton
    fun provideAsrMediaSource(impl: AudioVideoRepository): AsrMediaSource =
        object : AsrMediaSource {
            override suspend fun read(attachment: MessageAttachment): ByteArray? =
                impl.readBytes(
                    SessionMedia(messageId = attachment.uri, index = 0, attachment = attachment)
                )
        }
}
