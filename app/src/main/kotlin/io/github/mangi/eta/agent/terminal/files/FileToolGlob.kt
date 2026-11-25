package io.github.mangi.eta.agent.terminal.files

/** 路径段使用动态规划，段内通配符使用有界回退；不把模型输入交给正则引擎。 */
internal class FileToolGlob(pattern: String) {
    private val components: List<String>

    init {
        if (pattern.isEmpty() || pattern.length > 512 || pattern.startsWith('/') || pattern.indexOf('\u0000') >= 0 ||
            pattern.split('/').any { it == ".." || it == "." || it.isEmpty() }
        ) {
            throw FileToolException("INVALID_GLOB", "模式必须是搜索目录内的相对路径，支持 *、? 和 ** 路径段")
        }
        components = pattern.split('/').fold(mutableListOf<String>()) { result, component ->
            if (component != "**" || result.lastOrNull() != "**") result += component
            result
        }
    }

    fun matches(path: String): Boolean {
        val segments = path.split('/')
        var suffixMatches = BooleanArray(segments.size + 1).also { it[segments.size] = true }
        for (component in components.asReversed()) {
            val current = BooleanArray(segments.size + 1)
            if (component == "**") {
                current[segments.size] = suffixMatches[segments.size]
                for (index in segments.lastIndex downTo 0) current[index] = suffixMatches[index] || current[index + 1]
            } else {
                for (index in segments.lastIndex downTo 0) {
                    current[index] = suffixMatches[index + 1] && matchesSegment(component, segments[index])
                }
            }
            suffixMatches = current
        }
        return suffixMatches[0]
    }

    private fun matchesSegment(pattern: String, value: String): Boolean {
        val patternPoints = pattern.codePoints().toArray()
        val valuePoints = value.codePoints().toArray()
        var patternIndex = 0
        var valueIndex = 0
        var lastStar = -1
        var starEnd = 0
        while (valueIndex < valuePoints.size) {
            when {
                patternIndex < patternPoints.size && (patternPoints[patternIndex] == '?'.code || patternPoints[patternIndex] == valuePoints[valueIndex]) -> {
                    patternIndex++
                    valueIndex++
                }
                patternIndex < patternPoints.size && patternPoints[patternIndex] == '*'.code -> {
                    lastStar = patternIndex++
                    starEnd = valueIndex
                }
                lastStar >= 0 -> {
                    patternIndex = lastStar + 1
                    valueIndex = ++starEnd
                }
                else -> return false
            }
        }
        while (patternIndex < patternPoints.size && patternPoints[patternIndex] == '*'.code) patternIndex++
        return patternIndex == patternPoints.size
    }
}
