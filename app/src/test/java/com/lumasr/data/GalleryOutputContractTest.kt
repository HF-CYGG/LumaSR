package com.lumasr.data

import com.lumasr.domain.OutputFormat
import org.junit.Assert.assertThrows
import org.junit.Test

class GalleryOutputContractTest {
    @Test
    fun acceptsPngGalleryOutput() {
        OutputFormat.PNG.requireGalleryOutputSupported()
    }

    @Test
    fun rejectsFormatsThatAreNotImplemented() {
        assertThrows(IllegalArgumentException::class.java) {
            OutputFormat.JPEG.requireGalleryOutputSupported()
        }
        assertThrows(IllegalArgumentException::class.java) {
            OutputFormat.WEBP.requireGalleryOutputSupported()
        }
    }
}
