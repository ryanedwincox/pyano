<!-- Concern: explains editable score marks and offline behavior | Non-concern: PDF transcription | IO: none -->
# Pen marks on scores

Open a downloaded score and turn **Pen** on. Draw with a finger or stylus.
Turn Pen off to zoom, pan, or swipe between pages; the page navigation buttons
remain available. **Undo** removes the most recently stored visible stroke on
the current page. Marks are black and follow the PDF when zooming or panning.

Completed strokes save automatically on the tablet, including offline. The Cloud
icon shows save/sync status. Tap it for details and **Retry** after
failed Cloud sync. Local save failures open the details automatically. Free storage and retry
before leaving the page; unsaved gestures cannot survive application shutdown.

Cloud permissions require scores:read and scores:annotate (or scores:manage).
Marks are private to the score owner and sync between devices. Background work
waits for network access and retries transient failures. Opening the page also
refreshes Cloud changes. Undo actions merge without resurrecting removed marks.
Removing a local PDF copy does not remove its annotations; deleting a score in
Cloud removes its Cloud marks, while existing local marks remain on the tablet.

The reader toolbar places the pen and undo icons after the zoom controls.
Pen marks remain editable and the original PDF stays unchanged.

Marks are tied to the PDF hash and score ID. A rename retains marks; a different
PDF never receives marks positioned for the old document. Pages support up to
500 Cloud strokes including undone strokes; individual paths have up to 256 points.
If offline edits meet a full Cloud page, the unsynced marks stay local. Retry
cannot lift the Cloud page limit. Incoming Cloud marks still appear; the local store permits up to 1,000 entries to preserve conflicting edits.
