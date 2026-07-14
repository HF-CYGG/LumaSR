package com.lumasr.data

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageCacheRepositorySourceTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun opensRestoredAbsoluteAndFileUriInputsWithoutContentResolver() {
        val expected = byteArrayOf(1, 2, 3, 4)
        val source = temporaryFolder.newFile("restored input.png").apply { writeBytes(expected) }
        var resolverCalls = 0

        val absolute = openImageSource(source.absolutePath) {
            resolverCalls += 1
            null
        }
        val fileUri = openImageSource(source.toURI().toString()) {
            resolverCalls += 1
            null
        }

        assertArrayEquals(expected, absolute!!.use { it.readBytes() })
        assertArrayEquals(expected, fileUri!!.use { it.readBytes() })
        assertEquals(0, resolverCalls)
    }

    @Test
    fun delegatesContentUrisToTheResolver() {
        val expected = byteArrayOf(9, 8, 7)
        var openedSource: String? = null

        val input = openImageSource("content://photos/item/7") { source ->
            openedSource = source
            ByteArrayInputStream(expected)
        }

        assertEquals("content://photos/item/7", openedSource)
        assertArrayEquals(expected, input!!.use { it.readBytes() })
    }
}
