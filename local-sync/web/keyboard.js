// Concern: displays MIDI notes below an audio player | Non-concern: sound synthesis | IO: (MIDI and audio clock) -> keyboard lights
import { parseMidi, MidiState } from './midi.mjs';

const names = ['C', 'C♯', 'D', 'D♯', 'E', 'F', 'F♯', 'G', 'G♯', 'A', 'A♯', 'B'];
const noteName = note => `${names[note % 12]}${Math.floor(note / 12) - 1}`;
const black = note => [1, 3, 6, 8, 10].includes(note % 12);
const ns = 'http://www.w3.org/2000/svg';

export function attachKeyboard(container, audio, url) {
  const controller = new AbortController();
  const signal = controller.signal;
  const section = document.createElement('div');
  section.className = 'midi-preview';
  const label = document.createElement('p');
  label.className = 'midi-caption';
  label.textContent = 'Loading MIDI keyboard…';
  const svg = document.createElementNS(ns, 'svg');
  svg.classList.add('midi-keyboard');
  svg.setAttribute('viewBox', '0 0 728 88');
  svg.setAttribute('role', 'img');
  svg.setAttribute('aria-label', '88-key piano: notes highlighted from the MIDI file');
  const keys = new Map();
  let whites = 0;
  const dark = [];
  for (let note = 21; note <= 108; note++) {
    const isBlack = black(note);
    const key = document.createElementNS(ns, 'rect');
    key.setAttribute('x', isBlack ? whites * 14 - 4.5 : whites * 14 + .5);
    key.setAttribute('y', '.5');
    key.setAttribute('width', isBlack ? '9' : '13');
    key.setAttribute('height', isBlack ? '51' : '86');
    key.setAttribute('rx', '1.5');
    key.classList.add(isBlack ? 'black-key' : 'white-key');
    key.dataset.note = String(note);
    const title = document.createElementNS(ns, 'title');
    title.textContent = noteName(note);
    key.append(title);
    keys.set(note, key);
    if (isBlack) dark.push(key);
    else {
      svg.append(key);
      if (note % 12 === 0) {
        const text = document.createElementNS(ns, 'text');
        text.setAttribute('x', whites * 14 + 7);
        text.setAttribute('y', '80');
        text.textContent = noteName(note);
        svg.append(text);
      }
      whites++;
    }
  }
  svg.append(...dark);
  section.append(svg, label);
  container.append(section);
  let state;
  let frame = 0;
  let previous = '';
  const render = () => {
    if (!state || signal.aborted) return;
    const notes = state.seek(audio.currentTime);
    const signature = [...notes].map(([note, mode]) => `${note}:${mode}`).join(',');
    if (signature === previous) return;
    previous = signature;
    keys.forEach((key, note) => {
      key.classList.toggle('held', notes.get(note) === 'held');
      key.classList.toggle('sustained', notes.get(note) === 'sustained');
    });
    label.textContent = notes.size ? `MIDI · ${[...notes.keys()].sort((a, b) => a - b).map(noteName).join(' · ')}` : 'MIDI · Green: key down · Amber: sustain';
  };
  const animate = () => {
    frame = 0;
    render();
    if (!audio.paused && !audio.ended && !signal.aborted) frame = requestAnimationFrame(animate);
  };
  for (const event of ['play', 'pause', 'seeking', 'seeked', 'timeupdate', 'ended']) {
    audio.addEventListener(event, () => {
      render();
      if (!audio.paused && !audio.ended && !frame) frame = requestAnimationFrame(animate);
      if ((audio.paused || audio.ended) && frame) { cancelAnimationFrame(frame); frame = 0; }
    }, { signal });
  }
  fetch(url, { signal }).then(async response => {
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const buffer = await response.arrayBuffer();
    if (buffer.byteLength > 16 * 1024 * 1024) throw new Error('MIDI exceeds preview size limit');
    state = new MidiState(parseMidi(buffer));
    previous = 'initial';
    render();
  }).catch(error => {
    if (!signal.aborted) label.textContent = `MIDI preview unavailable: ${error.message}`;
  });
  return () => { controller.abort(); if (frame) cancelAnimationFrame(frame); };
}
