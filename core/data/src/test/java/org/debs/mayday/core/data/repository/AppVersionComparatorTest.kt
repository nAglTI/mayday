package org.debs.mayday.core.data.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionComparatorTest {

    @Test
    fun detectsNewerSemanticVersion() {
        assertTrue(AppVersionComparator.isNewer("v2.10.0", "2.9.9"))
    }

    @Test
    fun treatsSameVersionAsNotNewer() {
        assertFalse(AppVersionComparator.isNewer("v2.1.0", "2.1.0"))
    }

    @Test
    fun treatsOlderVersionAsNotNewer() {
        assertFalse(AppVersionComparator.isNewer("v2.0.9", "2.1.0"))
    }

    @Test
    fun ignoresBuildMetadata() {
        assertFalse(AppVersionComparator.isNewer("v2.1.0+7", "2.1.0"))
    }
}
