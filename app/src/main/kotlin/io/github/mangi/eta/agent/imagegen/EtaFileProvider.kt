package io.github.mangi.eta.agent.imagegen

import androidx.core.content.FileProvider

/** 专用 FileProvider 子类，避免直接注册 AndroidX 基类在部分设备或库冲突时触发崩溃。 */
internal class EtaFileProvider : FileProvider()
