package com.lumasr.data

import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GalleryRepositoryFileCopyTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun copiesCompleteImageIntoDestinationStream() {
        val source = temporaryFolder.newFile("output.png")
        val expected = byteArrayOf(0x01, 0x23, 0x45, 0x67)
        source.writeBytes(expected)
        val destination = ByteArrayOutputStream()

        copyImageContents(source) { destination }

        assertArrayEquals(expected, destination.toByteArray())
    }

    @Test
    fun rejectsMissingOrEmptySourcesAndUnavailableDestinations() {
        val missing = File(temporaryFolder.root, "missing.png")
        val empty = temporaryFolder.newFile("empty.png")
        val valid = temporaryFolder.newFile("valid.png").apply { writeBytes(byteArrayOf(1)) }

        assertThrows(IllegalArgumentException::class.java) {
            copyImageContents(missing) { ByteArrayOutputStream() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            copyImageContents(empty) { ByteArrayOutputStream() }
        }
        assertThrows(IllegalArgumentException::class.java) {
            copyImageContents(valid) { null }
        }
    }
}
