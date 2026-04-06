#!/usr/bin/env python3
"""Pyano - Low-latency MIDI-to-SoundFont piano synth."""

import argparse
import re
import signal
import sys
import time

import fluidsynth
import mido


def find_alsa_device(name_pattern="arturia"):
    """Find an ALSA playback device by name, returns e.g. 'plughw:2,0'."""
    try:
        import subprocess
        result = subprocess.run(["aplay", "-l"], capture_output=True, text=True)
        for line in result.stdout.splitlines():
            if name_pattern.lower() in line.lower():
                match = re.match(r"card (\d+):", line)
                if match:
                    card = match.group(1)
                    print(f"Found audio device: {line.strip()}")
                    return f"plughw:{card},0"
    except FileNotFoundError:
        pass
    return None


def list_midi_ports():
    """Print available MIDI input ports."""
    ports = mido.get_input_names()
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


def find_midi_port(requested):
    """Find a MIDI port by index or substring match."""
    ports = mido.get_input_names()
    if not ports:
        return None

    # Try as index
    if requested is not None:
        try:
            idx = int(requested)
            if 0 <= idx < len(ports):
                return ports[idx]
        except ValueError:
            pass

        # Try as substring match
        requested_lower = requested.lower()
        for port in ports:
            if requested_lower in port.lower():
                return port

        print(f"No MIDI port matching '{requested}'. Available:")
        for p in ports:
            print(f"  {p}")
        return None

    # Auto-select: prefer non-"Through" ports
    for port in ports:
        if "through" not in port.lower():
            return port
    return ports[0]


def run(sf_path, midi_port=None, audio_driver="alsa", device=None,
        gain=0.8, buffer_size=256, reverb=None, chorus=None):
    """Main synth loop."""
    # Auto-detect MiniFuse if no device specified
    if device is None and audio_driver in ("alsa", "pipewire"):
        device = find_alsa_device("arturia")
        if not device:
            print("Warning: MiniFuse not found, using default audio device")

    fs = fluidsynth.Synth(gain=gain)

    # FluidSynth audio settings for low latency
    fs.setting("audio.driver", audio_driver)
    fs.setting("audio.period-size", buffer_size)
    fs.setting("audio.periods", 2)

    if device:
        fs.setting("audio.alsa.device", device)

    fs.start()

    # Configure reverb
    if reverb is None:
        fs.set_reverb(roomsize=0.2, damping=0.0, width=0.5, level=0.9)
    elif reverb == "off":
        fs.set_reverb(level=0.0)
    else:
        fs.set_reverb(**reverb)

    # Configure chorus
    if chorus is None:
        fs.set_chorus(nr=3, level=2.0, speed=0.3, depth=8.0, type=0)
    elif chorus == "off":
        fs.set_chorus(level=0.0)
    else:
        fs.set_chorus(**chorus)

    # Load soundfont
    sfid = fs.sfload(sf_path)
    if sfid == -1:
        print(f"Error: Could not load soundfont: {sf_path}")
        fs.delete()
        sys.exit(1)

    fs.program_select(0, sfid, 0, 0)  # Channel 0, Bank 0, Preset 0 (Grand Piano)
    print(f"Loaded soundfont: {sf_path}")

    # Find MIDI port
    port_name = find_midi_port(midi_port)
    if not port_name:
        print("\nNo MIDI port available. Entering test mode (type note numbers).")
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
        fs.delete()
        return

    print(f"Listening on MIDI port: {port_name}")
    print("Press Ctrl+C to quit.")

    # Handle clean shutdown
    running = True

    def on_signal(sig, frame):
        nonlocal running
        running = False

    signal.signal(signal.SIGINT, on_signal)
    signal.signal(signal.SIGTERM, on_signal)

    try:
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
                    elif msg.type == "control_change":
                        # Ignore CC7 (volume) and CC11 (expression)
                        if msg.control not in (7, 11):
                            fs.cc(msg.channel, msg.control, msg.value)
                time.sleep(0.001)  # 1ms poll — keeps CPU low without adding latency
    except OSError as e:
        print(f"MIDI error: {e}")
    finally:
        fs.delete()
        print("\nDone.")


def main():
    parser = argparse.ArgumentParser(description="Pyano - MIDI SoundFont synth")
    parser.add_argument("soundfont", help="Path to .sf2 soundfont file")
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

    # Reverb controls
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

    # Chorus controls
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

    args = parser.parse_args()

    if args.list:
        list_midi_ports()
        sys.exit(0)

    # Build reverb/chorus config
    reverb = "off" if args.no_reverb else {
        "roomsize": args.reverb_room, "damping": args.reverb_damp,
        "width": args.reverb_width, "level": args.reverb_level,
    }
    chorus = "off" if args.no_chorus else {
        "nr": args.chorus_voices, "level": args.chorus_level,
        "speed": args.chorus_speed, "depth": args.chorus_depth,
        "type": args.chorus_type,
    }

    run(args.soundfont, midi_port=args.port, audio_driver=args.driver,
        device=args.device, gain=args.gain, buffer_size=args.buffer,
        reverb=reverb, chorus=chorus)


if __name__ == "__main__":
    main()
