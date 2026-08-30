package com.mosstts.app.engine

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.min

/**
 * 轻量级 SentencePiece Unigram 分词器实现。
 * 直接解析 tokenizer.model 的 protobuf 二进制格式，无需 protobuf 运行时。
 */
class SentencePieceTokenizer(modelFile: File) {

    private val pieces: List<Piece>
    private val pieceToId: Map<String, Int>
    private val bosId: Int
    private val eosId: Int
    private val padId: Int
    private val unkId: Int

    data class Piece(
        val text: String,
        val score: Float,
        val type: Int,
    )

    companion object {
        private const val TYPE_NORMAL = 1
        private const val TYPE_UNKNOWN = 2
        private const val TYPE_CONTROL = 3
        private const val TYPE_BYTE = 4
        private const val TYPE_UNUSED = 5

        // Protobuf wire types
        private const val WIRE_VARINT = 0
        private const val WIRE_FIXED64 = 1
        private const val WIRE_LENGTH_DELIMITED = 2
        private const val WIRE_FIXED32 = 5
    }

    init {
        val parsed = parseModelFile(modelFile.inputStream())
        pieces = parsed
        pieceToId = pieces.mapIndexed { index, piece -> piece.text to index }.toMap()

        // 查找特殊 token
        bosId = pieceToId["<s>"] ?: pieceToId["<BOS>"] ?: 1
        eosId = pieceToId["</s>"] ?: pieceToId["<EOS>"] ?: 2
        padId = pieceToId["<pad>"] ?: pieceToId["<PAD>"] ?: 0
        unkId = pieceToId["<unk>"] ?: pieceToId["<UNK>"] ?: 0

        require(pieces.isNotEmpty()) { "Failed to parse SentencePiece model: no pieces found" }
    }

    val vocabSize: Int get() = pieces.size

    fun encode(text: String): IntArray {
        if (text.isEmpty()) return intArrayOf()

        // 预处理：规范化空白
        val normalized = normalize(text)
        if (normalized.isEmpty()) return intArrayOf()

        // Unigram 分词：Viterbi 算法
        return viterbiEncode(normalized)
    }

    fun decode(ids: IntArray): String {
        val sb = StringBuilder()
        for (id in ids) {
            if (id in pieces.indices) {
                val piece = pieces[id]
                if (piece.type == TYPE_CONTROL) continue
                sb.append(piece.text)
            }
        }
        return sb.toString()
    }

    fun idToPiece(id: Int): String? = pieces.getOrNull(id)?.text

    fun pieceToId(piece: String): Int? = pieceToId[piece]

    private fun normalize(text: String): String {
        // SentencePiece 默认将空格替换为 ▁ (U+2581)
        var result = text.replace(" ", "▁")
        // 合并连续的 ▁
        while ("▁▁" in result) {
            result = result.replace("▁▁", "▁")
        }
        // 开头加 ▁
        if (result.isNotEmpty() && result[0] != '▁') {
            result = "▁$result"
        }
        return result
    }

    private fun viterbiEncode(text: String): IntArray {
        val n = text.length
        if (n == 0) return intArrayOf()

        // bestScore[i] = 前 i 个字符的最佳分数
        val bestScore = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val bestPrev = IntArray(n + 1) { -1 }
        val bestPieceId = IntArray(n + 1) { -1 }
        bestScore[0] = 0f

        // 预计算所有可能的匹配
        val maxPieceLen = pieces.maxOfOrNull { it.text.length } ?: 16

        for (i in 0 until n) {
            if (bestScore[i] == Float.NEGATIVE_INFINITY) continue
            val maxLen = min(maxPieceLen, n - i)
            for (len in 1..maxLen) {
                val substr = text.substring(i, i + len)
                val id = pieceToId[substr]
                if (id != null) {
                    val piece = pieces[id]
                    if (piece.type == TYPE_NORMAL || piece.type == TYPE_BYTE) {
                        val score = bestScore[i] + piece.score
                        if (score > bestScore[i + len]) {
                            bestScore[i + len] = score
                            bestPrev[i + len] = i
                            bestPieceId[i + len] = id
                        }
                    }
                }
            }
            // 如果没有匹配，使用 unk（单字符）
            if (bestPrev[i + 1] == -1) {
                val ch = text[i].toString()
                val unkMatch = pieceToId[ch]
                val id = unkMatch ?: unkId
                bestScore[i + 1] = bestScore[i] - 10f // unk 惩罚
                bestPrev[i + 1] = i
                bestPieceId[i + 1] = id
            }
        }

        // 回溯
        val result = ArrayList<Int>()
        var pos = n
        while (pos > 0) {
            val id = bestPieceId[pos]
            if (id >= 0) {
                result.add(0, id)
            }
            pos = bestPrev[pos]
            if (pos < 0) break
        }
        return result.toIntArray()
    }

    /**
     * 手动解析 SentencePiece model protobuf 文件。
     * ModelProto { repeated SentencePiece pieces = 1; ... }
     * SentencePiece { string piece = 1; float score = 2; int32 type = 3; }
     */
    private fun parseModelFile(input: InputStream): List<Piece> {
        val data = input.readBytes()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val pieces = ArrayList<Piece>()

        while (buf.hasRemaining()) {
            val tag = readVarint(buf).toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7

            if (fieldNumber == 1 && wireType == WIRE_LENGTH_DELIMITED) {
                // SentencePiece message
                val len = readVarint(buf).toInt()
                val end = buf.position() + len
                parseSentencePiece(buf, end)?.let { pieces.add(it) }
                buf.position(end)
            } else {
                // 跳过其他字段
                skipField(buf, wireType)
            }
        }
        return pieces
    }

    private fun parseSentencePiece(buf: ByteBuffer, end: Int): Piece? {
        var pieceText = ""
        var score = 0f
        var type = TYPE_NORMAL

        while (buf.position() < end) {
            val tag = readVarint(buf).toInt()
            val fieldNumber = tag ushr 3
            val wireType = tag and 0x7

            when (fieldNumber) {
                1 -> { // piece (string)
                    val len = readVarint(buf).toInt()
                    val bytes = ByteArray(len)
                    buf.get(bytes)
                    pieceText = String(bytes, Charsets.UTF_8)
                }
                2 -> { // score (float, fixed32)
                    score = buf.float
                }
                3 -> { // type (enum, varint)
                    type = readVarint(buf).toInt()
                }
                else -> skipField(buf, wireType)
            }
        }
        return Piece(pieceText, score, type)
    }

    private fun readVarint(buf: ByteBuffer): Long {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val b = buf.get().toInt() and 0xFF
            result = result or ((b.toLong() and 0x7FL) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }

    private fun skipField(buf: ByteBuffer, wireType: Int) {
        when (wireType) {
            WIRE_VARINT -> readVarint(buf)
            WIRE_FIXED64 -> buf.position(buf.position() + 8)
            WIRE_LENGTH_DELIMITED -> {
                val len = readVarint(buf).toInt()
                buf.position(buf.position() + len)
            }
            WIRE_FIXED32 -> buf.position(buf.position() + 4)
            else -> {} // ignore
        }
    }
}
