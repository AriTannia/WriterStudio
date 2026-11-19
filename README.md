# Writer's Studio

A Scrivener-inspired long-form writing and research application built with Java Swing.

## Features

- **Project panel** - A Draft / Research / Trash tree. Create documents and folders,
  rename items with F2, drag items to reorganize them, move items up or down,
  and permanently delete items from Trash.
- **Editor** - Rich-text editing with undo/redo, bold, italic, underline,
  alignment, font, font size, and clipboard controls. Formatting controls follow
  the caret. JetBrains Sans and JetBrains Mono are available when installed.
- **Research Editor** - Research and Character cards use a structured form
  instead of the free-form document editor. Cards support Title, Content,
  Images with captions, Linked Cards, Character Class, custom roles,
  Relationships, and Optional Fields.
- **Three-Act Structure cards** - A fixed story-planning layout with Summary
  and the 15 beats of Act I, Act II, and Act III, including percentage ranges
  and guidance for each beat.
- **Novel spacing** - Paragraphs use novel-style spacing: first-line indentation,
  compact lines within paragraphs, and visible spacing between paragraphs.
- **Combined Draft** - Select a folder or multiple documents to edit them as one
  continuous view. Changes are saved back to the original documents.
  Three-Act Structure cards can also be viewed in a structured Combined Draft
  preview showing their title, summary, acts, and beats.
- **Corkboard** - Editable index cards with title and synopsis. Select a card
  and use the fixed ordering controls to move it to the top, up, down, or
  bottom. This avoids unreliable drag-and-drop reflow. Corkboard cards support
  zoom from 70% to 160%.
- **Outliner** - A directly editable table containing Title, Synopsis, Label,
  Status, Words, and Compile information, including child documents.
- **Research** - General-purpose research templates: Note, Character, Location,
  Organization, Event, Reference, and Three-Act Structure. Character cards
  support portraits, Main/Side/Supporting Character classes, multiple custom
  roles, relationships, and optional fields.
- **Research sections** - Collapsible sections, a horizontal image gallery,
  captions below images, linked-card trees, relationship management, and
  inline optional fields. Double-click an image to open a resizable viewer.
- **Project-owned images** - Images selected from the local computer are copied
  into `files/research-images/`. They do not become Binder nodes, and unused
  copies are removed when they are no longer referenced by any card.
- **Primary Character image** - The first image added to a Character becomes
  primary automatically. The primary image can be changed through the
  Character image picker and is displayed in the Relationship graph.
- **Relationship graph** - A Relationships folder displays Character cards as
  image-backed graph nodes. The graph shows primary images, Character Class,
  custom roles, relationship labels, and supports double-click navigation.
  The graph includes zoom controls from 70% to 160%.
- **Sidebar controls** - Toggle Project and Details independently, or use Focus
  to hide both panels. The same controls are available from the View menu.
- **Export Draft** - Export the complete Draft as Markdown or plain text.
- **DOCX / PDF export** - Select folders or individual Draft cards and choose
  which child cards to include.
- **Automatic saving** - The project saves every 30 seconds and when the
  application closes.

## Running

Requires JDK/JRE 17 or newer.

```text
java -jar WriterStudio.jar
java -jar WriterStudio.jar <project-folder>
```

The first command opens the most recent project or the welcome dialog.

## Building

Windows:

```text
build.bat
```

Linux/macOS:

```text
./build.sh
```

## Project format

A project is an ordinary folder:

```text
MyNovel/
  project.xml              Binder structure and metadata
  data/<uuid>.txt          Document content (UTF-8)
  files/                   Imported project-owned files
  files/research-images/   Local copies of Research/Character images
```

## Keyboard shortcuts

```text
Ctrl/Cmd+N       New document
Ctrl/Cmd+Shift+N New folder
F2                Rename
Delete            Move Project selection to Trash
Ctrl/Cmd+1..4    Editor / Combined Draft / Corkboard / Outliner
Ctrl/Cmd+Alt+B   Toggle Project panel
Ctrl/Cmd+Alt+I   Toggle Details panel
Ctrl/Cmd+S       Save
Ctrl/Cmd+Shift+E Export Draft
```
