// Concern: verifies MIDI preview timing and state | Non-concern: audio synthesis | IO: (SMF fixtures) -> timing assertions
import test from 'node:test';
import assert from 'node:assert/strict';
import { parseMidi, MidiState } from './web/midi.mjs';

function file(tracks, format = 0, division = 500) {
  const bytes = [77, 84, 104, 100, 0, 0, 0, 6, 0, format, 0, tracks.length, division >> 8, division & 255];
  for (const track of tracks) {
    const size = track.length;
    bytes.push(77, 84, 114, 107, size >>> 24, (size >>> 16) & 255, (size >>> 8) & 255, size & 255, ...track);
  }
  return Uint8Array.from(bytes).buffer;
}
const end = [0, 255, 47, 0];
const chord = file([[
  0, 0x90, 60, 100, 0, 0x90, 64, 80,
  0x83, 0x74, 0xB0, 64, 127,
  0, 0x80, 60, 0, 0, 64, 0,
  0x83, 0x74, 0xB0, 64, 0, ...end,
]]);

test('held notes, sustain, and backward seek follow the same clock', () => {
  const timeline = parseMidi(chord);
  assert.equal(timeline.duration, 1);
  const state = new MidiState(timeline);
  assert.deepEqual([...state.seek(.25)], [[60, 'held'], [64, 'held']]);
  assert.deepEqual([...state.seek(.75)], [[60, 'sustained'], [64, 'sustained']]);
  assert.deepEqual([...state.seek(1)], []);
  assert.deepEqual([...state.seek(.25)], [[60, 'held'], [64, 'held']]);
  assert.deepEqual([...state.seek(.75)], [[60, 'sustained'], [64, 'sustained']]);
});

test('tempo changes in a separate track determine note times', () => {
  const tempo = [0, 255, 81, 3, 7, 161, 32, 0x83, 0x74, 255, 81, 3, 15, 66, 64, 0x83, 0x74, 255, 47, 0];
  const notes = [0, 0x90, 60, 100, 0x87, 0x68, 0x80, 60, 0, ...end];
  const timeline = parseMidi(file([tempo, notes], 1));
  assert.equal(timeline.duration, 1.5);
  assert.equal(timeline.events[1].seconds, 1.5);
  const state = new MidiState(timeline);
  assert.equal(state.seek(1.49).get(60), 'held');
  assert.equal(state.seek(1.5).size, 0);
});

test('velocity-zero note on with running status releases the key', () => {
  const state = new MidiState(parseMidi(file([[0, 0x90, 60, 100, 100, 60, 0, ...end]])));
  assert.equal(state.seek(.05).get(60), 'held');
  assert.equal(state.seek(.1).size, 0);
});

test('one channel cannot release another channel playing the same pitch', () => {
  const state = new MidiState(parseMidi(file([[
    0, 0x90, 60, 100, 0, 0x91, 60, 90, 100, 0x80, 60, 0, 100, 0x81, 60, 0, ...end,
  ]])));
  assert.equal(state.seek(.1).get(60), 'held');
  assert.equal(state.seek(.2).size, 0);
});

test('all-notes-off respects sustain and all-sound-off clears it', () => {
  const state = new MidiState(parseMidi(file([[
    0, 0x90, 60, 100, 0, 0xB0, 64, 127, 100, 123, 0, 100, 120, 0, ...end,
  ]])));
  assert.equal(state.seek(.1).get(60), 'sustained');
  assert.equal(state.seek(.2).size, 0);
});

test('controller reset clears pedal-held notes without releasing pressed keys', () => {
  const state = new MidiState(parseMidi(file([[
    0, 0x90, 60, 100, 0, 64, 100, 0, 0xB0, 64, 127, 0, 0x80, 60, 0, 100, 0xB0, 121, 0, ...end,
  ]])));
  assert.deepEqual([...state.seek(.1)], [[64, 'held']]);
});

test('invalid headers, truncated events, invalid tempo, and running status fail clearly', () => {
  assert.throws(() => parseMidi(new ArrayBuffer(0)), /Truncated/);
  assert.throws(() => parseMidi(file([[0, 60, 100]])), /running status/);
  assert.throws(() => parseMidi(file([[0, 0x90, 60]])), /Truncated/);
  assert.throws(() => parseMidi(file([[0, 255, 81, 3, 0, 0, 0, ...end]])), /tempo/);
  assert.throws(() => parseMidi(file([end], 0, 0xE728)), /beat-based/);
});
