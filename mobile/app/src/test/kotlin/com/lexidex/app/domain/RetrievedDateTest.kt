package com.lexidex.app.domain

import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * El valor que entra aca puede venir de una sincronizacion o de un respaldo escrito por otro
 * cliente, asi que lo que importa no es solo que formatee bien, sino que no se caiga con lo que no
 * puede formatear.
 */
class RetrievedDateTest {

    @Test
    fun `an instant becomes the day it was in the reader's timezone`() {
        assertEquals("19/08/2026", retrievedDate("2026-08-19T14:30:00Z", BUENOS_AIRES))
    }

    @Test
    fun `a late night copy belongs to the day the user lived, not to UTC`() {
        // 22:00 en Buenos Aires ya es el dia siguiente en UTC; la copia es del 19, no del 20.
        assertEquals("19/08/2026", retrievedDate("2026-08-20T01:00:00Z", BUENOS_AIRES))
    }

    @Test
    fun `a date without a time is accepted as it is`() {
        // El paquete canonico y los respaldos de otros clientes pueden traerla asi.
        assertEquals("19/08/2026", retrievedDate("2026-08-19", BUENOS_AIRES))
    }

    @Test
    fun `no date at all is not a date`() {
        assertNull(retrievedDate(null, BUENOS_AIRES))
        assertNull(retrievedDate("", BUENOS_AIRES))
        assertNull(retrievedDate("   ", BUENOS_AIRES))
    }

    @Test
    fun `something that is not a date returns null instead of breaking the card`() {
        assertNull(retrievedDate("ayer", BUENOS_AIRES))
        assertNull(retrievedDate("2026-13-45T00:00:00Z", BUENOS_AIRES))
        assertNull(retrievedDate("0", BUENOS_AIRES))
    }

    @Test
    fun `surrounding blanks do not hide a good date`() {
        assertEquals("19/08/2026", retrievedDate("  2026-08-19T14:30:00Z  ", BUENOS_AIRES))
    }

    private companion object {
        val BUENOS_AIRES: ZoneId = ZoneId.of("America/Argentina/Buenos_Aires")
    }
}
