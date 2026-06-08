"""Unit tests for pyano's pure logic and CLI parser.

These tests must run without a real audio/MIDI backend. `pyano` imports the
heavy native libraries (fluidsynth, mido) lazily inside functions, so importing
the module here does not require those libraries to be installed.
"""

from __future__ import annotations

from pathlib import Path

import pytest

import pyano

# --- find_midi_port -------------------------------------------------------

def test_find_midi_port_empty_returns_none() -> None:
    assert pyano.find_midi_port([], None) is None
    assert pyano.find_midi_port([], "anything") is None


def test_find_midi_port_index_parse() -> None:
    ports = ["Port A", "Port B", "Port C"]
    assert pyano.find_midi_port(ports, "0") == "Port A"
    assert pyano.find_midi_port(ports, "2") == "Port C"


def test_find_midi_port_index_out_of_range_falls_back_to_substring() -> None:
    ports = ["Keyboard 5 MIDI"]
    # "5" is not a valid index but is a substring of the only port name.
    assert pyano.find_midi_port(ports, "5") == "Keyboard 5 MIDI"


def test_find_midi_port_substring_match_case_insensitive() -> None:
    ports = ["Midi Through Port-0", "Arturia MiniFuse 2 MIDI 1"]
    assert pyano.find_midi_port(ports, "arturia") == "Arturia MiniFuse 2 MIDI 1"
    assert pyano.find_midi_port(ports, "MINIFUSE") == "Arturia MiniFuse 2 MIDI 1"


def test_find_midi_port_no_match_returns_none() -> None:
    ports = ["Arturia MiniFuse 2 MIDI 1"]
    assert pyano.find_midi_port(ports, "nonexistent") is None


def test_find_midi_port_autoselect_prefers_non_through() -> None:
    ports = ["Midi Through Port-0", "Arturia MiniFuse 2 MIDI 1"]
    assert pyano.find_midi_port(ports, None) == "Arturia MiniFuse 2 MIDI 1"


def test_find_midi_port_autoselect_falls_back_to_first_when_all_through() -> None:
    ports = ["Midi Through Port-0", "Midi Through Port-1"]
    assert pyano.find_midi_port(ports, None) == "Midi Through Port-0"


# --- parse_aplay_card -----------------------------------------------------

def test_parse_aplay_card_match() -> None:
    line = "card 2: MiniFuse2 [Arturia MiniFuse 2], device 0: USB Audio [USB Audio]"
    assert pyano.parse_aplay_card(line, "arturia") == "plughw:2,0"


def test_parse_aplay_card_case_insensitive_pattern() -> None:
    line = "card 0: PCH [HDA Intel PCH], device 0: ALC ..."
    assert pyano.parse_aplay_card(line, "INTEL") == "plughw:0,0"


def test_parse_aplay_card_no_pattern_match() -> None:
    line = "card 0: PCH [HDA Intel PCH], device 0: ALC ..."
    assert pyano.parse_aplay_card(line, "arturia") is None


def test_parse_aplay_card_pattern_present_but_no_card_prefix() -> None:
    line = "  Subdevices: 1/1 arturia"
    assert pyano.parse_aplay_card(line, "arturia") is None


# --- argparse smoke -------------------------------------------------------

def test_parser_builds() -> None:
    parser = pyano.build_parser()
    args = parser.parse_args(["my.sf2"])
    assert args.soundfont == Path("my.sf2")
    assert args.driver == "alsa"
    assert args.test_tone is False


def test_parser_rejects_bad_driver() -> None:
    parser = pyano.build_parser()
    with pytest.raises(SystemExit):
        parser.parse_args(["my.sf2", "--driver", "bogus"])


def test_parser_version(capsys: pytest.CaptureFixture[str]) -> None:
    parser = pyano.build_parser()
    with pytest.raises(SystemExit) as exc:
        parser.parse_args(["--version"])
    assert exc.value.code == 0
    out = capsys.readouterr().out
    assert pyano.__version__ in out


def test_parser_soundfont_is_path() -> None:
    parser = pyano.build_parser()
    args = parser.parse_args(["/tmp/grand.sf2", "--test-tone"])
    assert isinstance(args.soundfont, Path)
    assert args.test_tone is True
