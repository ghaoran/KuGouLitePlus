package com.bldf1.kugoulite.plus

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile

/**
 * 借酷狗自己的 libjengine.so 解密 mflac / kgg。
 *
 * KGG 密钥由酷狗 infra 层结合本机机器码推导（官方 Windows 转换器
 * kgg-dec.exe 也是调酷狗自己的 infra.dll），外部无法复现，因此直接
 * 调用它自己的解密器：
 *   CreateDecryption(const char* ekey, int len) -> StreamCencrypt*
 *   Decrypt(StreamCencrypt* self, char* buf, int len, uint64_t offset)
 */
internal object NativeH {

    private const val TAG = "NativeH"
    private const val HEADER = 0x400
    private const val BLOCK = 1 shl 20

    @Volatile private var tried = false
    @Volatile private var ready = false

    private external fun nativeSetup(): Boolean
    private external fun nativeCreate(ekey: String): Long
    private external fun nativeDecrypt(handle: Long, buf: ByteArray, off: Int, len: Int, pos: Long): Int
    private external fun nativeFree(handle: Long)

    private fun ensureLoaded(): Boolean {
        if (tried) return ready
        tried = true
        ready = try {
            System.loadLibrary("kgmdec")
            nativeSetup()
        } catch (t: Throwable) {
            ModuleLog.log("$TAG: native 初始化失败 " + t.message)
            false
        }
        return ready
    }

    fun isEncryptedContainer(name: String): Boolean {
        val n = name.lowercase()
        return n.contains(".kgg") || n.contains(".mflac") ||
            n.contains(".kgm") || n.contains(".kgma") || n.contains(".kgms")
    }

    /** 解密 [src] 到 [dst]，成功返回检测到的音频格式 */
    fun decryptFile(src: File, dst: File, dbPath: String?): String? {
        if (!ensureLoaded()) return null
        RandomAccessFile(src, "r").use { input ->
            val head = ByteArray(HEADER)
            if (input.read(head) < 0x50) return null

            val hlen = le32(head, 0x10)
            val ver = le32(head, 0x14)
            if (hlen <= 0 || hlen >= src.length()) return null

            val ekey = if (ver == 5) audioHash(head)?.let { lookupEkey(dbPath, it) } else null
            if (ekey == null) {
                ModuleLog.log("$TAG: 取不到 ekey (ver=" + ver + ")")
                return null
            }
            ModuleLog.log("$TAG: ekey len=" + ekey.length)

            val h = nativeCreate(ekey)
            if (h == 0L) {
                ModuleLog.log("$TAG: CreateDecryption 失败")
                return null
            }
            try {
                input.seek(hlen.toLong())
                dst.parentFile?.mkdirs()
                dst.outputStream().buffered().use { out ->
                    val buf = ByteArray(BLOCK)
                    var pos = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        nativeDecrypt(h, buf, 0, n, pos)
                        out.write(buf, 0, n)
                        pos += n
                    }
                }
            } finally {
                nativeFree(h)
            }
        }
        return KgmDecryptor.detectFormat(dst)
    }

    private fun audioHash(h: ByteArray): String? {
        val len = le32(h, 0x44)
        if (len <= 0 || 0x48 + len > h.size) return null
        return String(h, 0x48, len, Charsets.UTF_8).trimEnd('\u0000')
    }

    /** 在酷狗数据库里按 mgg_hash / filehash 找 fileuserkey */
    private fun lookupEkey(dbPath: String?, hash: String): String? {
        if (dbPath == null) return null
        if (!File(dbPath).isFile) {
            ModuleLog.log("$TAG: 数据库不存在 " + dbPath)
            return null
        }
        try {
            SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                val sql = "select fileuserkey from 'file' where mgg_hash=? or filehash=? limit 1"
                db.rawQuery(sql, arrayOf(hash, hash)).use { c ->
                    if (c.moveToFirst()) {
                        val k = c.getString(0)
                        if (!k.isNullOrEmpty()) return normalizeEkey(k)
                    }
                }
            }
        } catch (t: Throwable) {
            ModuleLog.log("$TAG: 查库失败 " + t.message)
        }
        return null
    }

    /** fileuserkey 形如 `xxx-force-52` / `xxx-4-123`，去掉后缀 */
    private fun normalizeEkey(raw: String): String {
        var s = raw.trim()
        val df = s.indexOf("-force-")
        if (df > 0) s = s.substring(0, df)
        if (s.length > 34 && s[32] == '-' && s[33] == '4') s = s.substring(0, 32)
        return s
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    fun defaultDbPath(): String =
        "/data/data/com.kugou.android.lite/databases/kugou_music_phone_v7.db"
}
