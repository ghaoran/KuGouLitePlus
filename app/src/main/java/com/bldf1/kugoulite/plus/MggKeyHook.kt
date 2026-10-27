package com.bldf1.kugoulite.plus

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 抓取酷狗真正用于 KGG/mflac 解密的 mggKey。
 *
 * 调用链（已从 APK 反编译确认）：
 *   Engine.c() -> IKggKeyQueryCallback.query()  [job.p$c]
 *     -> y.a(hash)          缓存命中
 *     -> y.c(mixId,hash,q)  网络取
 */
internal object MggKeyHook {

    private const val TAG = "MggKey"
    private const val MAX = 512

    private val keys = ConcurrentHashMap<String, String>()

    @Volatile private var storeFile: File? = null

    fun install(module: XposedModule, classLoader: ClassLoader) {
        hookQuery(module, classLoader)
        hookGetter(module, classLoader)
        ModuleLog.log("$TAG: Hook 已安装")
    }

    private fun hookQuery(module: XposedModule, classLoader: ClassLoader) {
        try {
            // ⚠️ 必须 Hook **实现类**，不是接口！
            // 接口的 query() 是 abstract，LSPosed 直接拒绝：
            //   Cannot hook abstract methods: Engine$IKggKeyQueryCallback.query(...)
            // 真机调用实现是 job.p$c（com.kugou.common.filemanager.job.p$c.query）
            val iface = try {
                Class.forName("com.kugou.common.filemanager.job.p\$c", false, classLoader)
            } catch (_: Throwable) {
                // 回退到接口（某些版本实现类名不同）
                Class.forName(
                    "com.kugou.common.filemanager.downloadengine.Engine\$IKggKeyQueryCallback",
                    false, classLoader
                )
            }
            val m = iface.getDeclaredMethod(
                "query", Long::class.javaPrimitiveType, String::class.java, String::class.java
            )
            module.hook(m)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept { chain ->
                    val result = chain.proceed()
                    try {
                        val hash = chain.args[1] as? String
                        val key = result as? String
                        if (!hash.isNullOrEmpty() && !key.isNullOrEmpty()) remember(hash, key)
                    } catch (_: Throwable) {
                    }
                    result
                }
        } catch (t: Throwable) {
            ModuleLog.log("$TAG: query hook 失败 " + t.message)
        }
    }

    /** Hook y.a(String) / y.c(long,String,String)，二者都可能返回 key */
    private fun hookGetter(module: XposedModule, classLoader: ClassLoader) {
        try {
            val cls = Class.forName("com.kugou.framework.service.util.y", false, classLoader)
            var n = 0
            for (m in cls.declaredMethods) {
                val name = m.name
                val pt = m.parameterTypes
                val one = name == "a" && pt.size == 1 && pt[0] == String::class.java
                val three = name == "c" && pt.size == 3
                if (!one && !three) continue
                n++
                module.hook(m)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        val result = chain.proceed()
                        try {
                            val key = result as? String
                            if (!key.isNullOrEmpty()) {
                                val hash = chain.args.firstOrNull {
                                    it is String && it.length in 16..64
                                } as? String
                                if (hash != null) remember(hash, key)
                            }
                        } catch (_: Throwable) {
                        }
                        result
                    }
            }
            ModuleLog.log("$TAG: y 类 hook 完成 " + n + " 个方法")
        } catch (t: Throwable) {
            ModuleLog.log("$TAG: y hook 失败 " + t.message)
        }
    }

    private fun remember(hash: String, key: String) {
        if (key.length > MAX) return
        val old = keys.put(hash, key)
        if (old == key) return
        ModuleLog.log("$TAG: 抓到 key hash=" + hash.take(16) + " len=" + key.length)
        persist(hash, key)
    }

    private fun persist(hash: String, key: String) {
        val f = storeFile ?: return
        try {
            f.parentFile?.mkdirs()
            f.appendText(hash + "\t" + key + "\n")
        } catch (_: Throwable) {
        }
    }

    fun setStoreFile(f: File) {
        storeFile = f
        loaded = false
        load()
    }

    private fun load() {
        val f = storeFile ?: return
        if (!f.isFile) return
        try {
            f.readLines().forEach { line ->
                val i = line.indexOf('\t')
                if (i > 0) keys[line.substring(0, i)] = line.substring(i + 1)
            }
            loaded = true
            ModuleLog.log("$TAG: 已载入 " + keys.size + " 个 key")
        } catch (_: Throwable) {
            loaded = true
        }
    }

    @Volatile private var loaded = false

    fun keyFor(hash: String): String? {
        if (!loaded) load()
        return keys[hash]
    }

    fun allKeys(): Map<String, String> = keys.toMap()
}
