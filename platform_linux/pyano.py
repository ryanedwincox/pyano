#!/usr/bin/env python3
"""[Role]: Interactive Linux CLI that plays a live MIDI keyboard through a
SoundFont using FluidSynth, optimized for low playback latency.

This module owns: parsing CLI options, locating audio/MIDI devices, building and
configuring the FluidSynth instance (gain, reverb, chorus, buffering), and the
realtime loop that forwards incoming MIDI events to the synthesizer.

NOT concerned with: SoundFont authoring, MIDI file recording/playback, GUI or
networking, or any non-interactive/automation use. It is a human-driven terminal
tool, not an agent interface.

I/O: reads a SoundFont path plus options from argv and live events from a MIDI
input port (or stdin in --test-tone mode); writes synthesized audio to an ALSA/
PipeWire/JACK/PulseAudio device; user-facing status to stdout; errors and
diagnostics to stderr via logging.
"""

from __future__ import annotations

import argparse
import logging
import re
import signal
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from types import FrameType
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import fluidsynth

__version__ = "1.0.0"

logger = logging.getLogger("pyano")


@dataclass(frozen=True)
class ReverbConfig:
    roomsize: float = 0.2
    damping: float = 0.0
    width: float = 0.5
    level: float = 0.9


@dataclass(frozen=True)
class ChorusConfig:
    nr: int = 3
    level: float = 2.0
    speed: float = 0.3
    depth: float = 8.0
    type: int = 0


def parse_aplay_card(line: str, name_pattern: str) -> str | None:
    """Pure parse of one `aplay -l` line into a `plughw:CARD,0` device string."""
    if name_pattern.lower() not in line.lower():
        return None
    match = re.match(r"card (\d+):", line)
    if not match:
        return None
    return f"plughw:{match.group(1)},0"


def find_alsa_device(name_pattern: str = "arturia") -> str | None:
    try:
        result = subprocess.run(
            ["aplay", "-l"], capture_output=True, text=True, check=False
        )
    except FileNotFoundError:
        logger.debug("`aplay` not found; cannot auto-detect ALSA device.")
        return None
    for line in result.stdout.splitlines():
        device = parse_aplay_card(line, name_pattern)
        if device:
            print(f"Found audio device: {line.strip()}")
            return device
    return None


def list_midi_ports() -> list[str]:
    # Lazy import: mido is a heavy optional native backend — keeping it
    # function-level lets the module import without the lib (tests) and keeps
    # --version/--list fast and fail-fast.
    import mido

    ports: list[str] = mido.get_input_names()
    if not ports:
        print("No MIDI input ports found.")
        print("Tips:")
        print("  - Check your keyboard is connected to the MiniFuse 2 MIDI port")
        print("  - Run: aconnect -l")
        print("  - You may need: sudo modprobe snd-seq-midi")
    else:
        print("Available MIDI input ports:")
        for i, name in enumerate(ports):
            print(f"  [{i}] {name}")
    return ports


def find_midi_port(ports: list[str], requested: str | None) -> str | None:
    if not ports:
        return None

    if requested is not None:
        try:
            idx = int(requested)
            if 0 <= idx < len(ports):
                return ports[idx]
        except ValueError:
            pass

        requested_lower = requested.lower()
        for port in ports:
            if requested_lower in port.lower():
                return port

        print(f"No MIDI port matching '{requested}'. Available:")
        for p in ports:
            print(f"  {p}")
        return None

    # Auto-select: prefer non-"Through" ports.
    for port in ports:
        if "through" not in port.lower():
            return port
    return ports[0]


