// Sf2Subset: Writes a small SF2 holding one preset of a source SoundFont, cut down to chosen keys.
// NOT concerned with: loading into the synth, choosing presets/keys, or metadata for the UI.
package com.pyano.audio

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Extracts one preset from an SF2, keeping only the zones (and samples) that sound for
 * [keys]. Used for the metronome: the GM drum kit's handful of click sounds is a few
 * hundred KB, while loading the whole GM SoundFont keeps ~150 MB of samples in RAM.
 *
 * Only 16-bit sample data is carried over (an sm24 chunk is dropped).
 */
object Sf2Subset {

    private const val GEN_KEY_RANGE = 43
    private const val GEN_INSTRUMENT = 41
    private const val GEN_SAMPLE_ID = 53
    private const val SAMPLE_TYPE_MONO = 1
    private const val SAMPLE_PADDING = 46  // zero points the spec requires after each sample

    private class Gen(val oper: Int, var amount: Int)
    private class Zone(val gens: List<Gen>, val mods: List<ByteArray>)
    private class Phdr(val name: ByteArray, val preset: Int, val bank: Int, val bag: Int,
                       val library: Int, val genre: Int, val morphology: Int)
    private class Inst(val name: ByteArray, val bag: Int)
    private class Shdr(val name: ByteArray, var start: Int, var end: Int, var startLoop: Int,
                       var endLoop: Int, val rate: Int, val pitch: Byte, val correction: Byte,
                       var link: Int, var type: Int)

    private class Pdta(
        val phdr: List<Phdr>, val pbag: List<IntArray>, val pmod: List<ByteArray>, val pgen: List<Gen>,
        val inst: List<Inst>, val ibag: List<IntArray>, val imod: List<ByteArray>, val igen: List<Gen>,
        val shdr: List<Shdr>,
    )

    /** Returns true if [dest] was written. [dest] is left untouched on failure. */
    fun extract(source: File, dest: File, bank: Int, program: Int, keys: Set<Int>, name: String): Boolean {
        RandomAccessFile(source, "r").use { raf ->
            val (smplOffset, pdta) = readChunks(raf) ?: return false
            val presetIndex = pdta.phdr.dropLast(1).indexOfFirst { it.bank == bank && it.preset == program }
            if (presetIndex < 0) return false
            val preset = pdta.phdr[presetIndex]

            fun inKeys(zone: Zone): Boolean {
                val range = zone.gens.firstOrNull { it.oper == GEN_KEY_RANGE } ?: return true
                val lo = range.amount and 0xFF
                val hi = (range.amount shr 8) and 0xFF
                return keys.any { it in lo..hi }
            }

            // Preset zones: keep the global zone (no instrument) and zones that cover a key.
            val presetZones = zones(pdta.pbag, pdta.pgen, pdta.pmod, preset.bag, pdta.phdr[presetIndex + 1].bag)
                .filterIndexed { i, z -> isGlobal(z, GEN_INSTRUMENT, i) || (hasGen(z, GEN_INSTRUMENT) && inKeys(z)) }

            val instOrder = presetZones.mapNotNull { z -> z.gens.firstOrNull { it.oper == GEN_INSTRUMENT }?.amount }.distinct()
            val instZones = instOrder.map { idx ->
                zones(pdta.ibag, pdta.igen, pdta.imod, pdta.inst[idx].bag, pdta.inst[idx + 1].bag)
                    .filterIndexed { i, z -> isGlobal(z, GEN_SAMPLE_ID, i) || (hasGen(z, GEN_SAMPLE_ID) && inKeys(z)) }
            }

            // Samples used, plus the other half of any stereo pair.
            val sampleSet = sortedSetOf<Int>()
            instZones.flatten().forEach { z -> z.gens.firstOrNull { it.oper == GEN_SAMPLE_ID }?.let { sampleSet += it.amount } }
            var added: Boolean
            do {
                added = false
                for (s in sampleSet.toList()) {
                    val h = pdta.shdr[s]
                    if (h.type != SAMPLE_TYPE_MONO && h.link < pdta.shdr.size - 1 && sampleSet.add(h.link)) added = true
                }
            } while (added)
            if (sampleSet.isEmpty()) return false

            val sampleMap = sampleSet.withIndex().associate { (newIdx, old) -> old to newIdx }
            val instMap = instOrder.withIndex().associate { (newIdx, old) -> old to newIdx }
            presetZones.forEach { z -> z.gens.filter { it.oper == GEN_INSTRUMENT }.forEach { it.amount = instMap.getValue(it.amount) } }
            instZones.flatten().forEach { z -> z.gens.filter { it.oper == GEN_SAMPLE_ID }.forEach { it.amount = sampleMap.getValue(it.amount) } }

            // Copy each sample's points (plus padding) and rebase its offsets.
            val smpl = ByteArrayOutputStream()
            val samples = sampleSet.map { old ->
                val h = pdta.shdr[old]
                val data = ByteArray((h.end - h.start) * 2)
                raf.seek(smplOffset + h.start.toLong() * 2)
                raf.readFully(data)
                val newStart = smpl.size() / 2
                val delta = newStart - h.start
                smpl.write(data)
                smpl.write(ByteArray(SAMPLE_PADDING * 2))
                Shdr(h.name, h.start + delta, h.end + delta, h.startLoop + delta, h.endLoop + delta,
                     h.rate, h.pitch, h.correction,
                     if (h.type == SAMPLE_TYPE_MONO) 0 else sampleMap[h.link] ?: 0, h.type)
            }

            val bytes = write(name, preset, presetZones, instOrder.map { pdta.inst[it] }, instZones, samples, smpl.toByteArray())
            val tmp = File(dest.path + ".tmp")
            tmp.writeBytes(bytes)
            if (tmp.renameTo(dest)) return true
            tmp.delete()
            return false
        }
    }

