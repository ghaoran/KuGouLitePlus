package com.bldf1.kugoulite.plus

import java.io.File
import java.io.RandomAccessFile

/**
 * KGG/mflac 解密（纯 Kotlin，QMC2 MapCipher）。
 *
 * 已实测验证（G.E.M.《多远都要在一起》）：
 *  文件头 0x48 是 audio_hash 的 ASCII 十六进制串
 *  音频从 header_len(0x10) 开始
 *  out[i] = body[i] ^ mask(i)  ；mask 由 Qmc2.decrypt 生成
 */
internal object KggDecryptor {

    private const val HEADER = 0x400

    /** 读 audio_hash（0x48 处是 ASCII hex 编码的 hash） */
    fun audioHash(h: ByteArray): String? {
        val len = le32(h, 0x44)
        if (len <= 0 || 0x48 + len > h.size) return null
        val ascii = String(h, 0x48, len, Charsets.US_ASCII).trimEnd('\u0000')
        if (ascii.length % 2 == 0 &&
            ascii.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
        ) {
            val out = StringBuilder(ascii.length / 2)
            var i = 0
            while (i < ascii.length) {
                out.append(ascii.substring(i, i + 2).toInt(16).toChar())
                i += 2
            }
            return out.toString()
        }
        return ascii
    }

    /**
     * 用 [ekeyB64] 解密 [src] 到 [dst]。
     * 成功返回音频格式，失败返回 null。
     */
    fun decrypt(src: File, dst: File, ekeyB64: String): String? {
        val key = Qmc2.deriveKey(ekeyB64) ?: run {
            ModuleLog.log("Kgg: ekey 派生失败")
            return null
        }
        ModuleLog.log("Kgg: 派生 key 长度=" + key.size)
        RandomAccessFile(src, "r").use { input ->
            val head = ByteArray(HEADER)
            if (input.read(head) < 0x50) return null
            val hlen = le32(head, 0x10)
            if (hlen <= 0 || hlen >= src.length()) return null
            input.seek(hlen.toLong())
            val body = ByteArray((src.length() - hlen).toInt())
            input.readFully(body)
            Qmc2.decrypt(body, key)
            dst.parentFile?.mkdirs()
            dst.outputStream().use { it.write(body) }
        }
        return KgmDecryptor.detectFormat(dst)
    }

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
