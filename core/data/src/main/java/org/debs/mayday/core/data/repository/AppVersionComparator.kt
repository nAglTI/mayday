package org.debs.mayday.core.data.repository

internal object AppVersionComparator {

    fun isNewer(latestVersionTag: String, currentVersionName: String): Boolean {
        val latestVersion = latestVersionTag.normalizeVersionTag()
        val currentVersion = currentVersionName.normalizeVersionTag()
        return latestVersion.isNotBlank() &&
            currentVersion.isNotBlank() &&
            latestVersion.compareSemanticVersion(currentVersion) > 0
    }

    private fun String.normalizeVersionTag(): String {
        return trim()
            .removePrefix("release-")
            .removePrefix("Release-")
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore('+')
            .trim()
    }

    private fun String.compareSemanticVersion(other: String): Int {
        val leftParts = semanticParts()
        val rightParts = other.semanticParts()
        val maxSize = maxOf(leftParts.size, rightParts.size, MIN_SEMVER_PARTS)
        for (index in 0 until maxSize) {
            val left = leftParts.getOrElse(index) { 0 }
            val right = rightParts.getOrElse(index) { 0 }
            if (left != right) {
                return left.compareTo(right)
            }
        }
        return 0
    }

    private fun String.semanticParts(): List<Int> {
        return split('.', '-', '_')
            .mapNotNull { part -> part.takeWhile(Char::isDigit).toIntOrNull() }
    }

    private const val MIN_SEMVER_PARTS = 3
}
