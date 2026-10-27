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
    /**
     * 读 audio_hash。
     *
     * ⚠️ 真机修复（关键）：0x48 处存的就是**正确的 hash 字符串本身**
     * （形如 "878fc31e185c898ce6bd4762826cb2c5"，32 个可打印字符）。
     *
     * 旧实现误以为它是「hash 的 hex 编码」又解了一次 hex，
     * 结果得到 16 字节乱码 → 拿去查 key 永远查不到 →
     * 日志出现 `QMC2 跳过(无 key) hash=Ã\u001e\u0018\\æ½Gb²Å` 这种乱码。
     */
    fun audioHash(h: ByteArray): String? {
        val len = le32(h, 0x44)
        if (len <= 0 || 0x48 + len > h.size) return null
        val raw = String(h, 0x48, len, Charsets.US_ASCII).trimEnd('\u0000').trim()
        // 只接受 32 位十六进制（标准 audio_hash 形态），其余视为无效
        if (raw.length != 32) return null
        if (!raw.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }) return null
        return raw.lowercase()
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
