package dev.yuyuyuyuyu.updatecomposemultiplatform

import java.math.BigInteger

val updateTypes = listOf("major", "minor", "patch")
private val prereleaseWords =
    setOf("alpha", "beta", "rc", "cr", "m", "milestone", "dev", "eap", "ea", "preview", "pre", "snapshot")
private val wordOrder = listOf("dev", "", "rc", "snapshot", "final", "ga", "release", "sp")
private val versionParts = Regex("""\d+|[^\d.\-_+]+""")

class Version(
    private val text: String,
) : Comparable<Version> {
    private val parts: List<String> = versionParts.findAll(text).map { it.value }.toList()
    private val base: List<BigInteger> = parts.takeWhile(::isNumber).map(::BigInteger)
    private val flavor: List<String> = parts.filterNot { isNumber(it) || isPrerelease(it) }.map(String::lowercase)

    val isStable: Boolean = parts.none(::isPrerelease)

    fun newest(candidates: Collection<Version>): Version = (listOf(this) + candidates.filter(::accepts)).max()

    fun updateType(to: Version): String {
        val mine = levels()
        val theirs = to.levels()
        val changed = mine.zip(theirs).indexOfFirst { (one, other) -> one != other }
        val level =
            when {
                changed >= 0 -> changed
                isStable -> 2
                else -> theirs.indexOfLast { it != BigInteger.ZERO }.coerceAtLeast(0)
            }
        return updateTypes[level]
    }

    override fun compareTo(other: Version): Int {
        val shorter = minOf(parts.size, other.parts.size)
        val extra = parts.getOrNull(shorter) ?: other.parts.getOrNull(shorter)
        val lengthOrder =
            when {
                extra == null -> 0
                (parts.size > other.parts.size) == isNumber(extra) -> 1
                else -> -1
            }
        return parts
            .zip(other.parts)
            .map { (mine, theirs) -> compareParts(mine, theirs) }
            .firstOrNull { it != 0 }
            ?: lengthOrder
    }

    override fun equals(other: Any?): Boolean = other is Version && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = text

    private fun accepts(candidate: Version): Boolean =
        candidate.flavor == flavor && (candidate.isStable || (!isStable && candidate.base == base))

    private fun levels(): List<BigInteger> = (base + List(updateTypes.size) { BigInteger.ZERO }).take(updateTypes.size)
}

private fun compareParts(
    mine: String,
    theirs: String,
): Int =
    when {
        isNumber(mine) && isNumber(theirs) -> BigInteger(mine).compareTo(BigInteger(theirs))
        isNumber(mine) -> 1
        isNumber(theirs) -> -1
        else -> compareValuesBy(mine, theirs, ::wordRank, String::lowercase)
    }

private fun wordRank(word: String): Int = wordOrder.indexOf(word.lowercase()).let { if (it < 0) 1 else it }

private fun isNumber(part: String): Boolean = part.first().isDigit()

private fun isPrerelease(part: String): Boolean = !isNumber(part) && part.lowercase() in prereleaseWords
