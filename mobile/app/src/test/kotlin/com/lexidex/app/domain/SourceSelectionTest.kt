package com.lexidex.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Consultar una fuente sale a internet y gasta datos, asi que lo que se prueba aca es sobre todo
 * lo que **no** puede pasar: que "todas" sea el default, y que el usuario se quede sin ninguna.
 */
class SourceSelectionTest {

    private val available = listOf("wikipedia", "cambridge", "otra")

    @Test
    fun `the default is one source, never all of them`() {
        val selection = SourceSelection.default(available)

        assertFalse(selection.isAll)
        assertEquals(listOf("wikipedia"), selection.resolve(available))
    }

    @Test
    fun `all means all, including sources registered later`() {
        // Se guarda como marcador y no como la lista de hoy: si no, agregar una fuente dejaria
        // afuera a quien habia pedido todas, sin avisarle.
        val stored = SourceSelection.ALL.toStoredValue()

        val restored = SourceSelection.fromStoredValue(stored, available + "nueva")

        assertTrue(restored.isAll)
        assertEquals(available + "nueva", restored.resolve(available + "nueva"))
    }

    @Test
    fun `turning the last one off leaves the first, not nothing`() {
        // Sin fuentes el buscador queda mudo sin decir por que.
        val only = SourceSelection.of(listOf("cambridge"))

        val emptied = only.with("cambridge", active = false, available = available)

        assertEquals(listOf("wikipedia"), emptied.resolve(available))
    }

    @Test
    fun `a group is the sources you ticked, in registration order`() {
        val selection = SourceSelection.of(listOf("otra", "wikipedia"))

        assertEquals(listOf("wikipedia", "otra"), selection.resolve(available))
        assertEquals(2, selection.count(available))
    }

    @Test
    fun `a source that no longer exists is ignored`() {
        val restored = SourceSelection.fromStoredValue("wikipedia,borrada", available)

        assertEquals(listOf("wikipedia"), restored.resolve(available))
    }

    @Test
    fun `if nothing stored survives, the default comes back`() {
        val restored = SourceSelection.fromStoredValue("borrada,otra-que-no-esta", available)

        assertEquals(listOf("wikipedia"), restored.resolve(available))
    }

    @Test
    fun `nothing stored at all is the default and not all`() {
        assertFalse(SourceSelection.fromStoredValue(null, available).isAll)
        assertFalse(SourceSelection.fromStoredValue("", available).isAll)
    }

    @Test
    fun `the stored form survives a round trip`() {
        val selection = SourceSelection.of(listOf("wikipedia", "otra"))

        val restored = SourceSelection.fromStoredValue(selection.toStoredValue(), available)

        assertEquals(selection.resolve(available), restored.resolve(available))
    }

    @Test
    fun `with no sources registered there is nothing to count`() {
        assertEquals(0, SourceSelection.default(emptyList()).count(emptyList()))
    }
}
