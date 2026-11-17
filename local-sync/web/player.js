// Concern: loads local songs into audio players | Non-concern: MIDI parsing | IO: (recording API) -> playback controls
import { attachKeyboard } from './keyboard.js';

let disposers = [];
const status = document.querySelector('#status');
const list = document.querySelector('#recordings');
const refresh = document.querySelector('#refresh');
async function load() {
  refresh.disabled = true;
  try {
    const response = await fetch('/api/recordings');
    if (!response.ok) throw new Error(`Library unavailable (${response.status})`);
    const songs = await response.json();
    list.querySelectorAll('audio').forEach(audio => audio.pause());
    disposers.forEach(dispose => dispose());
    disposers = [];
    list.replaceChildren();
    status.textContent = songs.length ? `${songs.length} saved ${songs.length === 1 ? 'take' : 'takes'}` : 'Your library is quiet. Upload a take from Pyano to begin.';
    for (const song of songs) {
      const card = document.createElement('article');
      const title = document.createElement('h3');
      title.textContent = song.title;
      const audio = document.createElement('audio');
      audio.controls = true;
      audio.preload = 'metadata';
      audio.src = `/recordings/${song.id}/audio.m4a`;
      audio.setAttribute('aria-label', song.title);
      audio.addEventListener('play', () => list.querySelectorAll('audio').forEach(other => { if (other !== audio) other.pause(); }));
      card.append(title, audio);
      if (song.hashes['performance.mid']) {
        disposers.push(attachKeyboard(card, audio, `/recordings/${song.id}/performance.mid`));
        const midi = document.createElement('a');
        midi.href = `/recordings/${song.id}/performance.mid`;
        midi.textContent = 'Download MIDI';
        midi.download = `${song.title}.mid`;
        card.append(midi);
      } else {
        const note = document.createElement('p');
        note.className = 'midi-caption';
        note.textContent = 'No MIDI file for this take';
        card.append(note);
      }
      list.append(card);
    }
  } catch (error) {
    status.textContent = error.message;
  } finally {
    refresh.disabled = false;
  }
}
refresh.addEventListener('click', load);
load();
