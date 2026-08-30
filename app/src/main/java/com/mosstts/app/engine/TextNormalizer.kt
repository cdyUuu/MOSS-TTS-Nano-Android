package com.mosstts.app.engine

/**
 * 中文文本归一化器：将数字、符号等转换为可读文本。
 * 简化版 WeTextProcessing，覆盖常见场景。
 */
object TextNormalizer {

    private val digits = arrayOf("零", "一", "二", "三", "四", "五", "六", "七", "八", "九")
    private val units = arrayOf("", "十", "百", "千")
    private val bigUnits = arrayOf("", "万", "亿", "万亿")

    private val sentenceEndPunctuation = setOf('.', '!', '?', '。', '！', '？', '；', ';')
    private val clausePunctuation = setOf(',', '，', '、', '；', ';', '：', ':')
    private val closingPunctuation = setOf('"', '\'', '”', '’', ')', ']', '}', '）', '】', '》', '」', '』')

    fun normalize(text: String): String {
        if (text.isBlank()) return text
        var result = text

        // 统一换行和空白
        result = result.replace("\r", " ").replace("\n", " ")
        while ("  " in result) result = result.replace("  ", " ")

        // 数字转换
        result = normalizeNumbers(result)

        // 英文缩写和常见符号
        result = normalizeSymbols(result)

        // 去除可能导致模型产生噪声的特殊字符
        result = sanitizeSpecialChars(result)

        return result.trim()
    }

    /**
     * 将长文本按 token 预算分块。
     */
    fun splitIntoChunks(
        text: String,
        tokenCounter: (String) -> Int,
        maxTokens: Int = 75,
    ): List<String> {
        val normalized = normalize(text)
        if (normalized.isBlank()) return emptyList()

        // 确保中文文本以句末标点结尾
        val prepared = if (containsCJK(normalized)) {
            if (normalized.last() !in sentenceEndPunctuation) "$normalized。" else normalized
        } else {
            if (normalized.last().isLetterOrDigit()) "$normalized." else normalized
        }

        // 先按句末标点分
        val sentences = splitByPunctuation(prepared, sentenceEndPunctuation)
        val chunks = mutableListOf<String>()
        var current = ""
        var currentTokens = 0

        for (sentence in sentences) {
            val s = sentence.trim()
            if (s.isEmpty()) continue
            val sTokens = tokenCounter(s)

            if (sTokens <= maxTokens) {
                if (current.isEmpty()) {
                    current = s
                    currentTokens = sTokens
                } else if (currentTokens + sTokens <= maxTokens) {
                    current = joinParts(current, s)
                    currentTokens = tokenCounter(current)
                } else {
                    chunks.add(current.trim())
                    current = s
                    currentTokens = sTokens
                }
            } else {
                // 句子太长，按子句标点分
                if (current.isNotEmpty()) {
                    chunks.add(current.trim())
                    current = ""
                    currentTokens = 0
                }
                val clauses = splitByPunctuation(s, clausePunctuation)
                for (clause in clauses) {
                    val c = clause.trim()
                    if (c.isEmpty()) continue
                    val cTokens = tokenCounter(c)
                    if (cTokens <= maxTokens) {
                        if (current.isEmpty()) {
                            current = c
                            currentTokens = cTokens
                        } else if (currentTokens + cTokens <= maxTokens) {
                            current = joinParts(current, c)
                            currentTokens = tokenCounter(current)
                        } else {
                            chunks.add(current.trim())
                            current = c
                            currentTokens = cTokens
                        }
                    } else {
                        // 子句仍太长，按 token 预算硬切
                        if (current.isNotEmpty()) {
                            chunks.add(current.trim())
                            current = ""
                            currentTokens = 0
                        }
                        chunks.addAll(splitByTokenBudget(c, tokenCounter, maxTokens))
                    }
                }
            }
        }
        if (current.isNotEmpty()) chunks.add(current.trim())
        return chunks.ifEmpty { listOf(normalized) }
    }

    private fun splitByTokenBudget(
        text: String,
        tokenCounter: (String) -> Int,
        maxTokens: Int,
    ): List<String> {
        val result = mutableListOf<String>()
        var remaining = text.trim()
        val boundaryChars = clausePunctuation + sentenceEndPunctuation + ' '

        while (remaining.isNotEmpty()) {
            if (tokenCounter(remaining) <= maxTokens) {
                result.add(remaining)
                break
            }
            // 二分查找最大前缀
            var low = 1
            var high = remaining.length
            var best = 1
            while (low <= high) {
                val mid = (low + high) / 2
                val candidate = remaining.take(mid).trim()
                if (candidate.isNotEmpty() && tokenCounter(candidate) <= maxTokens) {
                    best = mid
                    low = mid + 1
                } else {
                    high = mid - 1
                }
            }
            // 在末尾附近找标点
            var cut = best
            val prefix = remaining.take(best)
            val scanMin = maxOf(0, prefix.length - 25)
            for (i in prefix.length - 1 downTo scanMin) {
                if (prefix[i] in boundaryChars) {
                    cut = i + 1
                    break
                }
            }
            val piece = remaining.take(cut).trim()
            if (piece.isNotEmpty()) result.add(piece)
            remaining = remaining.drop(cut).trim()
        }
        return result
    }

