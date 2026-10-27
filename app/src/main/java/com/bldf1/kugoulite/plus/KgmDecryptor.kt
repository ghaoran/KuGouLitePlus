package com.bldf1.kugoulite.plus

import java.io.File
import java.io.RandomAccessFile

/**
 * 酷狗加密音频（KGM / KGMA / VPR）→ 标准音频 解密器
 *
 * 纯 Kotlin 实现，不依赖任何 .so / 外部程序。
 *
 * ## 算法（KGM V2 XOR 掩码，已逐字节实测验证）
 *
 * fileKey   = header[0x1C:0x2C] + 0x00            // 17 字节
 * headerLen = LE32(header[0x10])                  // 通常 1024
 * audio     = 文件自 headerLen 起的内容
 *
 * 关键：offset 是「相对 audio 起点」从 0 开始计数，
 *      不是文件绝对偏移。用绝对偏移会导致 mask/key 索引错位，解出来全是乱码。
 *
 * out[i] = T( maskV2(i) ^ audio[i] ^ fileKey[i % 17] )
 * T(x)   = x ^ ((x & 0x0F) << 4)
 * maskV2(i) = tableV2[i % 272] ^ maskV1(i >> 4)
 * maskV1(o): while (o >= 0x11) { v ^= table1[o % 272]; o >>= 4; v ^= table2[o % 272]; o >>= 4 }
 *
 * .vpr 额外再异或一个固定 key: out[i] ^= vprKey[i % 17]
 */
internal object KgmDecryptor {

