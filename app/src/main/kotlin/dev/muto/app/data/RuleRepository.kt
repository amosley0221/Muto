package dev.muto.app.data

import dev.muto.app.data.db.RuleAction
import dev.muto.app.data.db.RuleDao
import dev.muto.app.data.db.RuleEntity
import dev.muto.core.filter.DomainNames
import kotlinx.coroutines.flow.Flow

/** Add, remove and list the rules the user wrote themselves. */
class RuleRepository(private val dao: RuleDao) {

    fun observeAll(): Flow<List<RuleEntity>> = dao.observeAll()

    fun observe(action: RuleAction): Flow<List<RuleEntity>> = dao.observeByAction(action)

    /**
     * Adds a rule, normalising what the user typed. Returns a failure with a message worth showing
     * rather than throwing, because bad input here is expected, not exceptional.
     */
    suspend fun add(rawDomain: String, action: RuleAction, note: String? = null): Result<String> {
        val domain = DomainNames.normalize(rawDomain)
            ?: return Result.failure(IllegalArgumentException("\"${rawDomain.trim()}\" is not a domain name"))

        if (dao.count(domain, action) > 0) {
            return Result.failure(IllegalStateException("$domain is already on that list"))
        }
        dao.insert(RuleEntity(domain = domain, action = action, note = note))
        return Result.success(domain)
    }

    /**
     * Moves a domain to the other list. Used by the "allow this" / "block this" action in the
     * query log, where the user wants one tap rather than an add followed by a delete.
     */
    suspend fun set(rawDomain: String, action: RuleAction, note: String? = null): Result<String> {
        val domain = DomainNames.normalize(rawDomain)
            ?: return Result.failure(IllegalArgumentException("\"${rawDomain.trim()}\" is not a domain name"))
        val opposite = if (action == RuleAction.ALLOW) RuleAction.BLOCK else RuleAction.ALLOW
        dao.delete(domain, opposite)
        if (dao.count(domain, action) == 0) {
            dao.insert(RuleEntity(domain = domain, action = action, note = note))
        }
        return Result.success(domain)
    }

    suspend fun remove(id: Long) = dao.delete(id)

    suspend fun remove(domain: String, action: RuleAction) = dao.delete(domain, action)
}
