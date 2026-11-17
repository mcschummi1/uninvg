package dev.uninvg.memory

import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException

class SceneStore(private val directory: File) {
    data class Inventory(val scenes: List<SavedScene>, val unreadable: Int)

    @Synchronized fun list(): Inventory {
        if (!directory.exists()) return Inventory(emptyList(), 0)
        val files = directory.listFiles() ?: throw IOException("Scene folder could not be read")
        val records = files.filter { it.extension == "json" }
        var unreadable = (records.size - 20).coerceAtLeast(0)
        val scenes = records.take(20).mapNotNull { file ->
            try { load(file) } catch (error: Exception) {
                unreadable++
                Log.w("UniNVG.Memory", "operation=load_scene invalid_record type=${error.javaClass.simpleName}")
                null
            }
        }.sortedByDescending { it.createdMs }
        return Inventory(scenes, unreadable)
    }

    @Synchronized fun save(scene: SavedScene) {
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Scene folder could not be created")
        val file = File(directory, "${scene.id}.json")
        if (!file.exists() && (directory.listFiles()?.count { it.extension == "json" } ?: 0) >= 20) {
            throw IOException("Scene storage full; remove a saved scene first")
        }
        val json = JSONObject().put("version", 1).put("id", scene.id).put("name", scene.name).put("createdMs", scene.createdMs)
        scene.place?.let { fix -> json.put("place", JSONObject().put("latitude", fix.latitude).put("longitude", fix.longitude)
            .put("accuracy", fix.accuracyM).put("timeMs", fix.timeMs)) }
        json.put("patches", JSONArray().apply {
            scene.patches.forEach { patch -> put(JSONObject().put("forward", vector(patch.forward)).put("lines", JSONArray().apply {
                patch.lines.forEach { line -> put(JSONArray().put(vector(line.a)).put(vector(line.b))) }
            })) }
        })
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw IOException("Scene could not be saved", error)
        }
    }

    @Synchronized fun delete(id: String) {
        require(id.matches(Regex("[0-9a-f-]{36}")))
        val file = File(directory, "$id.json")
        if (file.exists() && !file.delete()) throw IOException("Scene could not be removed")
    }

    private fun load(file: File): SavedScene {
        val atomic = AtomicFile(file)
        val bytes = atomic.openRead().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            while (true) {
                val count = input.read(chunk)
                if (count == -1) break
                if (output.size() + count > 2_000_000) throw IOException("Scene exceeds storage limit")
                output.write(chunk, 0, count)
            }
            output.toByteArray()
        }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val id = json.getString("id")
        require(file.name == "$id.json")
        val place = (if (json.has("place") && !json.isNull("place")) json.getJSONObject("place") else null)?.let {
            PlaceFix(it.getDouble("latitude"), it.getDouble("longitude"), it.getDouble("accuracy").toFloat(), it.getLong("timeMs"))
        }
        val patches = json.getJSONArray("patches")
        require(patches.length() in 1..24)
        return SavedScene(id, json.getString("name"), json.getLong("createdMs"), place, List(patches.length()) { index ->
            val patch = patches.getJSONObject(index)
            val lines = patch.getJSONArray("lines")
            require(lines.length() in 8..240)
            ScenePatch(vector(patch.getJSONArray("forward")), List(lines.length()) { line ->
                val ends = lines.getJSONArray(line)
                require(ends.length() == 2)
                WorldLine(vector(ends.getJSONArray(0)), vector(ends.getJSONArray(1)))
            })
        })
    }

    private fun vector(value: Vec3) = JSONArray().put(value.x).put(value.y).put(value.z)
    private fun vector(value: JSONArray): Vec3 {
        require(value.length() == 3)
        return Vec3(value.getDouble(0), value.getDouble(1), value.getDouble(2))
    }
}
