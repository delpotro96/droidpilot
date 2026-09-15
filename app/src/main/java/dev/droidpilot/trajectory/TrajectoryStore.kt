package dev.droidpilot.trajectory

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

interface TrajectoryStore {
    suspend fun findFor(goal: String): Trajectory?
    suspend fun save(trajectory: Trajectory)
    suspend fun recordOutcome(id: String, success: Boolean)
    suspend fun all(): List<Trajectory>
}

// Backed by a single JSON file in app storage. The whole set is small enough
// that a database would only add ceremony
class FileTrajectoryStore(private val file: File) : TrajectoryStore {

    private val mutex = Mutex()
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun findFor(goal: String): Trajectory? = mutex.withLock {
        val normalized = normalize(goal)
        read()
            .filter { it.isTrustworthy && normalize(it.goal) == normalized }
            .maxByOrNull { it.successCount }
    }

    override suspend fun save(trajectory: Trajectory) = mutex.withLock {
        val existing = read().filterNot { normalize(it.goal) == normalize(trajectory.goal) }
        write(existing + trajectory)
    }

    override suspend fun recordOutcome(id: String, success: Boolean) = mutex.withLock {
        val updated = read().map {
            when {
                it.id != id -> it
                success -> it.copy(successCount = it.successCount + 1)
                else -> it.copy(failureCount = it.failureCount + 1)
            }
        }
        write(updated)
    }

    override suspend fun all(): List<Trajectory> = mutex.withLock { read() }

    private suspend fun read(): List<Trajectory> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        // A corrupted store should cost the recorded paths, not crash the agent
        runCatching { json.decodeFromString<List<Trajectory>>(file.readText()) }
            .getOrDefault(emptyList())
    }

    private suspend fun write(trajectories: List<Trajectory>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(trajectories))
    }

    private fun normalize(goal: String) = goal.trim().lowercase()
}
