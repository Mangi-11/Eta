package io.github.mangi.eta.agent.model

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class CodexCredentialAtomicFileTest {
    @Test
    fun replacementOverwritesExistingTarget() {
        val directory = Files.createTempDirectory("eta-codex-atomic-test")
        try {
            val target = directory.resolve("credentials.bin")
            val temporary = directory.resolve("credentials.bin.tmp")
            Files.writeString(target, "old-ciphertext")
            Files.writeString(temporary, "new-ciphertext")

            CodexCredentialAtomicFile.replace(temporary.toFile(), target.toFile())

            assertEquals("new-ciphertext", Files.readString(target))
        } finally {
            Files.deleteIfExists(directory.resolve("credentials.bin.tmp"))
            Files.deleteIfExists(directory.resolve("credentials.bin"))
            Files.deleteIfExists(directory)
        }
    }
}