    private const val TABLE_SIZE = 272
    private const val HEADER_PROBE = 0x3C
    private const val KEY_LEN = 17
    private const val BLOCK = 1 shl 20
    private val TABLE1 = byteArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 1, 33, 1, 97, 1, 33, 1, -31, 1, 33, 1, 97, 1, 33, 1,
        -46, 35, 2, 2, 66, 66, 2, 2, -62, -62, 2, 2, 66, 66, 2, 2,
        -45, -45, 2, 3, 99, 67, 99, 3, -29, -61, -29, 3, 99, 67, 99, 3,
        -108, -76, -108, 101, 4, 4, 4, 4, -124, -124, -124, -124, 4, 4, 4, 4,
        -107, -107, -107, -107, 4, 5, 37, 5, -27, -123, -91, -123, -27, 5, 37, 5,
        -42, -74, -106, -74, -42, 39, 6, 6, -58, -58, -122, -122, -58, -58, 6, 6,
        -41, -41, -105, -105, -41, -41, 6, 7, -25, -57, -25, -121, -25, -57, -25, 7,
        24, 56, 24, 120, 24, 56, 24, -23, 8, 8, 8, 8, 8, 8, 8, 8,
        25, 25, 25, 25, 25, 25, 25, 25, 8, 9, 41, 9, 105, 9, 41, 9,
        -38, 58, 26, 58, 90, 58, 26, 58, -38, 43, 10, 10, 74, 74, 10, 10,
        -37, -37, 27, 27, 91, 91, 27, 27, -37, -37, 10, 11, 107, 75, 107, 11,
        -100, -68, -100, 124, 28, 60, 28, 124, -100, -68, -100, 109, 12, 12, 12, 12,
        -99, -99, -99, -99, 29, 29, 29, 29, -99, -99, -99, -99, 12, 13, 45, 13,
        -34, -66, -98, -66, -34, 62, 30, 62, -34, -66, -98, -66, -34, 47, 14, 14,
        -33, -33, -97, -97, -33, -33, 31, 31, -33, -33, -97, -97, -33, -33, 14, 15,
        0, 32, 0, 96, 0, 32, 0, -32, 0, 32, 0, 96, 0, 32, 0, -15,
    )

    private val TABLE2 = byteArrayOf(
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 1, 35, 1, 103, 1, 35, 1, -17, 1, 35, 1, 103, 1, 35, 1,
        -33, 33, 2, 2, 70, 70, 2, 2, -50, -50, 2, 2, 70, 70, 2, 2,
        -34, -34, 2, 3, 101, 71, 101, 3, -19, -49, -19, 3, 101, 71, 101, 3,
        -99, -65, -99, 99, 4, 4, 4, 4, -116, -116, -116, -116, 4, 4, 4, 4,
        -100, -100, -100, -100, 4, 5, 39, 5, -21, -115, -81, -115, -21, 5, 39, 5,
        -37, -67, -97, -67, -37, 37, 6, 6, -54, -54, -114, -114, -54, -54, 6, 6,
        -38, -38, -98, -98, -38, -38, 6, 7, -23, -53, -23, -113, -23, -53, -23, 7,
        25, 59, 25, 127, 25, 59, 25, -25, 8, 8, 8, 8, 8, 8, 8, 8,
        24, 24, 24, 24, 24, 24, 24, 24, 8, 9, 43, 9, 111, 9, 43, 9,
        -41, 57, 27, 57, 95, 57, 27, 57, -41, 41, 10, 10, 78, 78, 10, 10,
        -42, -42, 26, 26, 94, 94, 26, 26, -42, -42, 10, 11, 109, 79, 109, 11,
        -107, -73, -107, 123, 29, 63, 29, 123, -107, -73, -107, 107, 12, 12, 12, 12,
        -108, -108, -108, -108, 28, 28, 28, 28, -108, -108, -108, -108, 12, 13, 47, 13,
        -45, -75, -105, -75, -45, 61, 31, 61, -45, -75, -105, -75, -45, 45, 14, 14,
        -46, -46, -106, -106, -46, -46, 30, 30, -46, -46, -106, -106, -46, -46, 14, 15,
        0, 34, 0, 102, 0, 34, 0, -18, 0, 34, 0, 102, 0, 34, 0, -2,
    )

    private val TABLE_V2 = byteArrayOf(
        -72, -43, 61, -78, -23, -81, 120, -116, -125, 51, 113, 81, 118, -96, -51, 55,
        47, 62, 53, -115, -87, -66, -104, -73, -25, -116, 34, -50, 90, 97, -33, 104,
        105, -119, -2, -91, -74, -34, -87, 119, -4, -56, -67, -67, -27, 109, 62, 90,
        54, -17, 105, 78, -66, -31, -23, 102, 28, -13, -39, 2, -74, -14, 18, -101,
        68, -48, 111, -71, 53, -119, -74, 70, 109, 115, -126, 6, 105, -63, -19, -41,
        -123, -62, 48, -33, -94, 98, -66, 121, 45, 98, 98, 61, 13, 126, -66, 72,
        -119, 35, 2, -96, -28, -43, 117, 81, 50, 2, 83, -3, 22, 58, 33, 59,
        22, 15, -61, -78, -69, -77, -30, -70, 58, 61, 19, -20, -10, 1, 69, -124,
        -91, 112, 15, -109, 73, 12, 100, -51, 49, -43, -52, 76, 7, 1, -98, 0,
        26, 35, -112, -65, -120, 30, 59, -85, -90, 62, -60, 115, 71, 16, 126, 59,
        94, -68, -29, 0, -124, -1, 9, -44, -32, -119, 15, 91, 88, 112, 79, -5,
        101, -40, 92, 83, 27, -45, -56, -58, -65, -17, -104, -80, 80, 79, 15, -22,
        -27, -125, 88, -116, 40, 44, -124, 103, -51, -48, -98, 71, -37, 39, 80, -54,
        -12, 99, 99, -24, -105, 127, 27, 75, 12, -62, -63, 33, 76, -52, 88, -11,
        -108, 82, -93, -13, -45, -32, 104, -12, 0, 35, -13, 94, 10, 123, -109, -35,
        -85, 18, -78, 19, -24, -124, -41, -89, -97, 15, 50, 76, 85, 29, 4, 54,
        82, -36, 3, -13, -7, 78, 66, -23, 61, 97, -17, 124, -74, -77, -109, 80,
    )

    private val VPR_KEY = byteArrayOf(
        37, -33, -24, -90, 117, 30, 117, 14, 47, -128, -13, 45, -72, -74, -29, 17,
        0,
    )


    private val ENCRYPTED_EXTS = arrayOf(
        ".kgm", ".kgma", ".kgms", ".kgg", ".kggs", ".vpr",
        ".mflac", ".mflac0", ".mgg", ".mgg0"
    )

    /**
     * 已知**无法解密**的后缀：.mgg / .mgg0
     *
     * 这两个是酷狗下载过程中的分片容器，不是成品音频（真机抓包确认：
     * down_c/default 下 xxx_2_hash_N.mgg 会随下载进度增长/合并）。
     * 强行解密只会必然失败并刷屏，直接跳过。
     */
    private val UNSUPPORTED_EXTS = arrayOf(".mgg", ".mgg0")

    private fun isUnsupported(name: String): Boolean {
        val n = name.lowercase()
        return UNSUPPORTED_EXTS.any { n.endsWith(it) }
    }

    fun isEncrypted(name: String): Boolean {
        val lower = name.lowercase()
        for (ext in ENCRYPTED_EXTS) if (lower.endsWith(ext)) return true
        for (ext in ENCRYPTED_EXTS) if (lower.contains(ext + ".")) return true
        return false
    }

    private fun isVpr(name: String) = name.lowercase().contains(".vpr")

    fun decrypt(src: File, dst: File): String? {
        val vpr = isVpr(src.name)
        RandomAccessFile(src, "r").use { input ->
            val header = ByteArray(HEADER_PROBE)
            if (input.read(header) < HEADER_PROBE) return null
            val fileKey = ByteArray(KEY_LEN)
            System.arraycopy(header, 0x1C, fileKey, 0, 16)
            fileKey[16] = 0
            val headerLen = le32(header, 0x10)
            if (headerLen <= 0 || headerLen >= src.length()) return null
            input.seek(headerLen.toLong())
            dst.parentFile?.mkdirs()
            dst.outputStream().buffered().use { output ->
                val buf = ByteArray(BLOCK)
                var offset = 0L
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    decode(buf, read, fileKey, offset, vpr)
                    output.write(buf, 0, read)
                    offset += read
                }
            }
        }
        return detectFormat(dst)
    }

    private fun decode(buf: ByteArray, len: Int, fileKey: ByteArray, offset: Long, vpr: Boolean) {
        for (i in 0 until len) {
            val o = offset + i
            val mask = (TABLE_V2[(o % TABLE_SIZE).toInt()].toInt() and 0xFF) xor maskV1(o shr 4)
            var x = mask xor (buf[i].toInt() and 0xFF) xor
                (fileKey[(o % KEY_LEN).toInt()].toInt() and 0xFF)
            x = x xor ((x and 0x0F) shl 4)
            if (vpr) x = x xor (VPR_KEY[(o % KEY_LEN).toInt()].toInt() and 0xFF)
            buf[i] = x.toByte()
        }
    }

    private fun maskV1(offset: Long): Int {
        var o = offset
        var v = 0
        while (o >= 0x11L) {
            v = v xor (TABLE1[(o % TABLE_SIZE).toInt()].toInt() and 0xFF)
            o = o shr 4
            v = v xor (TABLE2[(o % TABLE_SIZE).toInt()].toInt() and 0xFF)
            o = o shr 4
        }
        return v
    }

    private fun le32(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    fun detectFormat(f: File): String? {
        val head = ByteArray(8)
        val n = try {
            f.inputStream().use { it.read(head) }
        } catch (_: Throwable) {
            return null
        }
        if (n < 4) return null
        if (head[0] == 'f'.code.toByte() && head[1] == 'L'.code.toByte() &&
            head[2] == 'a'.code.toByte() && head[3] == 'C'.code.toByte()
        ) return "flac"
        if (head[0] == 'I'.code.toByte() && head[1] == 'D'.code.toByte() &&
            head[2] == '3'.code.toByte()
        ) return "mp3"
        val b0 = head[0].toInt() and 0xFF
        val b1 = head[1].toInt() and 0xFF
        if (b0 == 0xFF && (b1 and 0xE0) == 0xE0) return "mp3"
        if (head[0] == 'R'.code.toByte() && head[1] == 'I'.code.toByte() &&
            head[2] == 'F'.code.toByte() && head[3] == 'F'.code.toByte()
        ) return "wav"
        if (head[0] == 'O'.code.toByte() && head[1] == 'g'.code.toByte() &&
            head[2] == 'g'.code.toByte() && head[3] == 'S'.code.toByte()
        ) return "ogg"
        if (n >= 8 && head[4] == 'f'.code.toByte() && head[5] == 't'.code.toByte() &&
            head[6] == 'y'.code.toByte() && head[7] == 'p'.code.toByte()
        ) return "m4a"
        return null
    }

    /**
     * 把 `xxx.kgm.flac` / `xxx.kgm` 这类名字改成纯音频后缀，
     * 免得播放器按 .kgm 拒绝加载（内容已经是明文的话后缀就是最后一道障碍）。
     */
    private fun renameToPlainExt(file: File, fmt: String) {
        val name = file.name
        val lower = name.lowercase()
        if (lower.endsWith(".$fmt")) {
            // 已经是目标后缀，但仍可能带 .kgm 等中缀
            var b = name
            var stripped = false
            for (ext in ENCRYPTED_EXTS) {
                val token = ext + "."
                val i = b.lowercase().lastIndexOf(token)
                if (i > 0) { b = b.substring(0, i); stripped = true; break }
            }
            if (!stripped) return
            val t = File(file.parentFile, b + "." + fmt)
            if (!t.exists()) {
                try { if (file.renameTo(t)) ModuleLog.log("已重命名为 " + t.name) } catch (_: Throwable) {}
            }
            return
        }
        var base = name
        for (ext in ENCRYPTED_EXTS) {
            if (base.lowercase().endsWith(ext)) {
                base = base.substring(0, base.length - ext.length)
                break
            }
        }
        // 再去掉可能残留的 .kgm / .kgma 中缀
        for (ext in ENCRYPTED_EXTS) {
            val idx = base.lowercase().lastIndexOf(ext + ".")
            if (idx > 0) {
                base = base.substring(0, idx)
                break
            }
        }
        // 处理 xxx.kgg.flac / xxx.kgm.flac 这类双后缀
        for (ext in ENCRYPTED_EXTS) {
            val token = ext + "."
            val i = base.lowercase().lastIndexOf(token)
            if (i > 0) base = base.substring(0, i)
        }
        base = base.trimEnd('.')
        val target = File(file.parentFile, base + "." + fmt)
        if (target.exists()) return
        try {
            if (file.renameTo(target)) {
                ModuleLog.log("已重命名为 " + target.name)
            }
        } catch (_: Throwable) {
        }
    }

    fun decryptInPlace(file: File): File? {
        // ⚠️ 关键预检：酷狗的下载文件常是「已是明文的 FLAC/MP3，只是文件名还叫 .kgm.flac」。
        // 这种文件再去解密只会把好好的音频打烂，必须先按魔数判断真实内容。
        val existing = detectFormat(file)
        if (existing != null) {
            ModuleLog.log("已是明文音频，跳过解密: " + file.name + " (" + existing + ")")
            renameToPlainExt(file, existing)
            return null
        }

        if (isUnsupported(file.name)) {
            // 分片容器，不是成品音频，跳过避免无意义重试
            return null
        }

        val tmp = File(file.parentFile, file.name + ".kgmdec.tmp")
        return try {
            // KGG/mflac：优先用已抓到的 mggKey 走 QMC2 解密（纯 Kotlin）
            var fmt: String? = null
            try {
                // 用 read() 而非 readFully：小文件（如 down_c 里的 .mflac）不会 EOFException
                val head = ByteArray(0x400)
                var n = 0
                java.io.RandomAccessFile(file, "r").use { r ->
                    while (n < head.size) {
                        val k = r.read(head, n, head.size - n)
                        if (k < 0) break
                        n += k
                    }
                }
                val hash = if (n >= 0x50) KggDecryptor.audioHash(head) else null
                val ek = if (hash != null) MggKeyHook.keyFor(hash) else null
                if (ek != null) {
                    ModuleLog.log("解密入口: " + file.name + " hash=" + (hash ?: "?").take(16))
                    fmt = KggDecryptor.decrypt(file, tmp, ek)
                    ModuleLog.log("QMC2 解密结果: " + fmt)
                } else {
                    ModuleLog.log("QMC2 跳过(无 key): " + file.name + " hash=" + (hash?.take(16) ?: "none"))
                }
            } catch (t: Throwable) {
                ModuleLog.log("QMC2 解密异常: " + t.javaClass.simpleName + " " + t.message)
            }
            if (fmt == null) {
                // 回退到纯 Kotlin 的 KGM v3 掩码算法
                fmt = decrypt(file, tmp)
                ModuleLog.log("KGM v3 回退结果: " + fmt)
            }
            if (fmt == null) {
                tmp.delete()
                ModuleLog.log("KGM 解密失败(魔数不符): " + file.name)
                return null
            }
            if (!tmp.renameTo(file)) {
                file.outputStream().use { out -> tmp.inputStream().use { it.copyTo(out) } }
                tmp.delete()
            }
            ModuleLog.log("KGM 解密成功: " + file.name + " -> " + fmt)
            renameToPlainExt(file, fmt)
            return file
        } catch (t: Throwable) {
            tmp.delete()
            ModuleLog.log("KGM 解密异常: " + file.name + " " + t.message)
            null
        }
    }
}
