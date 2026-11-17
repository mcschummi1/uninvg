package dev.uninvg.memory

import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import kotlin.math.cos
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SceneStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var store: SceneStore

    @Before fun setup() {
        directory = temp.newFolder("scenes")
        store = SceneStore(directory)
    }

    @Test fun listingMissingDirectoryReturnsEmptyInventoryWithoutCreatingIt() {
        val missing = File(temp.root, "missing")
        val inventory = SceneStore(missing).list()
        assertTrue(inventory.scenes.isEmpty())
        assertEquals(0, inventory.unreadable)
        assertFalse(missing.exists())
    }

    @Test fun regularFileCannotMasqueradeAsAReadableSceneDirectory() {
        val file = temp.newFile("not-a-directory").apply { writeText("sentinel") }
        assertThrows(IOException::class.java) { SceneStore(file).list() }
        assertThrows(IOException::class.java) { SceneStore(file).save(scene(1)) }
        assertEquals("sentinel", file.readText())
    }

    @Test fun localJsonRoundTripRetainsPreciseVectorsPatchesLocationAndEscapedName() {
        val location = PlaceFix(51.50123456789, -0.123456789, 37.25f, 1_700_000_000_123L)
        val original = scene(1, location, patchCount = 3, lineCount = 17).copy(name = "Hall \"east\" – Ω")
        store.save(original)
        val inventory = SceneStore(directory).list()
        assertEquals(listOf(original), inventory.scenes)
        assertEquals(0, inventory.unreadable)
        val file = record(directory, original.id)
        val json = JSONObject(file.readText(Charsets.UTF_8))
        assertEquals(1, json.getInt("version"))
        assertEquals(original.id, json.getString("id"))
        assertEquals(original.name, json.getString("name"))
        assertEquals(3, json.getJSONArray("patches").length())
        assertEquals(3, json.getJSONArray("patches").getJSONObject(0).getJSONArray("forward").length())
        assertEquals(setOf("${original.id}.json"), fileNames(directory))
    }

    @Test fun optionalLocationRoundTripsAbsentAndReplacementDoesNotRetainOldFix() {
        val original = scene(1, PlaceFix(0.0, 0.0, 0f, 1L))
        store.save(original)
        val withoutLocation = original.copy(name = "Without location", place = null)
        store.save(withoutLocation)
        assertEquals(listOf(withoutLocation), SceneStore(directory).list().scenes)
        assertFalse(JSONObject(record(directory, original.id).readText()).has("place"))
        assertEquals(setOf("${original.id}.json"), fileNames(directory))
    }

    @Test fun explicitJsonNullLocationAlsoMeansNoLocation() {
        val original = scene(1)
        store.save(original)
        val file = record(directory, original.id)
        val json = JSONObject(file.readText()).put("place", JSONObject.NULL)
        file.writeText(json.toString(), Charsets.UTF_8)
        val inventory = store.list()
        assertEquals(listOf(original), inventory.scenes)
        assertEquals(0, inventory.unreadable)
    }

    @Test fun maximum24PatchesAnd240LinesPerPatchRemainReadableAfterSave() {
        val original = scene(1, patchCount = 24, lineCount = 240)
        store.save(original)
        assertTrue(record(directory, original.id).length() <= 2_000_000)
        val inventory = SceneStore(directory).list()
        assertEquals(0, inventory.unreadable)
        assertEquals(listOf(original), inventory.scenes)
        assertEquals(24, inventory.scenes.single().patches.size)
        assertTrue(inventory.scenes.single().patches.all { it.lines.size == 240 })
    }

    @Test fun interruptedAtomicWriteRestoresThePreviousCompleteScene() {
        val original = scene(1, patchCount = 2)
        store.save(original)
        val file = record(directory, original.id)
        AtomicFile(file).startWrite().use { it.write("{\"version\":1,".toByteArray(Charsets.UTF_8)) }
        assertTrue(File(directory, "${original.id}.json.bak").exists())
        val inventory = SceneStore(directory).list()
        assertEquals(listOf(original), inventory.scenes)
        assertEquals(0, inventory.unreadable)
        assertEquals(setOf("${original.id}.json"), fileNames(directory))
        assertEquals(original.id, JSONObject(file.readText()).getString("id"))
    }

    @Test fun malformedJsonIsCountedAndNeverRecreatedOrDeletedByListing() {
        val records = listOf("empty" to "", "truncated" to "{\"version\":1,", "array" to "[]", "text" to "not json")
        records.forEach { (label, contents) ->
            val folder = temp.newFolder(label)
            val healthy = scene(1)
            SceneStore(folder).save(healthy)
            val bad = record(folder, id(2)).apply { writeText(contents, Charsets.UTF_8) }
            assertUnreadableAndPreserved(folder, bad, healthy)
        }
    }

    @Test fun unsupportedOrMissingVersionIsCountedWithoutRewritingTheRecord() {
        for (version in listOf(-1, 0, 2, Int.MAX_VALUE)) {
            corruptRecord("version-$version") { it.put("version", version) }
        }
        corruptRecord("missing-version") { it.remove("version") }
    }

    @Test fun jsonIdMustMatchItsLocalFileName() {
        corruptRecord("mismatched-id") { it.put("id", id(3)) }
        corruptRecord("traversal-id") { it.put("id", "../outside") }
    }

    @Test fun exactlyTwoMillionBytesIsReadableButOneExtraByteIsUnreadable() {
        val healthy = scene(1)
        val padded = scene(2)
        store.save(healthy)
        store.save(padded)
        val file = record(directory, padded.id)
        val content = file.readBytes()
        val bytes = ByteArray(2_000_000) { 32.toByte() }
        content.copyInto(bytes)
        file.writeBytes(bytes)
        val atLimit = store.list()
        assertEquals(setOf(healthy, padded), atLimit.scenes.toSet())
        assertEquals(0, atLimit.unreadable)
        file.appendBytes(byteArrayOf(32))
        assertEquals(2_000_001L, file.length())
        assertUnreadableAndPreserved(directory, file, healthy)
    }

    @Test fun invalidPatchAndLineCountsAreUnreadableNotTruncatedToAllowedBounds() {
        val edits = listOf(
            mutation("no-patches") { it.put("patches", JSONArray()) },
            mutation("too-many-patches") { it.put("patches", repeated(it.getJSONArray("patches").getJSONObject(0), 25)) },
            mutation("seven-lines") { firstPatch(it).put("lines", repeated(firstLine(it), 7)) },
            mutation("241-lines") { firstPatch(it).put("lines", repeated(firstLine(it), 241)) }
        )
        edits.forEach { (label, edit) -> corruptRecord(label, edit) }
    }

    @Test fun malformedVectorShapesAndNonUnitDirectionsAreUnreadable() {
        val edits = listOf(
            mutation("short-forward") { firstPatch(it).put("forward", JSONArray().put(0).put(1)) },
            mutation("long-forward") { firstPatch(it).put("forward", JSONArray().put(0).put(0).put(1).put(0)) },
            mutation("zero-forward") { firstPatch(it).put("forward", JSONArray().put(0).put(0).put(0)) },
            mutation("scaled-forward") { firstPatch(it).put("forward", JSONArray().put(0).put(0).put(2)) },
            mutation("one-endpoint") { firstPatch(it).getJSONArray("lines").put(0, JSONArray().put(firstLine(it).getJSONArray(0))) },
            mutation("three-endpoints") { firstLine(it).put(firstLine(it).getJSONArray(0)) },
            mutation("short-endpoint") { firstLine(it).put(0, JSONArray().put(0).put(1)) },
            mutation("long-endpoint") { firstLine(it).put(1, JSONArray().put(0).put(0).put(1).put(0)) },
            mutation("zero-endpoint") { firstLine(it).put(0, JSONArray().put(0).put(0).put(0)) },
            mutation("scaled-endpoint") { firstLine(it).put(1, JSONArray().put(0).put(0).put(2)) },
            mutation("nan-component") { firstLine(it).getJSONArray(0).put(0, "NaN") },
            mutation("infinite-component") { firstPatch(it).getJSONArray("forward").put(2, "Infinity") }
        )
        edits.forEach { (label, edit) -> corruptRecord(label, edit) }
    }

    @Test fun invalidMetadataOrLocationValuesRemainVisibleAsUnreadable() {
        val edits = listOf(
            mutation("blank-name") { it.put("name", "  ") },
            mutation("long-name") { it.put("name", "x".repeat(65)) },
            mutation("zero-created") { it.put("createdMs", 0) },
            mutation("negative-created") { it.put("createdMs", -1) },
            mutation("latitude-outside") { it.getJSONObject("place").put("latitude", 90.001) },
            mutation("longitude-outside") { it.getJSONObject("place").put("longitude", -180.001) },
            mutation("negative-accuracy") { it.getJSONObject("place").put("accuracy", -0.1) },
            mutation("nan-accuracy") { it.getJSONObject("place").put("accuracy", "NaN") },
            mutation("overflow-accuracy") { it.getJSONObject("place").put("accuracy", Double.MAX_VALUE) },
            mutation("missing-place-time") { it.getJSONObject("place").remove("timeMs") },
            mutation("zero-place-time") { it.getJSONObject("place").put("timeMs", 0) }
        )
        edits.forEach { (label, edit) -> corruptRecord(label, edit) }
    }

    @Test fun wrongTypeLocationIsUnreadableRatherThanQuietlyDiscarded() {
        val wrongTypes = listOf<Any>("invalid location", JSONArray().put(1), true, 42)
        wrongTypes.forEachIndexed { index, value -> corruptRecord("place-type-$index") { it.put("place", value) } }
    }

    @Test fun twentyScenesAreAllowedButNewTwentyFirstSceneCannotOverwriteAnything() {
        val originals = (1..20).map { scene(it) }
        originals.forEach(store::save)
        assertEquals(originals.sortedByDescending { it.createdMs }, store.list().scenes)
        val before = directory.listFiles()!!.associate { it.name to it.readBytes() }
        assertThrows(IOException::class.java) { store.save(scene(21)) }
        assertEquals(20, store.list().scenes.size)
        assertEquals(0, store.list().unreadable)
        assertFalse(record(directory, id(21)).exists())
        assertEquals(before.keys, fileNames(directory))
        before.forEach { (name, bytes) -> assertArrayEquals(bytes, File(directory, name).readBytes()) }
    }

    @Test fun sameIdReplacementAtCapacityChangesOnlyThatSceneAndResortsInventory() {
        val originals = (1..20).map { scene(it) }
        originals.forEach(store::save)
        val replacement = scene(8, patchCount = 2, lineCount = 16).copy(name = "Replacement", createdMs = 1_800_000_000_000L)
        store.save(replacement)
        val expected = originals.map { if (it.id == replacement.id) replacement else it }.sortedByDescending { it.createdMs }
        val inventory = SceneStore(directory).list()
        assertEquals(expected, inventory.scenes)
        assertEquals(20, inventory.scenes.size)
        assertEquals(0, inventory.unreadable)
        assertEquals(replacement, inventory.scenes.first())
        assertEquals(originals.map { "${it.id}.json" }.toSet(), fileNames(directory))
    }

    @Test fun unreadableRecordsOccupySlotsUntilExplicitDeletionNotSilentRecreation() {
        for (number in 1..20) record(directory, id(number)).writeText("broken-$number")
        assertEquals(20, store.list().unreadable)
        assertTrue(store.list().scenes.isEmpty())
        assertThrows(IOException::class.java) { store.save(scene(21)) }
        for (number in 1..20) assertEquals("broken-$number", record(directory, id(number)).readText())
        store.delete(id(1))
        val original = scene(21)
        store.save(original)
        val inventory = store.list()
        assertEquals(listOf(original), inventory.scenes)
        assertEquals(19, inventory.unreadable)
        assertEquals(20, fileNames(directory).size)
    }

    @Test fun everyUnreadableJsonIsCountedEvenInAnExternallyOverfilledFolder() {
        for (number in 1..21) record(directory, id(number)).writeText("broken-$number")
        val inventory = store.list()
        assertTrue(inventory.scenes.isEmpty())
        assertEquals("The scene bound must not hide unreadable records", 21, inventory.unreadable)
        for (number in 1..21) assertEquals("broken-$number", record(directory, id(number)).readText())
    }

    @Test fun unrelatedNonJsonFilesAreNotInventedAsScenesOrCountedAsCorruption() {
        val original = scene(1)
        store.save(original)
        File(directory, "notes.tmp").writeText("unrelated")
        File(directory, "pending.new").writeText("incomplete unrelated write")
        val inventory = store.list()
        assertEquals(listOf(original), inventory.scenes)
        assertEquals(0, inventory.unreadable)
        assertEquals("unrelated", File(directory, "notes.tmp").readText())
    }

    @Test fun deletionRejectsTraversalAbsolutePathsAndInvalidIdsWithoutTouchingFiles() {
        val original = scene(1)
        store.save(original)
        val outside = temp.newFile("outside.json").apply { writeText("outside sentinel") }
        val before = record(directory, original.id).readBytes()
        val invalidIds = listOf("../outside", "..\\outside", "../" + "a".repeat(33),
            outside.absolutePath.removeSuffix(".json"), "${original.id}/../outside", "${original.id}.json", "", "G".repeat(36))
        invalidIds.forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { store.delete(invalid) }
            assertEquals("outside sentinel", outside.readText())
            assertArrayEquals(before, record(directory, original.id).readBytes())
        }
        assertEquals(listOf(original), store.list().scenes)
    }

    @Test fun validDeletionIsIdempotentAndDoesNotRemoveAnotherScene() {
        val first = scene(1)
        val second = scene(2)
        store.save(first)
        store.save(second)
        val survivor = record(directory, second.id).readBytes()
        store.delete(first.id)
        store.delete(first.id)
        val inventory = SceneStore(directory).list()
        assertEquals(listOf(second), inventory.scenes)
        assertEquals(0, inventory.unreadable)
        assertFalse(record(directory, first.id).exists())
        assertArrayEquals(survivor, record(directory, second.id).readBytes())
    }

    private fun corruptRecord(label: String, edit: (JSONObject) -> Unit) {
        val folder = temp.newFolder(label)
        val healthy = scene(1)
        val damaged = scene(2, PlaceFix(51.5, -0.12, 10f, 1_700_000_000_000L))
        val local = SceneStore(folder)
        local.save(healthy)
        local.save(damaged)
        val file = record(folder, damaged.id)
        val json = JSONObject(file.readText(Charsets.UTF_8))
        edit(json)
        file.writeText(json.toString(), Charsets.UTF_8)
        assertUnreadableAndPreserved(folder, file, healthy)
    }

    private fun assertUnreadableAndPreserved(folder: File, damaged: File, healthy: SavedScene) {
        val bytes = damaged.readBytes()
        val names = fileNames(folder)
        repeat(2) {
            val inventory = SceneStore(folder).list()
            assertEquals("Unreadable record: ${folder.name}", 1, inventory.unreadable)
            assertEquals(listOf(healthy), inventory.scenes)
            assertTrue(damaged.exists())
            assertArrayEquals(bytes, damaged.readBytes())
            assertEquals(names, fileNames(folder))
        }
    }

    private fun scene(number: Int, place: PlaceFix? = null, patchCount: Int = 1, lineCount: Int = 8): SavedScene {
        val lens = ViewLens(0.8, 0.6, 4.0 / 3.0)
        val patches = List(patchCount) { patch ->
            val angle = Math.toRadians(patch * 3.0)
            val pose = ViewPose(Vec3(cos(angle), 0.0, -sin(angle)), Vec3(0.0, 1.0, 0.0), Vec3(sin(angle), 0.0, cos(angle)))
            val lines = List(lineCount) { line ->
                val x = 0.08 + line % 20 * 0.04
                val y = 0.08 + line / 20 * 0.06
                ImageLine(Point2(x, y), Point2(x + 0.025, y + 0.02))
            }
            SceneEdges.patch(lines, pose, lens)
        }
        return SavedScene(id(number), "Scene $number", 1_700_000_000_000L + number, place, patches)
    }

    private fun id(number: Int) = "00000000-0000-0000-0000-${number.toString().padStart(12, '0')}"
    private fun record(folder: File, id: String) = File(folder, "$id.json")
    private fun fileNames(folder: File) = folder.listFiles()!!.map { it.name }.toSet()
    private fun firstPatch(json: JSONObject) = json.getJSONArray("patches").getJSONObject(0)
    private fun firstLine(json: JSONObject) = firstPatch(json).getJSONArray("lines").getJSONArray(0)
    private fun repeated(value: Any, count: Int) = JSONArray().apply { repeat(count) { put(value) } }
    private fun mutation(label: String, edit: (JSONObject) -> Unit) = label to edit
}
