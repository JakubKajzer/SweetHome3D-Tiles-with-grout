# Tiles with grout – a Sweet Home 3D plugin

A plugin for [Sweet Home 3D](https://www.sweethome3d.com/) 7.x that turns a plain tile texture
into a realistic tiled surface: it adds **grout** (width and color), supports **running bond**
(brick) layouts with an adjustable row shift, a **diagonal (45°, "karo")** layout, and a
**start offset**. Grout can be removed again with one click.

Instead of making you pick a texture inside the plugin, it works on what you already did in
Sweet Home 3D: apply a tile texture to a wall side or a floor the normal way, select it, and run
the plugin. The texture's native size (width/height in cm, including its scale) is used as the
tile size.

## Features

- Grout width in **millimetres** and grout color.
- Tile size taken from the applied texture, or a custom size.
- **Running bond**: shift every row by any percentage of the tile width (50% = classic brick,
  other values such as 33% work too).
- **Diagonal layout** (45°) for square tiles and for rectangular tiles with a simple aspect ratio
  (1:1, 1:2, 2:3, … within 1%).
- Start offset X / Y in cm.
- Straight layouts with horizontal or vertical rows.
- Works on wall sides (left / right) and room floors.
- Only surfaces that have a **texture** are changed; surfaces with just a color are left alone.
- **Remove grout** restores the original texture.
- The dialog **remembers the last used values**, also between sessions.

## How it works

The plugin generates a new, seamlessly repeating texture image from your tile: one tile wide and
as many rows tall as the brick pattern needs (or a square period for the diagonal layout). The
generated texture is given a real-world size in cm, so tiles keep their true scale on the wall.
Grout is the background of the generated image; each tile sits in the middle of its cell, so
grout is exactly the configured width between tiles and is never doubled when the texture repeats.

The original tile image, its size and its name are embedded in the generated PNG (`tEXt`
chunks). They are saved together with your `.sh3d` file, so after reopening a project you can
change the grout again without it stacking on top of the old grout, and **Remove grout** can
bring back the original texture.

## Requirements

- Sweet Home 3D 7.0 or newer (developed and tested with 7.5).
- To build: a JDK (any version that supports `--release 8`, for example 17 or 25).
  The Java runtime bundled with Sweet Home 3D is Java 8, so the plugin has to be compiled for
  Java 8. A newer JDK prints a deprecation warning for `--release 8`; that is harmless.

## Build

Project layout:

```
tiles-plugin/
  build.sh
  src/TilesPlugin.java
  src/ApplicationPlugin.properties
```

Find your `SweetHome3D.jar` (it is in the `lib` folder of the installation), then:

```
chmod +x build.sh
./build.sh /path/to/SweetHome3D.jar
```

This produces `TilesPlugin.sh3p`.

## Install

Close Sweet Home 3D, then copy `TilesPlugin.sh3p` into the plugins folder and start the program
again:

| System  | Plugins folder (typical)                                          |
|---------|-------------------------------------------------------------------|
| Linux   | `~/.eteks/sweethome3d/plugins/`                                   |
| Windows | `%APPDATA%\eteks\Sweet Home 3D\plugins\`                          |
| macOS   | `~/Library/Application Support/eteks/sweethome3d/plugins/`        |

Alternatively, double-click the `.sh3p` file and let Sweet Home 3D install it.

## Usage

1. Apply a tile texture to a wall side or a floor the usual way (modify walls / rooms →
   texture). Use an image of **one** tile.
2. Select the walls and/or rooms.
3. Open the **Tiles** submenu and choose **Tiles with grout...**
4. Set the options and press OK.

To undo the effect, select the surfaces and choose **Tiles → Remove grout**.

### Options

| Option | Meaning |
|--------|---------|
| Custom tile size | Use the width/height below instead of the texture's native size. |
| Tile width / height (cm) | Tile size without grout. |
| Grout width (mm) | Width of the gap between tiles. |
| Grout color | Color of the gap. |
| Brick row shift (%) | Shift of every next row as a percentage of the tile width. 0 = straight grid. Ignored for the diagonal layout. |
| Start offset X / Y (cm) | Moves the start of the pattern. |
| Layout | Straight with horizontal rows, straight with vertical rows, or diagonal (45°). |
| Walls: left / right side, Rooms: floor | Which surfaces of the selection are changed. |

A tile size of 60 × 60 cm with 3 mm grout gives a pattern pitch of 60.3 cm. If you want 60 cm
to be the pitch including the grout, use a custom tile size of 59.7 cm.

## Limitations

- The original offset, scale and rotation from the texture dialog are replaced: scale becomes the
  tile size, and the offset is set again in the plugin. After **Remove grout** in a later session
  the original texture is rebuilt from the embedded image and size, without its old angle and
  offset (within the same session the exact original texture object is restored).
- Textures generated by versions of the plugin that did not embed the original tile cannot be
  recovered. Apply the original texture again before using the plugin.
- Diagonal layout needs a tile aspect ratio close to 1:1, 1:2, 2:3, … (within 1%).
- No brick shift in the diagonal layout.
- Changes made by the plugin are not part of Sweet Home 3D's undo history.
- Ceilings are not handled.

## Settings

Dialog values are stored with `java.util.prefs` (on Linux in `~/.java/.userPrefs/`).

## Troubleshooting

- **The plugin does not appear in the menu.** Start Sweet Home 3D from a terminal and read the
  output. A message like *"compiled by a more recent version of the Java Runtime"* means the
  plugin was built for a newer Java than the bundled runtime; rebuild with `--release 8`.
- **`cannot find symbol` when building.** The plugin API differs between Sweet Home 3D versions;
  build against the `SweetHome3D.jar` of the version you actually run.
- **Grout grows each time I apply it.** The surface is using a texture generated by an older
  version of the plugin. Apply the original texture again and run the plugin once more.

## License

GPL, as declared in `ApplicationPlugin.properties`.
