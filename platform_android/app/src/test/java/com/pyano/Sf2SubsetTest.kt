package com.pyano

import com.pyano.audio.Sf2Subset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Sf2SubsetTest {

    @get:Rule val tmp = TemporaryFolder()

    // --- A minimal independent SF2 writer/reader for building inputs and checking outputs ---

    private class Le {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun u32(v: Int) = apply { u16(v); u16(v shr 16) }
        fun name(s: String) = apply { out.write(ByteArray(20).also { s.toByteArray().copyInto(it) }) }
        fun bytes() = out.toByteArray()
    }

    private fun chunk(id: String, data: ByteArray) = Le().apply {
        out.write(id.toByteArray()); u32(data.size); out.write(data); if (data.size % 2 == 1) u8(0)
    }.bytes()

    private fun list(type: String, vararg c: ByteArray) = chunk("LIST", type.toByteArray() + c.fold(ByteArray(0)) { a, b -> a + b })

    private fun keyRange(lo: Int, hi: Int) = lo or (hi shl 8)

    /** Sample i is 10 points of value (i+1)*100 + point index, so copies are checkable. */
    private fun samplePoints(i: Int) = ShortArray(10) { ((i + 1) * 100 + it).toShort() }

    /**
     * Presets: "Piano" (bank 0 prog 0 -> inst 0 -> sample 4) and "Kit" (bank 128 prog 0 ->
     * inst 1 with a global zone and zones: key 31 -> sample 0 (mono), keys 33-34 -> sample 1
     * (left, linked to sample 2), key 60 -> sample 3).
     */
    private fun buildSource(file: File) {
        val smpl = Le()
        val shdr = Le()
        val types = intArrayOf(1, 4, 2, 1, 1)
        val links = intArrayOf(0, 2, 1, 0, 0)
        for (i in 0 until 5) {
            val start = smpl.out.size() / 2
            samplePoints(i).forEach { smpl.u16(it.toInt()) }
            repeat(46) { smpl.u16(0) }
            shdr.name("S$i").u32(start).u32(start + 10).u32(start + 2).u32(start + 8)
                .u32(44100).u8(60).u8(0).u16(links[i]).u16(types[i])
        }
        shdr.name("EOS").u32(0).u32(0).u32(0).u32(0).u32(0).u8(0).u8(0).u16(0).u16(0)

        // igen: inst 0 zone0 (sample 4); inst 1: global(pan), 31, 33-34, 60
        val igen = Le(); val ibag = Le()
        var g = 0
        fun izone(vararg gens: Pair<Int, Int>) { ibag.u16(g).u16(0); gens.forEach { igen.u16(it.first).u16(it.second) }; g += gens.size }
        izone(53 to 4)
        izone(17 to 100)                                   // global zone: pan
        izone(43 to keyRange(31, 31), 53 to 0)
        izone(43 to keyRange(33, 34), 53 to 1)
        izone(43 to keyRange(60, 60), 53 to 3)
        ibag.u16(g).u16(0)
        igen.u16(0).u16(0)
        val inst = Le().name("PianoInst").u16(0).name("KitInst").u16(1).name("EOI").u16(5)

        val pgen = Le(); val pbag = Le()
        pbag.u16(0).u16(0); pgen.u16(41).u16(0)          // Piano zone -> inst 0
        pbag.u16(1).u16(0); pgen.u16(41).u16(1)          // Kit zone -> inst 1
        pbag.u16(2).u16(0); pgen.u16(0).u16(0)
        val phdr = Le()
            .name("Piano").u16(0).u16(0).u16(0).u32(0).u32(0).u32(0)
            .name("Kit").u16(0).u16(128).u16(1).u32(0).u32(0).u32(0)
            .name("EOP").u16(0).u16(0).u16(2).u32(0).u32(0).u32(0)

        val body = "sfbk".toByteArray() +
            list("INFO", chunk("ifil", Le().u16(2).u16(1).bytes())) +
            list("sdta", chunk("smpl", smpl.bytes())) +
            list("pdta", chunk("phdr", phdr.bytes()), chunk("pbag", pbag.bytes()), chunk("pmod", ByteArray(10)),
                chunk("pgen", pgen.bytes()), chunk("inst", inst.bytes()), chunk("ibag", ibag.bytes()),
                chunk("imod", ByteArray(10)), chunk("igen", igen.bytes()), chunk("shdr", shdr.bytes()))
        file.writeBytes(Le().apply { out.write("RIFF".toByteArray()); u32(body.size); out.write(body) }.bytes())
    }

    /** Returns sub-chunks by id from the given LIST type. */
    private fun subChunks(file: File, listType: String): Map<String, ByteBuffer> {
        val buf = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        val result = HashMap<String, ByteBuffer>()
        var pos = 12
        while (pos + 8 <= buf.capacity()) {
            val id = String(ByteArray(4).also { buf.position(pos); buf.get(it) })
            val size = buf.getInt(pos + 4)
            if (id == "LIST" && String(ByteArray(4).also { buf.position(pos + 8); buf.get(it) }) == listType) {
                var p = pos + 12
                while (p < pos + 8 + size) {
                    val subId = String(ByteArray(4).also { buf.position(p); buf.get(it) })
                    val subSize = buf.getInt(p + 4)
                    val d = buf.duplicate()
                    d.position(p + 8)
                    d.limit(p + 8 + subSize)
                    result[subId] = d.slice().order(ByteOrder.LITTLE_ENDIAN)
                    p += 8 + subSize + (subSize and 1)
                }
            }
            pos += 8 + size + (size and 1)
        }
        return result
    }

    private fun name(b: ByteBuffer, at: Int) = String(ByteArray(20).also { b.position(at); b.get(it) }).trimEnd('\u0000')

    @Test
    fun keepsOnlyThePresetZonesAndSamplesForTheKeys() {
        val src = tmp.newFile("src.sf2").also { buildSource(it) }
        val dest = File(tmp.root, "kit.sf2")
        assertTrue(Sf2Subset.extract(src, dest, bank = 128, program = 0, keys = setOf(33), name = "Kit subset"))

        val pdta = subChunks(dest, "pdta")
        val phdr = pdta.getValue("phdr")
        assertEquals(2, phdr.capacity() / 38)                       // Kit + EOP
        assertEquals("Kit", name(phdr, 0))
        assertEquals(128, phdr.getShort(22).toInt())                // bank

        val inst = pdta.getValue("inst")
        assertEquals(2, inst.capacity() / 22)                       // KitInst + EOI
        assertEquals("KitInst", name(inst, 0))
        assertEquals(2, inst.getShort(22 + 20).toInt())             // EOI bag index: global + 33-34

        val igen = pdta.getValue("igen")
        // global pan zone, then keyRange 33-34 + sampleID remapped to 0, then terminal
        assertEquals(17, igen.getShort(0).toInt())
        assertEquals(43, igen.getShort(4).toInt())
        assertEquals(keyRange(33, 34), igen.getShort(6).toInt() and 0xFFFF)
        assertEquals(53, igen.getShort(8).toInt())
        assertEquals(0, igen.getShort(10).toInt())

        // Samples 1 and 2 (stereo pair) survive as 0 and 1, linked to each other.
        val shdr = pdta.getValue("shdr")
        assertEquals(3, shdr.capacity() / 46)
        assertEquals("S1", name(shdr, 0))
        assertEquals("S2", name(shdr, 46))
        assertEquals(1, shdr.getShort(42).toInt())                  // S1 link -> 1
        assertEquals(0, shdr.getShort(46 + 42).toInt())             // S2 link -> 0
        val start1 = shdr.getInt(46 + 20)
        assertEquals(56, start1)                                    // 10 points + 46 padding
        assertEquals(start1 + 2, shdr.getInt(46 + 28))              // loop start rebased

        val smpl = subChunks(dest, "sdta").getValue("smpl")
        val points = ShortArray(10) { smpl.getShort((start1 + it) * 2) }
        assertArrayEquals(samplePoints(2), points)
    }

    @Test
    fun keyOutsideEveryZoneKeepsNoSamplesAndFails() {
        val src = tmp.newFile("src.sf2").also { buildSource(it) }
        val dest = File(tmp.root, "kit.sf2")
        assertFalse(Sf2Subset.extract(src, dest, bank = 128, program = 0, keys = setOf(90), name = "x"))
        assertFalse(dest.exists())
    }

    @Test
    fun missingPresetFails() {
        val src = tmp.newFile("src.sf2").also { buildSource(it) }
        assertFalse(Sf2Subset.extract(src, File(tmp.root, "kit.sf2"), bank = 5, program = 0, keys = setOf(33), name = "x"))
    }

    /** Set FLUIDR3_SF2 to a FluidR3_GM.sf2 to check the real metronome subset. */
    @Test
    fun fluidR3MetronomeKitIsSmall() {
        val path = System.getenv("FLUIDR3_SF2")
        assumeTrue(path != null && File(path).exists())
        val dest = File(tmp.root, "kit.sf2")
        val keys = setOf(31, 33, 34, 37, 42, 56, 75, 76, 77)
        assertTrue(Sf2Subset.extract(File(path!!), dest, 128, 0, keys, "Pyano Metronome Kit"))
        println("FluidR3 metronome kit: ${dest.length()} bytes")
        assertTrue(dest.length() < 5_000_000)
        // Round trip: extracting the same keys from the subset keeps every sample.
        val again = File(tmp.root, "again.sf2")
        assertTrue(Sf2Subset.extract(dest, again, 128, 0, keys, "Pyano Metronome Kit"))
        assertEquals(dest.length(), again.length())
    }
}
