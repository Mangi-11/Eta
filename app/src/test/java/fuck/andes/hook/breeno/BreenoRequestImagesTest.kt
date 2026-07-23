package fuck.andes.hook.breeno

import fuck.andes.agent.model.AgentModelClient

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class BreenoRequestImagesTest {
    @Test
    fun structuredTextIsParsedOnlyWhenResolved() {
        val snapshot = BreenoRequestImages.captureText(
            text = """{"image":{"url":"https://example.test/presigned"}}""",
            source = "cdm.data",
        )

        assertEquals(1, snapshot.inputCount)

        val images = resolveImages(snapshot)

        assertEquals(listOf("https://example.test/presigned"), images.map { it.reference })
    }

    @Test
    fun normalTextDoesNotCreateAnImageSnapshot() {
        val snapshot = BreenoRequestImages.captureText("帮我总结今天的日程", "cdm.data")

        assertTrue(snapshot.isEmpty)
    }

    @Test
    fun currentNlpDocFieldsAreCapturedWithoutResolvingInHookPath() {
        val payload = FakeDocPayload(
            url = "https://example.test/original",
            imagePreProcessResult = listOf(FakeImageResult("https://example.test/resized")),
        )

        val snapshot = BreenoRequestImages.capturePayload("Nlp", "Doc", payload)

        assertEquals(2, snapshot.inputCount)
        assertEquals(
            listOf("https://example.test/resized", "https://example.test/original"),
            resolveImages(snapshot).map { it.reference },
        )
    }

    @Test
    fun oversizedNlpDocImageListReturnsStructuredInputFailure() {
        val payload = FakeDocPayload(
            url = "https://example.test/original",
            imagePreProcessResult = List(9) { index ->
                FakeImageResult("https://example.test/image-$index.png")
            },
        )

        val resolution = BreenoRequestImages.resolve(
            context = null,
            snapshot = BreenoRequestImages.capturePayload("Nlp", "Doc", payload),
        )

        assertTrue(resolution is BreenoRequestImages.Resolution.Failure)
        assertEquals(
            BreenoRequestImages.FailureCode.IMAGE_INPUT_LIMIT_EXCEEDED,
            (resolution as BreenoRequestImages.Resolution.Failure).code,
        )
    }

    @Test
    fun unknownPayloadIsIgnoredWithoutCallingGenericRecursiveGetters() {
        val payload = FakeImagePayload()

        val snapshot = BreenoRequestImages.capturePayload("Vision", "ImageUpload", payload)

        assertTrue(snapshot.isEmpty)
        assertEquals(0, payload.imageGetterCalls)
        assertEquals(0, payload.genericGetterCalls)
    }

    @Test
    fun contentUriRemainsAnUnresolvedStringSnapshot() {
        val snapshot = BreenoRequestImages.captureText(
            text = "content://com.heytap.speechassist/image/42",
            source = "image.uri",
        )

        assertEquals(1, snapshot.inputCount)
    }

    @Test
    fun unreadableContentUriReturnsStructuredFailureInsteadOfDroppingTheImage() {
        val snapshot = BreenoRequestImages.captureText(
            text = "content://com.heytap.speechassist/image/42",
            source = "image.uri",
        )

        val resolution = BreenoRequestImages.resolve(context = null, snapshot = snapshot)

        assertTrue(resolution is BreenoRequestImages.Resolution.Failure)
        assertEquals(
            BreenoRequestImages.FailureCode.IMAGE_REFERENCE_UNREADABLE,
            (resolution as BreenoRequestImages.Resolution.Failure).code,
        )
    }

    @Test
    fun oneUnreadableImageFailsTheWholeMultiImageRequest() {
        val snapshot = BreenoRequestImages.merge(
            BreenoRequestImages.captureText(
                text = "https://example.test/valid.png",
                source = "image.url",
            ),
            BreenoRequestImages.captureText(
                text = "content://com.heytap.speechassist/image/missing",
                source = "image.uri",
            ),
        )

        val resolution = BreenoRequestImages.resolve(context = null, snapshot = snapshot)

        assertTrue(resolution is BreenoRequestImages.Resolution.Failure)
        assertEquals(
            BreenoRequestImages.FailureCode.IMAGE_REFERENCE_UNREADABLE,
            (resolution as BreenoRequestImages.Resolution.Failure).code,
        )
        assertEquals(2, resolution.imageCount)
    }

    @Test
    fun tooManyImagesReturnStructuredFailureInsteadOfDroppingTheTail() {
        val snapshot = BreenoRequestImages.merge(
            *Array(5) { index -> imageSnapshot("image-$index") },
        )

        val resolution = BreenoRequestImages.resolve(context = null, snapshot = snapshot)

        assertTrue(resolution is BreenoRequestImages.Resolution.Failure)
        assertEquals(
            BreenoRequestImages.FailureCode.IMAGE_COUNT_LIMIT_EXCEEDED,
            (resolution as BreenoRequestImages.Resolution.Failure).code,
        )
        assertEquals(5, resolution.imageCount)
    }

    @Test
    fun mergeInputLimitReturnsFailureEvenWhenTruncatedInputsAreDuplicates() {
        val duplicate = imageSnapshot("duplicate")
        val snapshot = BreenoRequestImages.merge(
            *Array(17) { duplicate },
        )

        val resolution = BreenoRequestImages.resolve(context = null, snapshot = snapshot)

        assertTrue(resolution is BreenoRequestImages.Resolution.Failure)
        assertEquals(
            BreenoRequestImages.FailureCode.IMAGE_INPUT_LIMIT_EXCEEDED,
            (resolution as BreenoRequestImages.Resolution.Failure).code,
        )
        assertEquals(17, resolution.imageCount)
    }

    @Test
    fun inlineImageIsNoLongerRejectedByBinderStringBudget() {
        val snapshot = BreenoRequestImages.captureText(
            text = "data:image/png;base64," + "A".repeat(300_000),
            source = "image.data",
        )

        val resolution = BreenoRequestImages.resolve(null, snapshot)

        assertTrue(resolution is BreenoRequestImages.Resolution.Success)
        assertEquals(1, (resolution as BreenoRequestImages.Resolution.Success).images.size)
    }

    @Test
    fun remoteImageUrlIsPreservedAcrossIpcBudgetValidation() {
        val snapshot = BreenoRequestImages.captureText(
            text = "https://example.test/image-without-extension",
            source = "image.url",
        )

        assertEquals(
            "https://example.test/image-without-extension",
            resolveImages(snapshot).single().reference,
        )
    }

    @Test
    fun snapshotCacheConsumesAllAliasesAtomicallyAndExpiresEntries() {
        var now = 1_000L
        val cache = BreenoRequestImages.SnapshotCache(
            maxEntries = 2,
            maxAliases = 4,
            maxEstimatedChars = 1_024,
            ttlMillis = 100,
            nowMillis = { now },
        )
        val snapshot = imageSnapshot("first")

        assertEquals(
            BreenoRequestImages.SnapshotCache.StoreResult.STORED,
            cache.store(listOf("record", "session"), snapshot),
        )
        assertSame(snapshot, cache.consume(listOf("session")))
        assertTrue(cache.consume(listOf("record")).isEmpty)
        assertEquals(0, cache.stats().aliases)

        cache.store(listOf("next"), imageSnapshot("next"))
        now += 100
        assertTrue(cache.consume(listOf("next")).isEmpty)
        assertEquals(0, cache.stats().entries)
    }

    @Test
    fun snapshotCacheEnforcesEntryAliasAndCharacterLimits() {
        val entryBounded = BreenoRequestImages.SnapshotCache(
            maxEntries = 2,
            maxAliases = 8,
            maxEstimatedChars = 10_000,
            ttlMillis = 1_000,
        )
        entryBounded.store(listOf("a"), imageSnapshot("a"))
        entryBounded.store(listOf("b"), imageSnapshot("b"))
        entryBounded.store(listOf("c"), imageSnapshot("c"))
        assertEquals(2, entryBounded.stats().entries)
        assertTrue(entryBounded.consume(listOf("a")).isEmpty)

        val aliasBounded = BreenoRequestImages.SnapshotCache(
            maxEntries = 2,
            maxAliases = 2,
            maxEstimatedChars = 10_000,
            ttlMillis = 1_000,
        )
        assertEquals(
            BreenoRequestImages.SnapshotCache.StoreResult.TOO_MANY_ALIASES,
            aliasBounded.store(listOf("a", "b", "c"), imageSnapshot("aliases")),
        )
        assertEquals(0, aliasBounded.stats().entries)

        val first = imageSnapshot("first")
        val second = imageSnapshot("second")
        val characterLimit = maxOf(first.estimatedChars, second.estimatedChars) + 1
        val characterBounded = BreenoRequestImages.SnapshotCache(
            maxEntries = 4,
            maxAliases = 4,
            maxEstimatedChars = characterLimit,
            ttlMillis = 1_000,
        )
        characterBounded.store(listOf("a"), first)
        characterBounded.store(listOf("b"), second)
        assertTrue(characterBounded.stats().estimatedChars <= characterLimit)
        assertTrue(characterBounded.consume(listOf("a")).isEmpty)

        val tooSmall = BreenoRequestImages.SnapshotCache(
            maxEntries = 1,
            maxAliases = 1,
            maxEstimatedChars = 1,
            ttlMillis = 1_000,
        )
        assertEquals(
            BreenoRequestImages.SnapshotCache.StoreResult.TOO_LARGE,
            tooSmall.store(listOf("a"), first),
        )
    }

    @Test
    fun oversizedSnapshotIsReplacedWithAConsumableFailureMarker() {
        val cache = BreenoRequestImages.SnapshotCache(
            maxEntries = 1,
            maxAliases = 2,
            maxEstimatedChars = 256,
            ttlMillis = 1_000,
        )
        val oversized = BreenoRequestImages.captureText(
            text = "https://example.test/" + "a".repeat(512) + ".png",
            source = "image.url",
        )

        assertEquals(
            BreenoRequestImages.SnapshotCache.StoreResult.STORED_FAILURE,
            cache.store(listOf("record"), oversized),
        )

        val resolution = BreenoRequestImages.resolve(null, cache.consume(listOf("record")))
        assertTrue(resolution is BreenoRequestImages.Resolution.Failure)
        assertEquals(
            BreenoRequestImages.FailureCode.IMAGE_REFERENCE_CACHE_LIMIT_EXCEEDED,
            (resolution as BreenoRequestImages.Resolution.Failure).code,
        )
    }

    private fun resolveImages(
        snapshot: BreenoRequestImages.Snapshot,
    ): List<AgentModelClient.ModelImage> {
        val resolution = BreenoRequestImages.resolve(null, snapshot)
        assertTrue(resolution is BreenoRequestImages.Resolution.Success)
        return (resolution as BreenoRequestImages.Resolution.Success).images
    }

    private fun imageSnapshot(suffix: String): BreenoRequestImages.Snapshot =
        BreenoRequestImages.captureText(
            text = "https://example.test/$suffix.png",
            source = "image.url",
        )

    private class FakeDocPayload(
        private val url: String,
        private val imagePreProcessResult: List<FakeImageResult>,
    ) {
        fun getType(): String = "image"

        fun getUrl(): String = url

        fun getImagePreProcessResult(): List<FakeImageResult> = imagePreProcessResult
    }

    private class FakeImageResult(private val url: String) {
        fun getUrl(): String = url
    }

    private class FakeImagePayload {
        var imageGetterCalls: Int = 0
            private set

        var genericGetterCalls: Int = 0
            private set

        fun getImageUrl(): String {
            imageGetterCalls++
            return "https://example.test/image.png"
        }

        fun getData(): Any {
            genericGetterCalls++
            return SensitiveValue
        }

        fun getContent(): Any {
            genericGetterCalls++
            return SensitiveValue
        }

        fun getPayload(): Any {
            genericGetterCalls++
            return SensitiveValue
        }
    }

    private data object SensitiveValue
}
