// Concern: decodes MIDI into timed note state | Non-concern: audio playback | IO: (SMF bytes and time) -> held and sustained notes
export function parseMidi(buffer) {
  const bytes = new Uint8Array(buffer);
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength);
  let position = 0;
  let limit = bytes.length;
  const need = size => { if (position + size > limit) throw new Error('Truncated MIDI file'); };
  const byte = () => { need(1); return bytes[position++]; };
  const word = () => (byte() << 8) | byte();
  const integer = () => { need(4); const value = view.getUint32(position); position += 4; return value; };
  const tag = () => String.fromCharCode(byte(), byte(), byte(), byte());
  const variable = () => {
    let value = 0;
    for (let i = 0; i < 4; i++) {
      const next = byte();
      value = value * 128 + (next & 127);
      if (!(next & 128)) return value;
    }
    throw new Error('Invalid MIDI delta or length');
  };
  if (tag() !== 'MThd') throw new Error('Missing MIDI header');
  const headerSize = integer();
  if (headerSize < 6) throw new Error('Invalid MIDI header');
  need(headerSize);
  const format = word();
  const tracks = word();
  const division = word();
  if (format > 1 || tracks === 0 || (format === 0 && tracks !== 1)) throw new Error('Unsupported MIDI format');
  if (!division || (division & 0x8000)) throw new Error('Expected beat-based MIDI timing');
  position += headerSize - 6;
  const events = [];
  for (let track = 0; track < tracks; track++) {
    limit = bytes.length;
    if (tag() !== 'MTrk') throw new Error('Missing MIDI track');
    const size = integer();
    need(size);
    const end = position + size;
    limit = end;
    let tick = 0;
    let running = 0;
    while (position < end) {
      tick += variable();
      let status = byte();
      if (status < 128) {
        if (!running) throw new Error('MIDI running status has no status byte');
        position--;
        status = running;
      }
      if (status === 0xFF) {
        running = 0;
        const type = byte();
        const length = variable();
        need(length);
        if (type === 0x51) {
          if (length !== 3) throw new Error('Invalid MIDI tempo');
          const tempo = bytes[position] * 65536 + bytes[position + 1] * 256 + bytes[position + 2];
          if (!tempo) throw new Error('Invalid MIDI tempo');
          events.push({ tick, tempo });
        }
        position += length;
      } else if (status === 0xF0 || status === 0xF7) {
        running = 0;
        const length = variable();
        need(length);
        position += length;
      } else {
        if (status < 0x80 || status >= 0xF0) throw new Error('Unsupported MIDI event');
        running = status;
        const command = status & 0xF0;
        const first = byte();
        const second = command === 0xC0 || command === 0xD0 ? 0 : byte();
        if (first > 127 || second > 127) throw new Error('Invalid MIDI data byte');
        if ([0x80, 0x90, 0xB0].includes(command)) events.push({ tick, command, channel: status & 15, first, second });
      }
      if (events.length > 500000) throw new Error('MIDI file has too many events');
    }
    events.push({ tick, end: true });
  }
  events.sort((a, b) => a.tick - b.tick);
  let tick = 0;
  let seconds = 0;
  let tempo = 500000;
  const timeline = [];
  for (const event of events) {
    seconds += (event.tick - tick) * tempo / division / 1000000;
    tick = event.tick;
    if (event.tempo) tempo = event.tempo;
    else if (!event.end) timeline.push({ ...event, seconds });
  }
  return { events: timeline, duration: seconds };
}

export class MidiState {
  constructor(timeline) {
    this.events = timeline.events;
    this.reset();
  }
  reset() {
    this.cursor = 0;
    this.time = -1;
    this.held = new Uint32Array(16 * 128);
    this.sustained = new Uint8Array(16 * 128);
    this.pedal = new Uint8Array(16);
  }
  seek(seconds) {
    if (!Number.isFinite(seconds) || seconds < 0) seconds = 0;
    if (seconds < this.time) this.reset();
    while (this.cursor < this.events.length && this.events[this.cursor].seconds <= seconds) {
      this.apply(this.events[this.cursor++]);
    }
    this.time = seconds;
    const notes = new Map();
    for (let channel = 0; channel < 16; channel++) {
      for (let note = 0; note < 128; note++) {
        const index = channel * 128 + note;
        if (this.held[index]) notes.set(note, 'held');
        else if (this.sustained[index] && !notes.has(note)) notes.set(note, 'sustained');
      }
    }
    return notes;
  }
  apply({ command, channel, first, second }) {
    const index = channel * 128 + first;
    if (command === 0x90 && second > 0) {
      this.held[index]++;
    } else if (command === 0x80 || (command === 0x90 && second === 0)) {
      if (this.held[index]) {
        this.held[index]--;
        if (this.pedal[channel]) this.sustained[index] = 1;
      }
    } else if (command === 0xB0) {
      if (first === 64) {
        this.pedal[channel] = second >= 64 ? 1 : 0;
        if (this.pedal[channel]) return;
      } else if (![120, 121, 123].includes(first)) return;
      if (first === 121) this.pedal[channel] = 0;
      for (let note = 0; note < 128; note++) {
        const key = channel * 128 + note;
        if (first === 123) {
          if (this.held[key] && this.pedal[channel]) this.sustained[key] = 1;
          this.held[key] = 0;
        } else {
          this.sustained[key] = 0;
          if (first === 120) this.held[key] = 0;
        }
      }
    }
  }
}
