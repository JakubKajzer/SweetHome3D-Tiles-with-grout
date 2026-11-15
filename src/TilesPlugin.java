import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;

import javax.imageio.ImageIO;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

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
 * Takes the texture previously applied to a wall side or floor (an image of a single tile),
 * uses its native size (width/height in cm, including scale) and builds a new texture from it
 * with grout (color, width), running bond (brick offset in % of tile width)
 * and a start offset (X/Y). Dialog values are remembered between runs and sessions.
 */
public class TilesPlugin extends Plugin {

  private final Preferences prefs = Preferences.userRoot().node("sh3d-tiles-plugin");

  /** Generated texture -> source texture (so running the plugin again does not nest grout). */
  private final Map<HomeTexture, HomeTexture> sources = new IdentityHashMap<HomeTexture, HomeTexture>();

  @Override
  public PluginAction [] getActions() {
    return new PluginAction [] {new TilesAction()};
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

  /** One textured surface: a wall side or a floor. */
  private abstract static class Surface {
    abstract HomeTexture get();
    abstract void set(HomeTexture texture);
  }

  private static JSpinner spinner(double value, double min, double max, double step) {
    return new JSpinner(new SpinnerNumberModel(Math.max(min, Math.min(max, value)), min, max, step));
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

  private HomeTexture sourceOf(HomeTexture current) {
    if (current == null) {
      return null;
    }
    HomeTexture src = sources.get(current);
    return src != null ? src : current;
  }

  private void showDialog() {
    // Collect surfaces from the selection (each wall side and floor separately)
    final List<Surface> allSurfaces = new ArrayList<Surface>();
    final List<String> kinds = new ArrayList<String>(); // "L", "R", "F"
    for (Selectable s : getHome().getSelectedItems()) {
      if (s instanceof Wall) {
        final Wall w = (Wall)s;
        allSurfaces.add(new Surface() {
          HomeTexture get() { return w.getLeftSideTexture(); }
          void set(HomeTexture t) { w.setLeftSideTexture(t); }
        });
        kinds.add("L");
        allSurfaces.add(new Surface() {
          HomeTexture get() { return w.getRightSideTexture(); }
          void set(HomeTexture t) { w.setRightSideTexture(t); }
        });
        kinds.add("R");
      } else if (s instanceof Room) {
        final Room r = (Room)s;
        allSurfaces.add(new Surface() {
          HomeTexture get() { return r.getFloorTexture(); }
          void set(HomeTexture t) { r.setFloorTexture(t); }
        });
        kinds.add("F");
      }
    }

    Component parent = null;
    Object view = getHomeController().getView();
    if (view instanceof JComponent) {
      parent = (JComponent)view;
    }

    if (allSurfaces.isEmpty()) {
      JOptionPane.showMessageDialog(parent,
          "Select walls or rooms that already have a tile texture applied.");
      return;
    }

    // Default tile size = native size of the first texture found
    double defW = 60, defH = 60;
    for (Surface sf : allSurfaces) {
      HomeTexture src = sourceOf(sf.get());
      if (src != null) {
        defW = Math.round(src.getWidth() * scaleOf(src) * 10) / 10.0;
        defH = Math.round(src.getHeight() * scaleOf(src) * 10) / 10.0;
        break;
      }
    }

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
    JComboBox<String> layoutRot = new JComboBox<String>(new String [] {"0° (horizontal rows)", "90° (vertical rows)"});
    layoutRot.setSelectedIndex(prefs.getInt("layoutRot", 0) == 1 ? 1 : 0);

    final Color [] groutHolder = {new Color(prefs.getInt("groutColor", 0x9A9A9A))};
    final Color [] tileHolder = {new Color(prefs.getInt("tileColor", 0xE8E4DC))};
    JButton groutBtn = colorButton(groutHolder);
    JButton tileBtn = colorButton(tileHolder);

    JCheckBox wallLeft = new JCheckBox("Walls: left side", prefs.getBoolean("wallLeft", true));
    JCheckBox wallRight = new JCheckBox("Walls: right side", prefs.getBoolean("wallRight", true));
    JCheckBox floorBox = new JCheckBox("Rooms: floor", prefs.getBoolean("floor", true));

    JPanel p = new JPanel(new GridLayout(0, 2, 8, 4));
    p.add(customSize); p.add(new JLabel(""));
    p.add(new JLabel("Tile width (cm)")); p.add(tileW);
    p.add(new JLabel("Tile height (cm)")); p.add(tileH);
    p.add(new JLabel("Grout width (mm)")); p.add(grout);
    p.add(new JLabel("Grout color")); p.add(groutBtn);
    p.add(new JLabel("Brick row shift (% of tile, 0 = straight)")); p.add(brick);
    p.add(new JLabel("Start offset X (cm)")); p.add(offX);
    p.add(new JLabel("Start offset Y (cm)")); p.add(offY);
    p.add(new JLabel("Layout rotation")); p.add(layoutRot);
    p.add(new JLabel("Tile color if no texture")); p.add(tileBtn);
    p.add(wallLeft); p.add(wallRight);
    p.add(floorBox); p.add(new JLabel(""));

    int res = JOptionPane.showConfirmDialog(parent, p, "Tiles with grout",
        JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
    if (res != JOptionPane.OK_OPTION) {
      return;
    }

    // Remember the values
    prefs.putBoolean("customSize", customSize.isSelected());
    prefs.putDouble("tileW", val(tileW));
    prefs.putDouble("tileH", val(tileH));
    prefs.putDouble("groutMm", val(grout));
    prefs.putDouble("brickPct", val(brick));
    prefs.putDouble("offX", val(offX));
    prefs.putDouble("offY", val(offY));
    prefs.putInt("layoutRot", layoutRot.getSelectedIndex());
    prefs.putInt("groutColor", groutHolder[0].getRGB() & 0xFFFFFF);
    prefs.putInt("tileColor", tileHolder[0].getRGB() & 0xFFFFFF);
    prefs.putBoolean("wallLeft", wallLeft.isSelected());
    prefs.putBoolean("wallRight", wallRight.isSelected());
    prefs.putBoolean("floor", floorBox.isSelected());

    Color groutColor = groutHolder[0];
    Color fallbackTileColor = tileHolder[0];
    double groutCm = val(grout) / 10.0;
    double brickFrac = val(brick) / 100.0;
    boolean rotateLayout = layoutRot.getSelectedIndex() == 1;
    String suffix = " grout " + val(grout) + "mm" + (brickFrac > 0 ? " brick " + (int)val(brick) + "%" : "");

    Map<Object, HomeTexture> cache = new IdentityHashMap<Object, HomeTexture>();
    final Object noTextureKey = new Object();
    int count = 0;
    try {
      for (int i = 0; i < allSurfaces.size(); i++) {
        String kind = kinds.get(i);
        if (("L".equals(kind) && !wallLeft.isSelected())
            || ("R".equals(kind) && !wallRight.isSelected())
            || ("F".equals(kind) && !floorBox.isSelected())) {
          continue;
        }
        Surface sf = allSurfaces.get(i);
        HomeTexture src = sourceOf(sf.get());
        Object key = src != null ? src : noTextureKey;
        HomeTexture result = cache.get(key);
        if (result == null) {
          double tw, th;
          if (customSize.isSelected() || src == null) {
            tw = val(tileW);
            th = val(tileH);
          } else {
            tw = src.getWidth() * scaleOf(src);
            th = src.getHeight() * scaleOf(src);
          }
          BufferedImage tileImg = null;
          if (src != null) {
            InputStream in = src.getImage().openStream();
            try {
              tileImg = ImageIO.read(in);
            } finally {
              in.close();
            }
          }
          double [] sizeCm = new double [2];
          BufferedImage image = buildTexture(tileImg, fallbackTileColor, tw, th, groutCm, groutColor,
              brickFrac, val(offX), val(offY), rotateLayout, sizeCm);

          File f = File.createTempFile("tiles", ".png");
          f.deleteOnExit();
          ImageIO.write(image, "png", f);
          Content content = new URLContent(f.toURI().toURL());
          String name = (src != null ? src.getName() : "Tiles") + suffix;
          CatalogTexture ct = new CatalogTexture(name, content, (float)sizeCm[0], (float)sizeCm[1]);
          result = new HomeTexture(ct);
          if (src != null) {
            sources.put(result, src);
          }
          cache.put(key, result);
        }
        sf.set(result);
        count++;
      }
    } catch (Exception ex) {
      JOptionPane.showMessageDialog(parent, "Error: " + ex);
      return;
    }
    if (count > 0) {
      getHome().setModified(true);
    }
  }

  /**
   * Builds one pattern period: 1 tile wide and m rows tall
   * (m chosen so the running bond pattern lines up when the texture repeats).
   * Returns the size in cm in sizeCm[0..1], so the scale in Sweet Home 3D is real.
   */
  static BufferedImage buildTexture(BufferedImage tileImg, Color tileC,
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

    double srcPpc = tileImg != null ? tileImg.getWidth() / tw : 8.0;
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
    if (tileImg != null) {
      tg.drawImage(tileImg, 0, 0, tilePxW, tilePxH, null);
    } else {
      tg.setColor(tileC);
      tg.fillRect(0, 0, tilePxW, tilePxH);
    }
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