def build_synth(
    sf_path: Path,
    audio_driver: str,
    device: str | None,
    gain: float,
    buffer_size: int,
) -> fluidsynth.Synth:
    # Lazy import: fluidsynth is a heavy optional native backend — keeping it
    # function-level lets the module import without the lib (tests) and keeps
    # --version/--list fast and fail-fast.
    import fluidsynth

    if device is None and audio_driver in ("alsa", "pipewire"):
        device = find_alsa_device("arturia")
        if not device:
            print("Warning: MiniFuse not found, using default audio device")

    fs = fluidsynth.Synth(gain=gain)

    # FluidSynth audio settings for low latency.
    fs.setting("audio.driver", audio_driver)
    fs.setting("audio.period-size", buffer_size)
    fs.setting("audio.periods", 2)
    if device:
        fs.setting("audio.alsa.device", device)

    fs.start()

    sfid = fs.sfload(str(sf_path))
    if sfid == -1:
        fs.delete()
        logger.error("Could not load soundfont: %s", sf_path)
        sys.exit(1)

    fs.program_select(0, sfid, 0, 0)  # Channel 0, Bank 0, Preset 0 (Grand Piano)
    print(f"Loaded soundfont: {sf_path}")
    return fs


def configure_effects(
    fs: fluidsynth.Synth,
    reverb: ReverbConfig | None,
    chorus: ChorusConfig | None,
) -> None:
    if reverb is None:
        fs.set_reverb(level=0.0)
    else:
        fs.set_reverb(
            roomsize=reverb.roomsize,
            damping=reverb.damping,
            width=reverb.width,
            level=reverb.level,
        )

    if chorus is None:
        fs.set_chorus(level=0.0)
    else:
        fs.set_chorus(
            nr=chorus.nr,
            level=chorus.level,
            speed=chorus.speed,
            depth=chorus.depth,
            type=chorus.type,
        )


def midi_event_loop(fs: fluidsynth.Synth, port_name: str) -> None:
    # Lazy import: mido is a heavy optional native backend — keeping it
    # function-level lets the module import without the lib (tests) and keeps
    # --version/--list fast and fail-fast.
    import mido

    print(f"Listening on MIDI port: {port_name}")
    print("Press Ctrl+C to quit.")

    running = True

    def on_signal(sig: int, frame: FrameType | None) -> None:
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)

    with mido.open_input(port_name) as inport:
        while running:
            for msg in inport.iter_pending():
                if msg.type == "note_on":
                    if msg.velocity > 0:
                        fs.noteon(msg.channel, msg.note, msg.velocity)
                    else:
                        fs.noteoff(msg.channel, msg.note)
                elif msg.type == "note_off":
                    fs.noteoff(msg.channel, msg.note)
                # Forward CC except CC7 (volume) and CC11 (expression).
                elif msg.type == "control_change" and msg.control not in (7, 11):
                    fs.cc(msg.channel, msg.control, msg.value)
            time.sleep(0.001)  # 1ms poll — keeps CPU low without adding latency


def test_tone_repl(fs: fluidsynth.Synth) -> None:
    print("Test mode: type MIDI note numbers to play tones.")
    print("Press Ctrl+C to quit.")
    try:
        while True:
            try:
                note = int(input("Note (0-127, middle C=60): "))
                fs.noteon(0, note, 100)
                time.sleep(0.5)
                fs.noteoff(0, note)
            except ValueError:
                pass
    except (KeyboardInterrupt, EOFError):
        pass


