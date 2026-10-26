package com.bldf1.kugoulite.plus

import android.util.Base64

/**
 * QMC2（酷狗 KGG/mflac 使用的密钥体系）解密。
 *
 * 两条已实测验证的事实：
 *  - fileuserkey / mggKey 是 base64 包装的 ekey，先经 QMC2 v1/v2 TEA 解码
 *  - 音频本体用 MapCipher：idx=(offset*offset+71214)%keyLen，
 *    r=((idx&7)+4)%8，mask=rol8(key[idx],r)，offset>0x7FFF 时取模 0x7FFF
 *
 * 全部纯 Kotlin，无需任何 native 库。
 */
internal object Qmc2 {

    private const val DELTA = 0x9E3779B9L
    private const val MASK = 0xFFFFFFFFL

    fun deriveKey(ekeyB64: String): ByteArray? {
        return try {
            var raw = Base64.decode(ekeyB64, Base64.NO_WRAP)
        if (raw.size < 16) return null
        val prefix = "QQMusic EncV2,Key:".toByteArray()
        if (raw.copyOfRange(0, minOf(prefix.size, raw.size)).contentEquals(prefix)) {
            val body = raw.copyOfRange(prefix.size, raw.size)
            val k1 = byteArrayOf(0x33, 0x38, 0x36, 0x5A, 0x4A, 0x59, 0x21, 0x40,
                0x23, 0x2A, 0x24, 0x25, 0x5E, 0x26, 0x29, 0x28)
            val k2 = byteArrayOf(0x2A, 0x2A, 0x23, 0x21, 0x28, 0x23, 0x24, 0x25,
                0x26, 0x5E, 0x61, 0x31, 0x63, 0x5A, 0x2C, 0x54)
            var b = tencentTeaDecrypt(body, k1)
            b = tencentTeaDecrypt(b, k2)
            raw = Base64.decode(b, Base64.NO_WRAP)
        }
        if (raw.size < 16) return null
        val sk = simpleMakeKey(106, 8)
        val tk = ByteArray(16)
        for (i in 0 until 8) {
            tk[i * 2] = sk[i].toByte()
            tk[i * 2 + 1] = raw[i]
        }
        raw.copyOfRange(0, 8) + tencentTeaDecrypt(raw.copyOfRange(8, raw.size), tk)
        } catch (_: Throwable) {
            null
        }
    }

    fun decrypt(audio: ByteArray, key: ByteArray) {
        val period = 0x7FFF
        val mask = ByteArray(period)
        val n = key.size
        for (o in 0 until period) {
            val idx = ((o.toLong() * o + 71214L) % n).toInt()
            val v = key[idx].toInt() and 0xFF
            val r = ((idx and 7) + 4) % 8
            mask[o] = (((v shl r) or (v ushr r)) and 0xFF).toByte()
        }
        for (i in audio.indices) {
            audio[i] = (audio[i].toInt() xor (mask[i % period].toInt() and 0xFF)).toByte()
        }
    }

    private fun simpleMakeKey(salt: Int, length: Int): IntArray =
        IntArray(length) { i ->
            (kotlin.math.abs(kotlin.math.tan(salt + i * 0.1)) * 100.0).toInt() and 0xFF
        }

    private fun teaDecryptBlock(block: ByteArray, key: ByteArray): ByteArray {
        var v0 = be32(block, 0)
        var v1 = be32(block, 4)
        val k = longArrayOf(be32(key, 0), be32(key, 4), be32(key, 8), be32(key, 12))
        var s = (DELTA * 16) and MASK
        repeat(16) {
            val t = ((((v0 shl 4) and MASK) + k[2]) and MASK) xor
                ((v0 + s) and MASK) xor (((v0 ushr 5) + k[3]) and MASK)
            v1 = (v1 - t) and MASK
            val t2 = ((((v1 shl 4) and MASK) + k[0]) and MASK) xor
                ((v1 + s) and MASK) xor (((v1 ushr 5) + k[1]) and MASK)
            v0 = (v0 - t2) and MASK
            s = (s - DELTA) and MASK
        }
        val out = ByteArray(8)
        putBe32(out, 0, v0)
        putBe32(out, 4, v1)
        return out
    }

    private fun tencentTeaDecrypt(inp: ByteArray, key: ByteArray): ByteArray {
        if (inp.size % 8 != 0 || inp.size < 16) return inp
        var dest = teaDecryptBlock(inp.copyOfRange(0, 8), key).toMutableList()
        val pad = dest[0].toInt() and 7
        val outLen = inp.size - 1 - pad - 2 - 7
        if (outLen <= 0) return inp
        val out = ByteArray(outLen)
        var ivPrev = ByteArray(8)
        var ivCur = inp.copyOfRange(0, 8)
        var inPos = 8
        var di = 1 + pad

        fun crypt() {
            ivPrev = ivCur
            ivCur = inp.copyOfRange(inPos, inPos + 8)
            for (i in 0 until 8) dest[i] = (dest[i].toInt() xor (ivCur[i].toInt() and 0xFF)).toByte()
            dest = teaDecryptBlock(dest.toByteArray(), key).toMutableList()
            inPos += 8
            di = 0
        }

        var i = 1
        while (i <= 2) {
            if (di < 8) { di++; i++ } else crypt()
        }
        var op = 0
        while (op < outLen) {
            if (di < 8) {
                out[op] = (dest[di].toInt() xor (ivPrev[di].toInt() and 0xFF)).toByte()
                di++; op++
            } else crypt()
        }
        return out
    }

    private fun be32(b: ByteArray, o: Int): Long =
        ((b[o].toInt() and 0xFF).toLong() shl 24) or
            ((b[o + 1].toInt() and 0xFF).toLong() shl 16) or
            ((b[o + 2].toInt() and 0xFF).toLong() shl 8) or
            (b[o + 3].toInt() and 0xFF).toLong()

    private fun putBe32(b: ByteArray, o: Int, v: Long) {
        b[o] = ((v ushr 24) and 0xFF).toByte()
        b[o + 1] = ((v ushr 16) and 0xFF).toByte()
        b[o + 2] = ((v ushr 8) and 0xFF).toByte()
        b[o + 3] = (v and 0xFF).toByte()
    }
}
