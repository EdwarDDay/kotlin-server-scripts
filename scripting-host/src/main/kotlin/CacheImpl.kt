/*
 * Copyright 2024 Eduard Wolf
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
