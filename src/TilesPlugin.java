import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import com.eteks.sweethome3d.model.Camera;
import com.eteks.sweethome3d.model.CatalogTexture;
import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.Room;
import com.eteks.sweethome3d.model.Selectable;
import com.eteks.sweethome3d.model.Wall;
import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;
import com.eteks.sweethome3d.tools.URLContent;

/**
 * Sweet Home 3D 7.x plugin.
 *
 * Takes the texture applied to a wall side or floor (an image of a single tile), uses its
 * native size (width/height in cm, including scale) and builds a new texture from it with
 * grout (color, width), running bond (brick offset in % of tile width), a diagonal (45 degrees,
 * "karo") layout, also with a brick shift for square tiles, and a start offset.
 * Only surfaces that have a texture are changed; plain colors are left alone.
 *
 * The original tile image is embedded in the generated PNG (tEXt chunks), so it survives
 * saving the home and restarting the application. "Remove grout" restores the original texture.
 */
public class TilesPlugin extends Plugin {

  private static final String KEY_SRC = "tp-src";
  private static final String KEY_W = "tp-w";
  private static final String KEY_H = "tp-h";
  private static final String KEY_NAME = "tp-name";

  // What the plugin changes (one at a time)
  private static final int T_LEFT = 0;
  private static final int T_RIGHT = 1;
  private static final int T_NEAREST = 2;
  private static final int T_FLOOR = 3;

  private final Preferences prefs = Preferences.userRoot().node("sh3d-tiles-plugin");

  /** Texture instance -> where it comes from (plain texture, or generated one with its original). */
  private final Map<HomeTexture, Origin> origins = new IdentityHashMap<HomeTexture, Origin>();

  @Override
  public PluginAction [] getActions() {
    return new PluginAction [] {new TilesAction(), new RemoveGroutAction()};
  }

  public class TilesAction extends PluginAction {
    public TilesAction() {
      putPropertyValue(Property.NAME, "Tiles with grout...");
      putPropertyValue(Property.MENU, "Tiles");
      putPropertyValue(Property.ENABLED, Boolean.TRUE);
    }

    @Override
    public void execute() {
      showDialog();
    }
  }

  public class RemoveGroutAction extends PluginAction {
    public RemoveGroutAction() {
      putPropertyValue(Property.NAME, "Remove grout");
      putPropertyValue(Property.MENU, "Tiles");
      putPropertyValue(Property.ENABLED, Boolean.TRUE);
    }

    @Override
    public void execute() {
      removeGrout();
    }
  }

  /** One textured surface: a wall side or a floor. */
  private abstract static class Surface {
    final String kind;  // "L" (wall left side), "R" (wall right side), "F" (room floor)
    final Wall wall;    // the wall for "L" / "R", else null
    Surface(String kind, Wall wall) {
      this.kind = kind;
      this.wall = wall;
    }
    abstract HomeTexture get();
    abstract void set(HomeTexture texture);
  }

  /** The original tile a texture was made from. */
  private static class Origin {
    HomeTexture exact;   // original texture object, if still known in this session
    byte [] bytes;       // original image file bytes (loaded lazily)
    double wCm;          // effective tile width in cm (texture width * scale)
    double hCm;
    String name;
    boolean generated;   // true if the texture was made by this plugin
  }

  // ---------------------------------------------------------------- helpers

