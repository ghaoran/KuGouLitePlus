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

    /** FileOutputStream 实例 → 落盘路径（仅用于观测，不持有强引用） */
    private val paths = java.util.Collections.synchronizedMap(
        java.util.WeakHashMap<Any, String>()
    )

    fun install(module: XposedModule, classLoader: ClassLoader) {
        worker = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "kgm-decrypt").apply { isDaemon = true }
        }

        hookFileOutputStreamCtor(module, classLoader)
        ModuleLog.log("$TAG: 下载即明文 Hook 已安装")
    }

    /**
     * Hook FileOutputStream 构造器记录路径。
     *
     * Android 的 FileOutputStream 没有可读的 path 字段（字段名是私有的 fd/fileChannel），
     * 唯一可靠的路径来源是构造函数的 File 参数，因此在这里把实例与路径关联起来。
     */
    private fun hookFileOutputStreamCtor(module: XposedModule, classLoader: ClassLoader) {
        try {
            val cls = FileOutputStream::class.java
            val ctor = cls.getDeclaredConstructor(File::class.java)
            module.hook(ctor)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val f = chain.args[0] as? File
                        if (f != null) {
                            paths[chain.thisObject] = f.absolutePath
                            scheduleCheck(f.absolutePath)
                        }
                    } catch (_: Throwable) {
                    }
                    result
                }
        } catch (t: Throwable) {
            ModuleLog.log("$TAG: FileOutputStream 构造器 Hook 失败: ${t.message}")
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
     * 目录轮询兜底：不管下载器用哪种方式写盘（FileOutputStream / NIO / native），
     * 只要文件出现在下载目录且是加密后缀，就会被这里捞到。
     *
     * 首次延时 20s 避开启动高峰，之后每 30s 扫一次；单文件需稳定 3s 才开始解密，
     * 避免读到只写了一半的文件。
     */
    fun startWatcher(ctx: Context) {
        worker?.execute {
            try {
                Thread.sleep(20_000)
            } catch (_: Throwable) {
            }
            val seen = HashMap<String, Long>()
            val done = HashMap<String, Long>()
            while (true) {
                try {
                    if (!Settings.isFeature(ctx, Settings.KEY_AUTO_DECRYPT_DOWNLOAD)) {
                        Thread.sleep(10_000)
                        continue
                    }
                    for (d in watchDirs()) {
                        if (!d.isDirectory) continue
                        val files = d.listFiles() ?: continue
                        for (f in files) {
                            if (!f.isFile || !KgmDecryptor.isEncrypted(f.name)) continue
                            val key = f.absolutePath
                            val now = System.currentTimeMillis()
                            val last = done[key]
                            if (last != null && now - last < 6 * 3600_000L) continue
                            val first = seen[key]
                            if (first == null) {
                                seen[key] = now
                                continue
                            }
                            if (now - first < 3000) continue
                            // 三个进程都会轮询同一目录，用锁文件避免并发解密把文件写坏
                            if (!tryLock(f)) continue
                            // 无论成功与否都记一笔：已是明文的文件不需要每秒重试
                            done[key] = now
                            seen.remove(key)
                            val before = f.length()
                            val r = KgmDecryptor.decryptInPlace(f)
                            releaseLock(f)
                            if (r != null || f.length() != before) {
                                notifyMediaStore(
                                    if (r != null) r else f
                                )
                            }
                        }
                    }
                } catch (_: Throwable) {
                }
                try {
                    Thread.sleep(10_000)
                } catch (_: Throwable) {
                    return@execute
                }
            }
        }
        ModuleLog.log("$TAG: 目录轮询已启动")
    }


    /** 跨进程互斥：抢到 .lock 才处理 */
    private fun tryLock(f: File): Boolean {
        return try {
            val lock = File(f.parentFile, "." + f.name + ".lock")
            if (lock.exists() && System.currentTimeMillis() - lock.lastModified() < 60_000L) {
                return false
            }
            lock.createNewFile()
            lock.setLastModified(System.currentTimeMillis())
            true
        } catch (_: Throwable) {
            true
        }
    }

    private fun releaseLock(f: File) {
        try {
            File(f.parentFile, "." + f.name + ".lock").delete()
        } catch (_: Throwable) {
        }
    }

    /**
     * 只监控「下载完成」的目录。
     *
     * ⚠️ 绝不能监控 down_c/default：那里是下载**过程中**的加密碎片
     * （xxx_2_hash_N.mgg / .mflac），不是成品音频。之前监控它导致
     * 每 10 秒对同一批碎片反复解密失败、日志刷屏、CPU 空转。
     */
    private fun watchDirs(): List<File> = listOf(
        File("/storage/emulated/0/Download/KuGouLite/Music"),
        File("/storage/emulated/0/Download/kgmusic/download"),
        File("/storage/emulated/0/Android/data/com.kugou.android.lite/files/Music"),
    )

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