    private fun hasGen(z: Zone, oper: Int) = z.gens.any { it.oper == oper }

    // Per the spec only the first zone can be global: it lacks the terminal generator.
    private fun isGlobal(z: Zone, terminal: Int, index: Int) = index == 0 && !hasGen(z, terminal)

    private fun zones(bags: List<IntArray>, gens: List<Gen>, mods: List<ByteArray>, from: Int, to: Int) =
        (from until to).map { b ->
            Zone(
                gens.subList(bags[b][0], bags[b + 1][0]).map { Gen(it.oper, it.amount) },
                mods.subList(bags[b][1], bags[b + 1][1]),
            )
        }

    // --- Reading ---

    /** Returns the file offset of the smpl data and the parsed pdta, or null if malformed. */
    private fun readChunks(raf: RandomAccessFile): Pair<Long, Pdta>? {
        val header = ByteArray(12)
        raf.readFully(header)
        if (String(header, 0, 4) != "RIFF" || String(header, 8, 4) != "sfbk") return null
        var smplOffset = -1L
        val sub = HashMap<String, ByteBuffer>()
        var pos = 12L
        while (pos + 8 <= raf.length()) {
            raf.seek(pos)
            val id = readId(raf)
            val size = readU32(raf)
            if (id == "LIST") {
                val listType = readId(raf)
                var p = pos + 12
                val end = pos + 8 + size
                while (p + 8 <= end) {
                    raf.seek(p)
                    val subId = readId(raf)
                    val subSize = readU32(raf)
                    if (listType == "sdta" && subId == "smpl") smplOffset = p + 8
                    if (listType == "pdta") {
                        val data = ByteArray(subSize.toInt())
                        raf.readFully(data)
                        sub[subId] = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                    }
                    p += 8 + subSize + (subSize and 1)
                }
            }
            pos += 8 + size + (size and 1)
        }
        if (smplOffset < 0) return null
        fun records(id: String, size: Int): List<ByteBuffer> {
            val buf = sub[id] ?: return emptyList()
            return (0 until buf.capacity() / size).map { i ->
                val d = buf.duplicate()
                d.position(i * size)
                d.slice().order(ByteOrder.LITTLE_ENDIAN)
            }
        }
        fun name(b: ByteBuffer) = ByteArray(20).also { b.get(it) }
        fun u16(b: ByteBuffer) = b.short.toInt() and 0xFFFF
        val pdta = Pdta(
            phdr = records("phdr", 38).map { Phdr(name(it), u16(it), u16(it), u16(it), it.int, it.int, it.int) },
            pbag = records("pbag", 4).map { intArrayOf(u16(it), u16(it)) },
            pmod = records("pmod", 10).map { ByteArray(10).also { a -> it.get(a) } },
            pgen = records("pgen", 4).map { Gen(u16(it), u16(it)) },
            inst = records("inst", 22).map { Inst(name(it), u16(it)) },
            ibag = records("ibag", 4).map { intArrayOf(u16(it), u16(it)) },
            imod = records("imod", 10).map { ByteArray(10).also { a -> it.get(a) } },
            igen = records("igen", 4).map { Gen(u16(it), u16(it)) },
            shdr = records("shdr", 46).map {
                Shdr(name(it), it.int, it.int, it.int, it.int, it.int, it.get(), it.get(), u16(it), u16(it))
            },
        )
        if (pdta.phdr.size < 2 || pdta.inst.size < 2 || pdta.shdr.size < 2) return null
        return smplOffset to pdta
    }

