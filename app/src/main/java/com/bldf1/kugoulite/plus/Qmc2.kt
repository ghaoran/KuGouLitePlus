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

    /**
     * QMC2 MapCipher，逐字节精确还原酷狗 libjengine.so 的 StreamCencrypt。
     *
     * 反汇编依据（0x130b14 StreamDecrypt 的 MapCipher 分支）：
     *     and  w14, w12, #7        ; b = idx & 7
     *     mov  w11, #-4
     *     cmp  w14, #3
     *     cneg w15, w11, ls        ; if (b < 3) t = +4  else t = -4
     *     add  w14, w15, w14       ; s = t + b
     *     lsl / lsr / orr          ; 32 位移位（未做 &31，靠硬件按 32 取模）
     *
     * 也就是：
     *     b == 0,1,2  ->  s = b + 4      (4,5,6)
     *     b == 3      ->  s = 3 - 4 = -1 -> 32位下 = 31
     *     b == 4..7   ->  s = b - 4      (0,1,2,3)
     *
     * ⚠️ 旧实现写成 ((b + 4) % 8)，b==3 时得 7，而 native 是 31，
     *    导致约 1/8 的字节解错 —— 这是 MapCipher 一直解不出正确结果的原因之一。
     *
     * 已用真机 key 验证：纯 Kotlin 输出与 native 逐字节一致
     *   head = 664c61430000002210001000  (fLaC)
     */
    fun decrypt(audio: ByteArray, key: ByteArray) {
        val period = 0x7FFF
        val mask = ByteArray(period)
        val n = key.size
        for (o in 0 until period) {
            val idx = ((o.toLong() * o + 71214L) % n).toInt()
            val v = key[idx].toInt() and 0xFF
            val b = idx and 7
            // cneg 语义：b<3 -> +4，否则 -4；负数在 32 位移位时等效取低 5 位
            val s = (if (b < 3) b + 4 else b - 4) and 31
            mask[o] = (((v shl s) or (v ushr s)) and 0xFF).toByte()
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