def run(
    sf_path: Path,
    midi_port: str | None = None,
    audio_driver: str = "alsa",
    device: str | None = None,
    gain: float = 0.8,
    buffer_size: int = 256,
    reverb: ReverbConfig | None = None,
    chorus: ChorusConfig | None = None,
    test_tone: bool = False,
) -> None:
    # Lazy import: mido is a heavy optional native backend — keeping it
    # function-level lets the module import without the lib (tests) and keeps
    # --version/--list fast and fail-fast.
    import mido

    fs = build_synth(sf_path, audio_driver, device, gain, buffer_size)
    configure_effects(fs, reverb, chorus)

    port_name = find_midi_port(mido.get_input_names(), midi_port)

    try:
        if port_name is None:
            if not test_tone:
                logger.error(
                    "No MIDI port available. Connect a keyboard or rerun with "
                    "--test-tone."
                )
                sys.exit(1)
            test_tone_repl(fs)
        else:
            midi_event_loop(fs, port_name)
    except OSError:
        logger.exception("MIDI error")
        sys.exit(1)
    finally:
        fs.delete()
        print("\nDone.")


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Pyano - MIDI SoundFont synth")
    parser.add_argument("-V", "--version", action="version", version=f"pyano {__version__}")
    parser.add_argument("soundfont", type=Path, help="Path to .sf2 soundfont file")
    parser.add_argument("-p", "--port", default=None,
                        help="MIDI port name/index (auto-detects if omitted)")
    parser.add_argument("-d", "--driver", default="alsa",
                        choices=["pipewire", "alsa", "jack", "pulseaudio"],
                        help="Audio driver (default: alsa)")
    parser.add_argument("--device", default=None,
                        help="ALSA device (auto-detects MiniFuse if omitted)")
    parser.add_argument("-g", "--gain", type=float, default=0.8,
                        help="Synth gain 0.0-10.0 (default: 0.8)")
    parser.add_argument("-b", "--buffer", type=int, default=256,
                        help="Audio buffer size in frames (default: 256, lower=less latency)")
    parser.add_argument("-l", "--list", action="store_true",
                        help="List MIDI ports and exit")
    parser.add_argument("--test-tone", action="store_true",
                        help="If no MIDI port is found, enter an interactive "
                             "note-number test REPL instead of exiting")

    reverb_group = parser.add_argument_group("reverb")
    reverb_group.add_argument("--no-reverb", action="store_true", help="Disable reverb")
    reverb_group.add_argument("--reverb-room", type=float, default=0.2,
                              help="Reverb room size 0.0-1.0 (default: 0.2)")
    reverb_group.add_argument("--reverb-damp", type=float, default=0.0,
                              help="Reverb damping 0.0-1.0 (default: 0.0)")
    reverb_group.add_argument("--reverb-width", type=float, default=0.5,
                              help="Reverb width 0.0-100.0 (default: 0.5)")
    reverb_group.add_argument("--reverb-level", type=float, default=0.9,
                              help="Reverb level 0.0-1.0 (default: 0.9)")

    chorus_group = parser.add_argument_group("chorus")
    chorus_group.add_argument("--no-chorus", action="store_true", help="Disable chorus")
    chorus_group.add_argument("--chorus-voices", type=int, default=3,
                              help="Chorus voice count 0-99 (default: 3)")
    chorus_group.add_argument("--chorus-level", type=float, default=2.0,
                              help="Chorus level 0.0-10.0 (default: 2.0)")
    chorus_group.add_argument("--chorus-speed", type=float, default=0.3,
                              help="Chorus speed in Hz 0.29-5.0 (default: 0.3)")
    chorus_group.add_argument("--chorus-depth", type=float, default=8.0,
                              help="Chorus depth in ms 0.0-21.0 (default: 8.0)")
    chorus_group.add_argument("--chorus-type", type=int, default=0, choices=[0, 1],
                              help="Chorus waveform: 0=sine, 1=triangle (default: 0)")
    return parser


def main() -> None:
    logging.basicConfig(
        level=logging.INFO, format="%(levelname)s: %(message)s", stream=sys.stderr
    )

    parser = build_parser()
    args = parser.parse_args()

    if args.list:
        list_midi_ports()
        sys.exit(0)

    if not args.soundfont.is_file():
        logger.error("Soundfont not found: %s", args.soundfont)
        sys.exit(1)

    reverb = None if args.no_reverb else ReverbConfig(
        roomsize=args.reverb_room, damping=args.reverb_damp,
        width=args.reverb_width, level=args.reverb_level,
    )
    chorus = None if args.no_chorus else ChorusConfig(
        nr=args.chorus_voices, level=args.chorus_level,
        speed=args.chorus_speed, depth=args.chorus_depth,
        type=args.chorus_type,
    )

    run(
        args.soundfont, midi_port=args.port, audio_driver=args.driver,
        device=args.device, gain=args.gain, buffer_size=args.buffer,
        reverb=reverb, chorus=chorus, test_tone=args.test_tone,
    )


if __name__ == "__main__":
    main()
