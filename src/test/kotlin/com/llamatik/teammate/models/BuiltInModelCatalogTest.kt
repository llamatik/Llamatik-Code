package com.llamatik.teammate.models

import com.llamatik.teammate.model.ModelTier
import org.junit.Assert.*
import org.junit.Test

class BuiltInModelCatalogTest {

    // ── Catalog integrity ─────────────────────────────────────────────────────

    @Test
    fun `catalog is non-empty`() {
        assertTrue(BuiltInModelCatalog.models.isNotEmpty())
    }

    @Test
    fun `all model ids are unique`() {
        val ids = BuiltInModelCatalog.models.map { it.id }
        assertEquals("Duplicate model ids found", ids.size, ids.toSet().size)
    }

    @Test
    fun `exactly one model is marked as default`() {
        val defaults = BuiltInModelCatalog.models.filter { it.isDefault }
        assertEquals("Expected exactly one default model", 1, defaults.size)
    }

    @Test
    fun `all models have non-blank display names`() {
        BuiltInModelCatalog.models.forEach { m ->
            assertTrue("Model ${m.id} has blank displayName", m.displayName.isNotBlank())
        }
    }

    @Test
    fun `all models have positive disk size`() {
        BuiltInModelCatalog.models.forEach { m ->
            assertTrue("Model ${m.id} has non-positive diskSizeGb", m.diskSizeGb > 0.0)
        }
    }

    @Test
    fun `all models have non-blank quantization`() {
        BuiltInModelCatalog.models.forEach { m ->
            assertTrue("Model ${m.id} has blank quantization", m.quantization.isNotBlank())
        }
    }

    @Test
    fun `minRamGb does not exceed recommendedRamGb for any model`() {
        BuiltInModelCatalog.models.forEach { m ->
            assertTrue(
                "Model ${m.id}: minRamGb (${m.minRamGb}) > recommendedRamGb (${m.recommendedRamGb})",
                m.minRamGb <= m.recommendedRamGb,
            )
        }
    }

    // ── byTier ────────────────────────────────────────────────────────────────

    @Test
    fun `byTier returns only models of that tier`() {
        val tier1 = BuiltInModelCatalog.byTier(ModelTier.TIER_1)
        assertTrue(tier1.isNotEmpty())
        assertTrue(tier1.all { it.tier == ModelTier.TIER_1 })
    }

    @Test
    fun `all three tiers have at least one model`() {
        assertTrue("TIER_1 is empty", BuiltInModelCatalog.byTier(ModelTier.TIER_1).isNotEmpty())
        assertTrue("TIER_2 is empty", BuiltInModelCatalog.byTier(ModelTier.TIER_2).isNotEmpty())
        assertTrue("TIER_3 is empty", BuiltInModelCatalog.byTier(ModelTier.TIER_3).isNotEmpty())
    }

    // ── findById ──────────────────────────────────────────────────────────────

    @Test
    fun `findById returns correct model`() {
        val first = BuiltInModelCatalog.models.first()
        val found = BuiltInModelCatalog.findById(first.id)
        assertNotNull(found)
        assertEquals(first.id, found!!.id)
    }

    @Test
    fun `findById returns null for unknown id`() {
        assertNull(BuiltInModelCatalog.findById("does-not-exist-xyz"))
    }

    // ── recommended ───────────────────────────────────────────────────────────

    @Test
    fun `recommended returns the single default model`() {
        val rec = BuiltInModelCatalog.recommended()
        assertTrue(rec.isDefault)
    }
}