  private static JSpinner spinner(double value, double min, double max, double step) {
    JSpinner s = new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, value)), min, max, step));
    JComponent editor = s.getEditor();
    if (editor instanceof JSpinner.DefaultEditor) {
      ((JSpinner.DefaultEditor)editor).getTextField().setColumns(6); // narrow field
    }
    return s;
  }

  private static double val(JSpinner s) {
    return ((Number)s.getValue()).doubleValue();
  }

  private static JButton colorButton(final Color [] holder) {
    final JButton b = new JButton("      ");
    b.setBackground(holder[0]);
    b.setOpaque(true);
    b.addActionListener(e -> {
      Color c = JColorChooser.showDialog(b, "Choose color", holder[0]);
      if (c != null) {
        holder[0] = c;
        b.setBackground(c);
      }
    });
    return b;
  }

  /** Adds "label  component" as one row of a GridBagLayout panel; the component keeps its own width. */
  private static void addRow(JPanel p, int row, String label, JComponent comp) {
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = row;
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(2, 0, 2, 14);
    p.add(new JLabel(label), c);
    c = new GridBagConstraints();
    c.gridx = 1;
    c.gridy = row;
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(2, 0, 2, 0);
    p.add(comp, c);
  }

  /** Adds a component spanning both columns. */
  private static void addWide(JPanel p, int row, JComponent comp) {
    GridBagConstraints c = new GridBagConstraints();
    c.gridx = 0;
    c.gridy = row;
    c.gridwidth = 2;
    c.anchor = GridBagConstraints.WEST;
    c.insets = new Insets(2, 0, 2, 0);
    p.add(comp, c);
  }

  /** Texture scale (HomeTexture.getScale) read by reflection so the code compiles on older APIs too. */
  private static float scaleOf(HomeTexture t) {
    try {
      Object o = t.getClass().getMethod("getScale").invoke(t);
      if (o instanceof Number) {
        float s = ((Number)o).floatValue();
        return s > 0 ? s : 1f;
      }
    } catch (Exception ex) {
      // no such method - scale 1
    }
    return 1f;
  }

  private static byte [] readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte [] buf = new byte [8192];
    int n;
    while ((n = in.read(buf)) != -1) {
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  private Component parentComponent() {
    Object view = getHomeController().getView();
    return view instanceof JComponent ? (JComponent)view : null;
  }

  private List<Surface> collectSurfaces() {
    List<Surface> surfaces = new ArrayList<Surface>();
    for (Selectable s : getHome().getSelectedItems()) {
      if (s instanceof Wall) {
        final Wall w = (Wall)s;
        surfaces.add(new Surface("L", w) {
          HomeTexture get() { return w.getLeftSideTexture(); }
          void set(HomeTexture t) { w.setLeftSideTexture(t); }
        });
        surfaces.add(new Surface("R", w) {
          HomeTexture get() { return w.getRightSideTexture(); }
          void set(HomeTexture t) { w.setRightSideTexture(t); }
        });
      } else if (s instanceof Room) {
        final Room r = (Room)s;
        surfaces.add(new Surface("F", null) {
          HomeTexture get() { return r.getFloorTexture(); }
          void set(HomeTexture t) { r.setFloorTexture(t); }
        });
      }
    }
    return surfaces;
  }

  /**
   * Which side of the wall ("L" or "R") is nearer to the viewer, judged by the current camera
   * position in the plan. "Left" is the left side when going from the wall start to its end.
   */
  private String sideNearestToViewer(Wall w) {
    Camera camera = getHome().getCamera();
    double dx = w.getXEnd() - w.getXStart();
    double dy = w.getYEnd() - w.getYStart();
    double cross = dx * (camera.getY() - w.getYStart()) - dy * (camera.getX() - w.getXStart());
    return cross < 0 ? "L" : "R";
  }

  private boolean matchesTarget(Surface sf, int target) {
    switch (target) {
      case T_LEFT:
        return "L".equals(sf.kind);
      case T_RIGHT:
        return "R".equals(sf.kind);
      case T_NEAREST:
        return sf.wall != null && sf.kind.equals(sideNearestToViewer(sf.wall));
      case T_FLOOR:
        return "F".equals(sf.kind);
      default:
        return false;
    }
  }

  // ---------------------------------------------------------------- origin tracking

  /** Finds the origin of a texture: embedded in its PNG, known from this session, or the texture itself. */
  private Origin resolve(HomeTexture t) {
    if (t == null) {
      return null;
    }
    Origin o = origins.get(t);
    if (o != null) {
      return o;
    }
    o = readEmbeddedOrigin(t);
    if (o == null) {
      o = new Origin();
      o.exact = t;
      o.wCm = t.getWidth() * scaleOf(t);
      o.hCm = t.getHeight() * scaleOf(t);
      o.name = t.getName();
      o.generated = false;
    }
    origins.put(t, o);
    return o;
  }

  private static byte [] bytesOf(Origin o) throws IOException {
    if (o.bytes == null && o.exact != null) {
      InputStream in = o.exact.getImage().openStream();
      try {
        o.bytes = readAll(in);
      } finally {
        in.close();
      }
    }
    if (o.bytes == null) {
      throw new IOException("Original tile image not available");
    }
    return o.bytes;
  }

  /** Reads the tEXt chunks written by this plugin; returns null for textures not made by it. */
  private static Origin readEmbeddedOrigin(HomeTexture t) {
    InputStream in = null;
    ImageInputStream iis = null;
    ImageReader reader = null;
    try {
      in = t.getImage().openStream();
      iis = ImageIO.createImageInputStream(in);
      Iterator<ImageReader> it = ImageIO.getImageReaders(iis);
      if (!it.hasNext()) {
        return null;
      }
      reader = it.next();
      reader.setInput(iis, true);
      IIOMetadata md = reader.getImageMetadata(0);
      Node root = md.getAsTree("javax_imageio_png_1.0");
      Map<String, String> text = new HashMap<String, String>();
      for (Node n = root.getFirstChild(); n != null; n = n.getNextSibling()) {
        if ("tEXt".equals(n.getNodeName())) {
          for (Node e = n.getFirstChild(); e != null; e = e.getNextSibling()) {
            NamedNodeMap a = e.getAttributes();
            text.put(a.getNamedItem("keyword").getNodeValue(), a.getNamedItem("value").getNodeValue());
          }
        }
      }
      if (!text.containsKey(KEY_SRC)) {
        return null;
      }
      Origin o = new Origin();
      o.bytes = Base64.getDecoder().decode(text.get(KEY_SRC));
      o.wCm = Double.parseDouble(text.get(KEY_W));
      o.hCm = Double.parseDouble(text.get(KEY_H));
      o.name = text.containsKey(KEY_NAME) ? text.get(KEY_NAME) : "Tiles";
      o.generated = true;
      return o;
    } catch (Exception ex) {
      return null; // not a PNG with our metadata
    } finally {
      try {
        if (reader != null) {
          reader.dispose();
        }
        if (iis != null) {
          iis.close();
        }
        if (in != null) {
          in.close();
        }
      } catch (IOException ex) {
        // ignore
      }
    }
  }

  private static void writePng(BufferedImage image, File file, Map<String, String> text) throws IOException {
    ImageWriter writer = ImageIO.getImageWritersByFormatName("png").next();
    ImageWriteParam param = writer.getDefaultWriteParam();
    IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
    IIOMetadataNode textNode = new IIOMetadataNode("tEXt");
    for (Map.Entry<String, String> e : text.entrySet()) {
      IIOMetadataNode entry = new IIOMetadataNode("tEXtEntry");
      entry.setAttribute("keyword", e.getKey());
      entry.setAttribute("value", e.getValue());
      textNode.appendChild(entry);
    }
    IIOMetadataNode root = new IIOMetadataNode("javax_imageio_png_1.0");
    root.appendChild(textNode);
    meta.mergeTree("javax_imageio_png_1.0", root);
    ImageOutputStream ios = ImageIO.createImageOutputStream(file);
    try {
      writer.setOutput(ios);
      writer.write(null, new IIOImage(image, null, meta), param);
    } finally {
      ios.close();
      writer.dispose();
    }
  }

  // ---------------------------------------------------------------- Tiles with grout...

  private void showDialog() {
    Component parent = parentComponent();

    List<Surface> all = collectSurfaces();
    Surface firstTextured = null;
    for (Surface sf : all) {
      if (sf.get() != null) {
        firstTextured = sf;
        break;
      }
    }
    if (firstTextured == null) {
      JOptionPane.showMessageDialog(parent,
          "Select walls or rooms that have a texture applied (surfaces with only a color are ignored).");
      return;
    }

    // Default tile size = native size of the first texture found
    Origin first = resolve(firstTextured.get());
    double defW = Math.round(first.wCm * 10) / 10.0;
    double defH = Math.round(first.hCm * 10) / 10.0;

    // Remembered values from the previous run
    boolean customSizeInit = prefs.getBoolean("customSize", false);
    double tileWInit = customSizeInit ? prefs.getDouble("tileW", defW) : defW;
    double tileHInit = customSizeInit ? prefs.getDouble("tileH", defH) : defH;

    JCheckBox customSize = new JCheckBox("Custom tile size (instead of texture size)", customSizeInit);
    JSpinner tileW = spinner(tileWInit, 1, 500, 0.5);
    JSpinner tileH = spinner(tileHInit, 1, 500, 0.5);
    JSpinner grout = spinner(prefs.getDouble("groutMm", 3), 0, 50, 0.5);
    JSpinner brick = spinner(prefs.getDouble("brickPct", 0), 0, 99, 5);
    JSpinner offX = spinner(prefs.getDouble("offX", 0), -1000, 1000, 1);
    JSpinner offY = spinner(prefs.getDouble("offY", 0), -1000, 1000, 1);
    JComboBox<String> layoutBox = new JComboBox<String>(new String [] {
        "Straight, horizontal rows", "Straight, vertical rows", "Diagonal (45°, karo)"});
    layoutBox.setSelectedIndex(Math.max(0, Math.min(2, prefs.getInt("layoutRot", 0))));

    final Color [] groutHolder = {new Color(prefs.getInt("groutColor", 0x9A9A9A))};
    JButton groutBtn = colorButton(groutHolder);

    int targetInit = Math.max(0, Math.min(3, prefs.getInt("target", T_NEAREST)));
    JRadioButton [] targetButtons = {
        new JRadioButton("Wall: left side"),
        new JRadioButton("Wall: right side"),
        new JRadioButton("Wall: side nearest to the viewer (camera)"),
        new JRadioButton("Room: floor")};
    ButtonGroup group = new ButtonGroup();
    for (JRadioButton rb : targetButtons) {
      group.add(rb);
    }
    targetButtons[targetInit].setSelected(true);

    JPanel p = new JPanel(new GridBagLayout());
    int row = 0;
    addWide(p, row++, customSize);
    addRow(p, row++, "Tile width (cm)", tileW);
    addRow(p, row++, "Tile height (cm)", tileH);
    addRow(p, row++, "Grout width (mm)", grout);
    addRow(p, row++, "Grout color", groutBtn);
    addRow(p, row++, "Brick row shift (% of tile, 0 = none)", brick);
    addRow(p, row++, "Start offset X (cm)", offX);
    addRow(p, row++, "Start offset Y (cm)", offY);
    addRow(p, row++, "Layout", layoutBox);
    addWide(p, row++, new JLabel("Change (one at a time):"));
    for (JRadioButton rb : targetButtons) {
      addWide(p, row++, rb);
    }

    int res = JOptionPane.showConfirmDialog(parent, p, "Tiles with grout",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (res != JOptionPane.OK_OPTION) {
      return;
    }

    int target = T_NEAREST;
    for (int i = 0; i < targetButtons.length; i++) {
      if (targetButtons[i].isSelected()) {
        target = i;
      }
    }

    // Remember the values
    prefs.putBoolean("customSize", customSize.isSelected());
    prefs.putDouble("tileW", val(tileW));
    prefs.putDouble("tileH", val(tileH));
    prefs.putDouble("groutMm", val(grout));
    prefs.putDouble("brickPct", val(brick));
    prefs.putDouble("offX", val(offX));
    prefs.putDouble("offY", val(offY));
    prefs.putInt("layoutRot", layoutBox.getSelectedIndex());
    prefs.putInt("groutColor", groutHolder[0].getRGB() & 0xFFFFFF);
    prefs.putInt("target", target);

    // Surfaces to change: textured ones matching the chosen target
    List<Surface> targets = new ArrayList<Surface>();
    for (Surface sf : all) {
      if (sf.get() != null && matchesTarget(sf, target)) {
        targets.add(sf);
      }
    }
    if (targets.isEmpty()) {
      JOptionPane.showMessageDialog(parent,
          "Nothing to change: no textured surface in the selection matches the chosen target\n"
          + "(surfaces with only a color are skipped).");
      return;
    }

    Color groutColor = groutHolder[0];
    double groutCm = val(grout) / 10.0;
    double brickFrac = val(brick) / 100.0;
    final int layout = layoutBox.getSelectedIndex(); // 0 horizontal, 1 vertical, 2 diagonal
    boolean rotateLayout = layout == 1;

    // Diagonal layout with a brick shift: square tiles and a shift that repeats seamlessly
    int brickR = 0;
    int brickS = 0;
    if (layout == 2 && brickFrac > 0.001) {
      int [] rs = diagonalBrick(brickFrac);
      if (rs == null) {
        JOptionPane.showMessageDialog(parent,
            "A brick shift of " + (int)Math.round(val(brick)) + "% cannot repeat seamlessly in the diagonal layout.\n"
            + "Use a shift such as 50, 33, 25, 20, 17, 14 or 12.5 (or 0 for no shift).");
        return;
      }
      brickR = rs[0];
      brickS = rs[1];
      for (Surface sf : targets) {
        Origin o = resolve(sf.get());
        double tw = customSize.isSelected() ? val(tileW) : o.wCm;
        double th = customSize.isSelected() ? val(tileH) : o.hCm;
        if (Math.abs(tw - th) > 0.01 * Math.max(tw, th)) {
          JOptionPane.showMessageDialog(parent,
              "The diagonal layout with a brick shift needs square tiles.\n"
              + "This tile is " + Math.round(tw * 10) / 10.0 + " x " + Math.round(th * 10) / 10.0 + " cm.\n"
              + "Use a square tile (or a custom square size), or set the brick shift to 0.");
          return;
        }
      }
    }

    String suffix = " grout " + val(grout) + "mm";
    if (layout == 2) {
      suffix += " diagonal" + (brickS > 0 ? " brick " + brickR + "/" + brickS : "");
    } else if (brickFrac > 0) {
      suffix += " brick " + (int)Math.round(val(brick)) + "%";
    }

    Map<Origin, HomeTexture> cache = new IdentityHashMap<Origin, HomeTexture>();
    int count = 0;
    try {
      for (Surface sf : targets) {
        Origin origin = resolve(sf.get());
        HomeTexture result = cache.get(origin);
        if (result == null) {
          double tw = customSize.isSelected() ? val(tileW) : origin.wCm;
          double th = customSize.isSelected() ? val(tileH) : origin.hCm;
          byte [] srcBytes = bytesOf(origin);
          BufferedImage tileImg = ImageIO.read(new ByteArrayInputStream(srcBytes));
          if (tileImg == null) {
            throw new IOException("Unsupported tile image format");
          }
          double [] sizeCm = new double [2];
          BufferedImage image;
          if (layout == 2) {
            image = buildDiagonalTexture(tileImg, tw, th, groutCm, groutColor,
                brickR, brickS, val(offX), val(offY), sizeCm);
          } else {
            image = buildTexture(tileImg, tw, th, groutCm, groutColor,
                brickFrac, val(offX), val(offY), rotateLayout, sizeCm);
          }

          Map<String, String> text = new LinkedHashMap<String, String>();
          text.put(KEY_W, Double.toString(origin.wCm));
          text.put(KEY_H, Double.toString(origin.hCm));
          text.put(KEY_NAME, origin.name != null ? origin.name : "Tiles");
          text.put(KEY_SRC, Base64.getEncoder().encodeToString(srcBytes));

          File f = File.createTempFile("tiles", ".png");
          f.deleteOnExit();
          writePng(image, f, text);
          Content content = new URLContent(f.toURI().toURL());
          String name = (origin.name != null ? origin.name : "Tiles") + suffix;
          CatalogTexture ct = new CatalogTexture(name, content, (float)sizeCm[0], (float)sizeCm[1]);
          result = new HomeTexture(ct);

          Origin generated = new Origin();
          generated.exact = origin.exact;
          generated.bytes = srcBytes;
          generated.wCm = origin.wCm;
          generated.hCm = origin.hCm;
          generated.name = origin.name;
          generated.generated = true;
          origins.put(result, generated);
          cache.put(origin, result);
        }
        sf.set(result);
        count++;
      }
    } catch (IllegalArgumentException ex) {
      JOptionPane.showMessageDialog(parent, ex.getMessage());
      return;
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(parent, "Error: " + ex);
      return;
    }
    if (count > 0) {
      getHome().setModified(true);
    }
  }

  // ---------------------------------------------------------------- Remove grout

  private void removeGrout() {
    Component parent = parentComponent();
    Map<Origin, HomeTexture> restoredCache = new IdentityHashMap<Origin, HomeTexture>();
    int count = 0;
    try {
      for (Surface sf : collectSurfaces()) {
        HomeTexture current = sf.get();
        Origin o = resolve(current);
        if (o == null || !o.generated) {
          continue; // no texture, or a texture not made by this plugin
        }
        HomeTexture restored = restoredCache.get(o);
        if (restored == null) {
          if (o.exact != null) {
            restored = o.exact; // exact original, with its own scale/angle
          } else {
            File f = File.createTempFile("tile-original", ".img");
            f.deleteOnExit();
            java.nio.file.Files.write(f.toPath(), bytesOf(o));
            Content content = new URLContent(f.toURI().toURL());
            String name = o.name != null ? o.name : "Tile";
            restored = new HomeTexture(new CatalogTexture(name, content, (float)o.wCm, (float)o.hCm));
          }
          restoredCache.put(o, restored);
        }
        sf.set(restored);
        count++;
      }
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(parent, "Error: " + ex);
      return;
    }
    if (count == 0) {
      JOptionPane.showMessageDialog(parent,
          "No textures made by this plugin found in the selection.");
    } else {
      getHome().setModified(true);
    }
  }

  // ---------------------------------------------------------------- texture generation

  /**
   * Finds r/s (2 <= s <= 8) close to the brick shift fraction, or null if there is none.
   * Only such shifts repeat seamlessly in the diagonal layout.
   */
  static int [] diagonalBrick(double fraction) {
    for (int s = 2; s <= 8; s++) {
      for (int r = 1; r < s; r++) {
        if (Math.abs(fraction - (double)r / s) <= 0.01) {
          return new int [] {r, s};
        }
      }
    }
    return null;
  }

  /**
   * Diagonal ("karo") layout: tiles rotated by 45 degrees.
   *
   * Without a brick shift (brickS == 0) the pattern repeats on an axis-aligned square of side
   * sqrt(2) * p * cw, where (tile height + grout) / (tile width + grout) = p / q for small
   * integers p, q (1:1, 1:2, 2:3 ... within 1%).
   *
   * With a brick shift of brickR / brickS of the tile width (square tiles only) every next row is
   * shifted along the row direction, and the pattern repeats on a square of side
   * sqrt(2) * brickS * cw.
   *
   * Rendered at 2x and downscaled for smooth edges.
   */
  static BufferedImage buildDiagonalTexture(BufferedImage tileImg,
                                            double tw, double th, double groutCm, Color groutC,
                                            int brickR, int brickS,
                                            double offXcm, double offYcm, double [] sizeCm) {
    double cw = tw + groutCm;
    double ch = th + groutCm;
    double f = 0;
    double ch2;   // cell height adjusted to the exact lattice
    int K;        // period = sqrt(2) * K * cw
    if (brickS > 0) {
      if (Math.abs(tw - th) > 0.01 * Math.max(tw, th)) {
        throw new IllegalArgumentException("The diagonal layout with a brick shift needs square tiles.");
      }
      ch2 = cw;
      f = (double)brickR / brickS;
      K = brickS;
    } else {
      int bestP = 1;
      int bestQ = 1;
      double bestErr = Double.MAX_VALUE;
      for (int p = 1; p <= 6; p++) {
        for (int q = 1; q <= 6; q++) {
          double err = Math.abs(ch / cw - (double)p / q) / (ch / cw);
          if (err < bestErr - 1e-9) {
            bestErr = err;
            bestP = p;
            bestQ = q;
          }
        }
      }
      if (bestErr > 0.01) {
        throw new IllegalArgumentException("The diagonal layout needs a tile aspect ratio close to "
            + "1:1, 1:2, 2:3... (within 1%).\nUse \"Custom tile size\" to adjust.");
      }
      ch2 = cw * bestP / bestQ;
      K = bestP;
    }
    double sqrt2 = Math.sqrt(2);
    double P = sqrt2 * K * cw;   // period in cm (square)

    double srcPpc = tileImg.getWidth() / tw;
    double ppc = Math.max(1.0, Math.min(Math.min(srcPpc, 1536.0 / P), 16.0));
    int S = Math.max(8, (int)Math.round(P * ppc));
    int HS = 2 * S;
    double ppcH = HS / P;

    BufferedImage hi = new BufferedImage(HS, HS, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = hi.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    g.setColor(groutC);
    g.fillRect(0, 0, HS, HS);

    // Tile centers (cm): i * a + j * b, with a = cw * (1, 1) / sqrt2 and
    // b = ((-ch2 + f * cw), (ch2 + f * cw)) / sqrt2  (row j shifted by j * f * cw along the row)
    double M = cw + ch2;
    double lo = -M;
    double hiCm = P + M;
    double [][] corners = {{lo, lo}, {lo, hiCm}, {hiCm, lo}, {hiCm, hiCm}};
    int i0 = Integer.MAX_VALUE;
    int i1 = Integer.MIN_VALUE;
    int j0 = Integer.MAX_VALUE;
    int j1 = Integer.MIN_VALUE;
    for (double [] pt : corners) {
      double X = sqrt2 * pt[0];
      double Y = sqrt2 * pt[1];
      double jj = (Y - X) / (2 * ch2);
      double ii = (X - jj * (-ch2 + f * cw)) / cw;
      i0 = Math.min(i0, (int)Math.floor(ii));
      i1 = Math.max(i1, (int)Math.ceil(ii));
      j0 = Math.min(j0, (int)Math.floor(jj));
      j1 = Math.max(j1, (int)Math.ceil(jj));
    }
    i0 -= 1;
    i1 += 1;
    j0 -= 1;
    j1 += 1;

    double sx = tw * ppcH / tileImg.getWidth();
    double sy = th * ppcH / tileImg.getHeight();
    double margin = M * ppcH;
    for (int i = i0; i <= i1; i++) {
      for (int j = j0; j <= j1; j++) {
        double cx = (i * cw + j * (-ch2 + f * cw)) / sqrt2 * ppcH;
        double cy = (i * cw + j * (ch2 + f * cw)) / sqrt2 * ppcH;
        if (cx < -margin || cx > HS + margin || cy < -margin || cy > HS + margin) {
          continue;
        }
        AffineTransform at = new AffineTransform();
        at.translate(cx, cy);
        at.rotate(Math.PI / 4);
        at.scale(sx, sy);
        at.translate(-tileImg.getWidth() / 2.0, -tileImg.getHeight() / 2.0);
        g.drawImage(tileImg, at, null);
      }
    }
    g.dispose();

    BufferedImage block = new BufferedImage(S, S, BufferedImage.TYPE_INT_RGB);
    Graphics2D bg = block.createGraphics();
    bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    bg.drawImage(hi, 0, 0, S, S, null);
    bg.dispose();

    // start offset with wrap-around
    int ox = (int)Math.round(offXcm * S / P);
    int oy = (int)Math.round(offYcm * S / P);
    ox = ((ox % S) + S) % S;
    oy = ((oy % S) + S) % S;
    BufferedImage result = block;
    if (ox != 0 || oy != 0) {
      result = new BufferedImage(S, S, BufferedImage.TYPE_INT_RGB);
      Graphics2D sg = result.createGraphics();
      for (int dx = 0; dx <= S; dx += S) {
        for (int dy = 0; dy <= S; dy += S) {
          sg.drawImage(block, dx - ox, dy - oy, null);
        }
      }
      sg.dispose();
    }
    sizeCm[0] = P;
    sizeCm[1] = P;
    return result;
  }

  /**
   * Builds one pattern period: 1 tile wide and m rows tall
   * (m chosen so the running bond pattern lines up when the texture repeats).
   * Returns the size in cm in sizeCm[0..1], so the scale in Sweet Home 3D is real.
   */
  static BufferedImage buildTexture(BufferedImage tileImg,
                                    double tw, double th, double groutCm, Color groutC,
                                    double brickFrac, double offXcm, double offYcm,
                                    boolean rotateLayout, double [] sizeCm) {
    double cw = tw + groutCm;
    double ch = th + groutCm;

    // number of rows in the pattern period
    int m = 1;
    if (brickFrac > 0.001 && brickFrac < 0.999) {
      m = 12;
      for (int k = 1; k <= 12; k++) {
        double v = k * brickFrac;
        if (Math.abs(v - Math.round(v)) < 0.01) {
          m = k;
          break;
        }
      }
    }

    double srcPpc = tileImg.getWidth() / tw;
    double ppc = Math.min(srcPpc, 4096.0 / Math.max(cw, m * ch));
    ppc = Math.max(1.0, Math.min(ppc, 16.0));

    int cellW = Math.max(2, (int)Math.round(cw * ppc));
    int cellH = Math.max(2, (int)Math.round(ch * ppc));
    int gPxX = groutCm <= 0 ? 0 : Math.max(1, (int)Math.round(groutCm * cellW / cw));
    int gPxY = groutCm <= 0 ? 0 : Math.max(1, (int)Math.round(groutCm * cellH / ch));
    int tilePxW = Math.max(1, cellW - gPxX);
    int tilePxH = Math.max(1, cellH - gPxY);
    int W = cellW;
    int H = m * cellH;

    BufferedImage tile = new BufferedImage(tilePxW, tilePxH, BufferedImage.TYPE_INT_RGB);
    Graphics2D tg = tile.createGraphics();
    tg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
    tg.drawImage(tileImg, 0, 0, tilePxW, tilePxH, null);
    tg.dispose();

    BufferedImage block = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
    Graphics2D g = block.createGraphics();
    g.setColor(groutC);
    g.fillRect(0, 0, W, H);
    for (int j = 0; j < m; j++) {
      double shift = (j * brickFrac) % 1.0;
      int sx = (int)Math.round(shift * cellW);
      int y = j * cellH + gPxY / 2;
      for (int dx = -cellW; dx <= 0; dx += cellW) {
        g.drawImage(tile, sx + dx + gPxX / 2, y, null);
      }
    }
    g.dispose();

    // start offset with wrap-around (the pattern repeats)
    int ox = (int)Math.round(offXcm * cellW / cw);
    int oy = (int)Math.round(offYcm * cellH / ch);
    ox = ((ox % W) + W) % W;
    oy = ((oy % H) + H) % H;
    BufferedImage shifted = block;
    if (ox != 0 || oy != 0) {
      shifted = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
      Graphics2D sg = shifted.createGraphics();
      for (int dx = 0; dx <= W; dx += W) {
        for (int dy = 0; dy <= H; dy += H) {
          sg.drawImage(block, dx - ox, dy - oy, null);
        }
      }
      sg.dispose();
    }

    double widthCm = cw;
    double heightCm = m * ch;
    if (rotateLayout) {
      BufferedImage rot = new BufferedImage(H, W, BufferedImage.TYPE_INT_RGB);
      Graphics2D rg = rot.createGraphics();
      AffineTransform at = new AffineTransform();
      at.translate(H, 0);
      at.rotate(Math.PI / 2);
      rg.drawImage(shifted, at, null);
      rg.dispose();
      shifted = rot;
      sizeCm[0] = heightCm;
      sizeCm[1] = widthCm;
    } else {
      sizeCm[0] = widthCm;
      sizeCm[1] = heightCm;
    }
    return shifted;
  }
}