    private fun splitByPunctuation(text: String, punctuation: Set<Char>): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            current.append(c)
            if (c in punctuation) {
                // 吸收后续闭引号
                var j = i + 1
                while (j < text.length && text[j] in closingPunctuation) {
                    current.append(text[j])
                    j++
                }
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) result.add(sentence)
                current.clear()
                while (j < text.length && text[j].isWhitespace()) j++
                i = j
                continue
            }
            i++
        }
        val tail = current.toString().trim()
        if (tail.isNotEmpty()) result.add(tail)
        return result
    }

    private fun joinParts(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        return if (containsCJK(left) || containsCJK(right)) left + right else "$left $right"
    }

    fun containsCJK(text: String): Boolean {
        for (c in text) {
            if (c in '\u4e00'..'\u9fff' ||
                c in '\u3400'..'\u4dbf' ||
                c in '\u3040'..'\u30ff' ||
                c in '\uac00'..'\ud7af'
            ) return true
        }
        return false
    }

    private fun normalizeNumbers(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c.isDigit()) {
                // 收集连续数字
                val numStart = i
                while (i < text.length && (text[i].isDigit() || text[i] == ',' || text[i] == '.')) i++
                val numStr = text.substring(numStart, i).replace(",", "")

                // 判断是否为小数
                val readAsChinese = if (numStr.contains('.')) {
                    numberToChineseDecimal(numStr)
                } else {
                    // 大数字或年份
                    if (numStr.length == 4 && (numStr.startsWith("19") || numStr.startsWith("20"))) {
                        digitsToString(numStr) // 年份按位读
                    } else if (numStr.length > 8) {
                        digitsToString(numStr) // 超大数字按位读
                    } else {
                        numberToChinese(numStr.toLong())
                    }
                }
                sb.append(readAsChinese)
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    private fun digitsToString(s: String): String {
        return s.map { if (it.isDigit()) digits[it.digitToInt()] else it }.joinToString("")
    }

    private fun numberToChineseDecimal(s: String): String {
        val parts = s.split('.')
        val intPart = parts[0].toLongOrNull() ?: return s
        val intStr = numberToChinese(intPart)
        val decStr = if (parts.size > 1) {
            "点" + parts[1].map { if (it.isDigit()) digits[it.digitToInt()] else it }.joinToString("")
        } else ""
        return intStr + decStr
    }

    private fun numberToChinese(num: Long): String {
        if (num == 0L) return "零"
        if (num < 0) return "负" + numberToChinese(-num)

        var n = num
        var result = ""
        var unitIndex = 0

        while (n > 0) {
            val section = (n % 10000).toInt()
            if (section > 0) {
                val sectionStr = fourDigitToChinese(section)
                result = sectionStr + bigUnits[unitIndex] + result
            } else if (result.isNotEmpty() && !result.startsWith("零")) {
                result = "零$result"
            }
            n /= 10000
            unitIndex++
        }
        // 清理多余的零
        return result.replace("零+".toRegex(), "零").removeSuffix("零")
    }

    private fun fourDigitToChinese(num: Int): String {
        if (num == 0) return ""
        var n = num
        val result = StringBuilder()
        var zeroFlag = false

        for (i in 3 downTo 0) {
            val divisor = Math.pow(10.0, i.toDouble()).toInt()
            val digit = n / divisor
            n %= divisor
            if (digit > 0) {
                if (zeroFlag) {
                    result.append("零")
                    zeroFlag = false
                }
                result.append(digits[digit]).append(units[i])
            } else if (result.isNotEmpty()) {
                zeroFlag = true
            }
        }
        // 处理 "十" 的简写（10-19）
        var s = result.toString()
        if (s.startsWith("一十")) s = s.removePrefix("一")
        return s
    }

    private fun normalizeSymbols(text: String): String {
        var result = text
        // 常见符号替换
        result = result.replace("%", "百分之")
        result = result.replace("℃", "摄氏度")
        result = result.replace("°", "度")
        result = result.replace("&", "和")
        result = result.replace("+", "加")
        result = result.replace("-", "至")
        result = result.replace("=", "等于")
        // 移除控制字符
        result = result.filter { it.code >= 32 || it == '\n' }
        return result
    }

    private fun sanitizeSpecialChars(text: String): String {
        // 移除网络小说中常见的特殊符号，避免模型产生噪声
        val sb = StringBuilder()
        for (c in text) {
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c in sentenceEndPunctuation || c in clausePunctuation -> sb.append(c)
                c in closingPunctuation -> sb.append(c)
                c in setOf('"', '\'', '“', '‘', '(', '[', '{', '（', '【', '《', '「', '『') -> sb.append(c)
                c.isWhitespace() -> sb.append(' ')
                c in setOf('▁', '…', '—', '~', '～') -> sb.append(c)
                else -> {} // 跳过其他特殊字符
            }
        }
        return sb.toString()
    }
}
