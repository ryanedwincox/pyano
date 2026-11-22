// Concern: balances shared touch-piano voices across windows | Non-concern: MIDI source selection | IO: (owner and pitch) -> note events
package com.pyano.keyboard

class PianoNoteOwners(private val onNote: (Int, Boolean) -> Unit) {
    private val notes = mutableMapOf<Any, MutableSet<Int>>()

    @Synchronized fun note(owner: Any, pitch: Int, down: Boolean) {
        if (down) {
            val held = notes.values.any { pitch in it }
            if (notes.getOrPut(owner) { mutableSetOf() }.add(pitch) && !held) onNote(pitch, true)
        } else {
            val owned = notes[owner] ?: return
            if (!owned.remove(pitch)) return
            if (owned.isEmpty()) notes.remove(owner)
            if (notes.values.none { pitch in it }) onNote(pitch, false)
        }
    }

    @Synchronized fun release(owner: Any) {
        notes[owner]?.toList()?.forEach { note(owner, it, false) }
    }

    @Synchronized fun releaseAll() {
        notes.keys.toList().forEach(::release)
    }
}
