package dev.muto.app.data

import dev.muto.app.data.db.TunnelDao
import dev.muto.app.data.db.TunnelEntity
import dev.muto.app.tunnel.TunnelController
import kotlinx.coroutines.flow.Flow

/** Stores imported WireGuard configurations. */
class TunnelRepository(private val dao: TunnelDao) {

    fun observeAll(): Flow<List<TunnelEntity>> = dao.observeAll()

    suspend fun byId(id: Long): TunnelEntity? = dao.byId(id)

    suspend fun mostRecent(): TunnelEntity? = dao.mostRecent()

    /**
     * Imports a config, rejecting anything WireGuard will not accept.
     *
     * Validation happens here rather than at connect time so a bad paste fails while the user is
     * still looking at the text they pasted, not later in an airport when they need it.
     */
    suspend fun import(rawName: String, configText: String): Result<TunnelEntity> {
        val config = TunnelController.parseConfig(configText)
            .getOrElse { return Result.failure(it) }

        if (config.peers.isEmpty()) {
            return Result.failure(IllegalArgumentException("That config has no [Peer] section, so there is nothing to connect to"))
        }
        if (config.peers.none { it.endpoint.isPresent }) {
            return Result.failure(IllegalArgumentException("No peer has an Endpoint, so there is no server address to dial"))
        }

        val name = rawName.trim().ifBlank { defaultNameFor(config.peers.first().endpoint.orElse(null)?.host) }
        if (dao.countWithName(name) > 0) {
            return Result.failure(IllegalStateException("A tunnel called \"$name\" already exists"))
        }

        val entity = TunnelEntity(name = name, config = configText.trim())
        return Result.success(entity.copy(id = dao.insert(entity)))
    }

    suspend fun markConnected(id: Long) = dao.markConnected(id, System.currentTimeMillis())

    suspend fun remove(id: Long) = dao.delete(id)

    /** Falls back to the server's hostname, which is more recognisable than "Tunnel 1". */
    private fun defaultNameFor(host: String?): String =
        host?.takeIf { it.isNotBlank() } ?: "Tunnel"
}