    private fun readId(raf: RandomAccessFile) = ByteArray(4).also { raf.readFully(it) }.toString(Charsets.US_ASCII)

    private fun readU32(raf: RandomAccessFile): Long {
        val b = ByteArray(4)
        raf.readFully(b)
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    }

    // --- Writing ---

    private class Le {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun u32(v: Int) = apply { u16(v); u16(v shr 16) }
        fun bytes(b: ByteArray) = apply { out.write(b) }
        fun name(s: String) = bytes(ByteArray(20).also { s.toByteArray(Charsets.US_ASCII).copyInto(it, 0, 0, minOf(19, s.length)) })
        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private fun chunk(id: String, data: ByteArray): ByteArray = Le().apply {
        bytes(id.toByteArray(Charsets.US_ASCII)); u32(data.size); bytes(data)
        if (data.size % 2 == 1) u8(0)
    }.toByteArray()

    private fun list(type: String, vararg chunks: ByteArray): ByteArray =
        chunk("LIST", type.toByteArray(Charsets.US_ASCII) + chunks.fold(ByteArray(0)) { a, c -> a + c })

    private fun zstr(s: String): ByteArray {
        val b = s.toByteArray(Charsets.US_ASCII) + 0
        return if (b.size % 2 == 1) b + 0 else b
    }

    private fun write(name: String, preset: Phdr, presetZones: List<Zone>, insts: List<Inst>,
                      instZones: List<List<Zone>>, samples: List<Shdr>, smpl: ByteArray): ByteArray {
        val phdr = Le(); val pbag = Le(); val pmod = Le(); val pgen = Le()
        val inst = Le(); val ibag = Le(); val imod = Le(); val igen = Le(); val shdr = Le()

        fun writeZones(zones: List<Zone>, bag: Le, gen: Le, mod: Le, counts: IntArray) {
            for (z in zones) {
                bag.u16(counts[0]).u16(counts[1])
                z.gens.forEach { gen.u16(it.oper).u16(it.amount) }
                z.mods.forEach { mod.bytes(it) }
                counts[0] += z.gens.size
                counts[1] += z.mods.size
            }
        }

        val pCounts = intArrayOf(0, 0)
        phdr.bytes(preset.name).u16(preset.preset).u16(preset.bank).u16(0)
            .u32(preset.library).u32(preset.genre).u32(preset.morphology)
        writeZones(presetZones, pbag, pgen, pmod, pCounts)
        phdr.name("EOP").u16(0).u16(0).u16(presetZones.size).u32(0).u32(0).u32(0)
        pbag.u16(pCounts[0]).u16(pCounts[1])
        pmod.bytes(ByteArray(10))
        pgen.u16(0).u16(0)

        val iCounts = intArrayOf(0, 0)
        var bagIndex = 0
        insts.forEachIndexed { i, it ->
            inst.bytes(it.name).u16(bagIndex)
            writeZones(instZones[i], ibag, igen, imod, iCounts)
            bagIndex += instZones[i].size
        }
        inst.name("EOI").u16(bagIndex)
        ibag.u16(iCounts[0]).u16(iCounts[1])
        imod.bytes(ByteArray(10))
        igen.u16(0).u16(0)

        samples.forEach {
            shdr.bytes(it.name).u32(it.start).u32(it.end).u32(it.startLoop).u32(it.endLoop)
                .u32(it.rate).u8(it.pitch.toInt()).u8(it.correction.toInt()).u16(it.link).u16(it.type)
        }
        shdr.name("EOS").u32(0).u32(0).u32(0).u32(0).u32(0).u8(0).u8(0).u16(0).u16(0)

        val info = list("INFO",
            chunk("ifil", Le().u16(2).u16(1).toByteArray()),
            chunk("isng", zstr("EMU8000")),
            chunk("INAM", zstr(name)),
        )
        val sdta = list("sdta", chunk("smpl", smpl))
        val pdta = list("pdta",
            chunk("phdr", phdr.toByteArray()), chunk("pbag", pbag.toByteArray()),
            chunk("pmod", pmod.toByteArray()), chunk("pgen", pgen.toByteArray()),
            chunk("inst", inst.toByteArray()), chunk("ibag", ibag.toByteArray()),
            chunk("imod", imod.toByteArray()), chunk("igen", igen.toByteArray()),
            chunk("shdr", shdr.toByteArray()),
        )
        val body = "sfbk".toByteArray(Charsets.US_ASCII) + info + sdta + pdta
        return Le().bytes("RIFF".toByteArray(Charsets.US_ASCII)).u32(body.size).bytes(body).toByteArray()
    }
}
