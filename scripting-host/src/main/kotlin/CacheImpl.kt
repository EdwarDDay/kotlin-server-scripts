package net.edwardday.serverscript.scripthost

import java.util.concurrent.atomic.AtomicReference
import net.edwardday.serverscript.scriptdefinition.script.Cache

class CacheImpl : Cache {
    private val map = AtomicReference<Map<Any, Any>>(emptyMap())

    override fun <T> getOrSet(key: Any?, factory: () -> T): T {
        val usedKey = key ?: NULL_VALUE
        val updatedMap = map.updateAndGet {
            val value = it[usedKey]
            if (value == null) {
                it + (usedKey to (factory() ?: NULL_VALUE))
            } else {
                it
            }
        }
        @Suppress("UNCHECKED_CAST")
        return updatedMap.getValue(usedKey).takeUnless { it == NULL_VALUE } as T
    }

    override fun <T> updateOrSet(key: Any?, factory: () -> T, merge: (oldValue: T) -> T): T {
        val usedKey = key ?: NULL_VALUE
        val updatedMap = map.updateAndGet { cachedMap ->
            val newValue = when (val value = cachedMap[usedKey]) {
                null -> factory()
                else -> @Suppress("UNCHECKED_CAST") merge(value.takeUnless { it == NULL_VALUE } as T)
            }
            cachedMap + (usedKey to (newValue ?: NULL_VALUE))
        }
        @Suppress("UNCHECKED_CAST")
        return updatedMap.getValue(usedKey).takeUnless { it == NULL_VALUE } as T
    }
}

private val NULL_VALUE = Any()
