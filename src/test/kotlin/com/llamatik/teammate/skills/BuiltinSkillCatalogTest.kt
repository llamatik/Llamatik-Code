package com.llamatik.teammate.skills

import org.junit.Assert.*
import org.junit.Test

class BuiltinSkillCatalogTest {

    @Test
    fun `catalog is non-empty`() {
        assertTrue(BuiltinSkillCatalog.all.isNotEmpty())
    }

    @Test
    fun `all skill ids are unique`() {
        val ids = BuiltinSkillCatalog.all.map { it.id }
        assertEquals("Duplicate skill ids found", ids.size, ids.toSet().size)
    }

    @Test
    fun `all skills have non-blank display names`() {
        BuiltinSkillCatalog.all.forEach { s ->
            assertTrue("Skill ${s.id} has blank displayName", s.displayName.isNotBlank())
        }
    }

    @Test
    fun `all skills have non-blank prompts`() {
        BuiltinSkillCatalog.all.forEach { s ->
            assertTrue("Skill ${s.id} has blank prompt", s.prompt.isNotBlank())
        }
    }

    @Test
    fun `all skills have non-blank categories`() {
        BuiltinSkillCatalog.all.forEach { s ->
            assertTrue("Skill ${s.id} has blank category", s.category.isNotBlank())
        }
    }

    @Test
    fun `all skills have non-blank descriptions`() {
        BuiltinSkillCatalog.all.forEach { s ->
            assertTrue("Skill ${s.id} has blank description", s.description.isNotBlank())
        }
    }
}
