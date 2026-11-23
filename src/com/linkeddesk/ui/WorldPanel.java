package com.linkeddesk.ui;

import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.analysis.Countries;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The World tab: where today's stories are happening. A simplified world map (drawn from built-in outlines,
 * no internet needed) with a circle per country, sized by how many stories are about it and coloured by the
 * desk most of them are in. Click a country (on the map or in the list) to see its stories.
 */
final class WorldPanel extends JPanel {
    private final Host host;
    private final MapCanvas map = new MapCanvas();
    private final DefaultListModel<Map.Entry<Countries.Country, List<StoryCluster>>> listModel = new DefaultListModel<>();
    private final JList<Map.Entry<Countries.Country, List<StoryCluster>>> list = new JList<>(listModel);
    private final StoryBrowser browser;
    private final JLabel info = new JLabel(" ");
    private Map<Countries.Country, List<StoryCluster>> index = Map.of();
    private List<StoryCluster> all = List.of();
    private Countries.Country selected;
    private int run;
    private boolean syncing;

    WorldPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        browser = new StoryBrowser(host);
        browser.setStories(List.of(), List.of(), null, "Click a country on the map or in the list to see its stories.");
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                @SuppressWarnings("unchecked") var e = (Map.Entry<Countries.Country, List<StoryCluster>>) v;
                super.getListCellRendererComponent(l, e.getKey().name() + "  (" + e.getValue().size() + ")", i, s, f);
                setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
                return this;
            }
        });
        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || syncing || list.getSelectedValue() == null) return;
            pick(list.getSelectedValue().getKey());
        });
        map.onPick = this::pick;
        JScrollPane ls = new JScrollPane(list);
        ls.setPreferredSize(new Dimension(230, 300));
        JPanel top = new JPanel(new BorderLayout());
        top.add(map, BorderLayout.CENTER);
        top.add(ls, BorderLayout.EAST);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, browser);
        split.setResizeWeight(0.55);
        split.setDividerLocation(420);
        info.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        add(info, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
    }

    /** New analysis from the main window (same time window and desk filter as the Stories tab). */
    void setResult(Analyzer.Result r, Category desk) {
        int my = ++run;
        List<StoryCluster> stories = new ArrayList<>();
        for (StoryCluster c : r.stories()) if (desk == null || c.inDesk(desk)) stories.add(c);
        info.setText("Finding where the stories happen…");
        new SwingWorker<Map<Countries.Country, List<StoryCluster>>, Void>() {
            @Override protected Map<Countries.Country, List<StoryCluster>> doInBackground() { return Countries.index(stories); }
            @Override protected void done() {
                if (my != run) return;
                try {
                    index = get();
                    all = r.stories();
                    List<Map.Entry<Countries.Country, List<StoryCluster>>> rows = new ArrayList<>(index.entrySet());
                    rows.sort((a, b) -> b.getValue().size() != a.getValue().size() ? b.getValue().size() - a.getValue().size() : a.getKey().name().compareTo(b.getKey().name()));
                    syncing = true;
                    listModel.clear();
                    listModel.addAll(rows);
                    syncing = false;
                    long placed = stories.stream().filter(s -> index.values().stream().anyMatch(v -> v.contains(s))).count();
                    info.setText(String.format("%,d of %,d stories placed in %d countries (named in a headline, or twice in the coverage). Same time window and desk as the Stories tab.",
                            placed, stories.size(), index.size()));
                    map.setData(index);
                    if (selected != null && index.containsKey(selected)) pick(selected);
                    else if (!rows.isEmpty()) pick(rows.get(0).getKey());
                } catch (Exception ex) { info.setText("Could not place the stories: " + ex.getMessage()); }
            }
        }.execute();
    }

    /** Shows one country's stories. */
    void pick(Countries.Country c) {
        selected = c;
        map.selected = c;
        map.repaint();
        List<StoryCluster> s = index.getOrDefault(c, List.of());
        syncing = true;
        for (int i = 0; i < listModel.size(); i++) if (listModel.get(i).getKey().equals(c)) { list.setSelectedIndex(i); list.ensureIndexIsVisible(i); }
        syncing = false;
        browser.setStories(s, all, null, "No stories about " + c.name() + " in this time window.");
    }

    Countries.Country selected() { return selected; }
    int countryCount() { return index.size(); }
    void refreshPage() { browser.refreshDetail(); map.repaint(); }

    // ------------------------------------------------------------------ the map

    private static final class MapCanvas extends JComponent {
        private Map<Countries.Country, List<StoryCluster>> data = Map.of();
        private final List<Countries.Country> order = new ArrayList<>();
        private final List<Shape> spots = new ArrayList<>();
        private Countries.Country hover;
        Countries.Country selected;
        java.util.function.Consumer<Countries.Country> onPick = c -> { };

        MapCanvas() {
            setPreferredSize(new Dimension(800, 380));
            setToolTipText("");
            MouseAdapter m = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    Countries.Country h = at(e.getPoint());
                    if (h != hover) { hover = h; repaint(); }
                    setCursor(Cursor.getPredefinedCursor(h == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
                }
                @Override public void mouseClicked(MouseEvent e) {
                    Countries.Country h = at(e.getPoint());
                    if (h != null) onPick.accept(h);
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        void setData(Map<Countries.Country, List<StoryCluster>> d) {
            data = d;
            order.clear();
            order.addAll(d.keySet());
            order.sort((a, b) -> d.get(b).size() - d.get(a).size());   // big circles first, small on top
            repaint();
        }

        private Countries.Country at(Point p) {
            for (int i = spots.size() - 1; i >= 0; i--) if (spots.get(i).contains(p)) return order.get(i);
            return null;
        }

        @Override public String getToolTipText(MouseEvent e) {
            Countries.Country c = at(e.getPoint());
            if (c == null) return null;
            List<StoryCluster> s = data.get(c);
            StringBuilder sb = new StringBuilder("<html><b>").append(Html.esc(c.name())).append("</b>: ").append(s.size())
                    .append(s.size() == 1 ? " story" : " stories");
            for (int i = 0; i < Math.min(3, s.size()); i++) sb.append("<br>• ").append(Html.esc(s.get(i).headline.length() > 70 ? s.get(i).headline.substring(0, 68) + "…" : s.get(i).headline));
            return sb.append("<br><i>Click to see them all</i></html>").toString();
        }

        // Equirectangular projection of the populated latitudes.
        private static final double LAT_TOP = 80, LAT_BOTTOM = -57;
        private double sx, sy, ox, oy;

        private Point.Double project(double lat, double lon) {
            return new Point.Double(ox + (lon + 180) * sx, oy + (LAT_TOP - lat) * sy);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            Color sea = Theme.dark ? Theme.c("#182028") : Theme.c("#eef3f8");
            Color land = Theme.dark ? Theme.c("#33393f") : Theme.c("#d9dee3");
            g.setColor(sea);
            g.fillRect(0, 0, w, h);
            // keep the 2:1 shape of the map, centred
            double mw = w - 20, mh = h - 20;
            double ratio = 360.0 / (LAT_TOP - LAT_BOTTOM) * 0.75;
            if (mw / mh > ratio) mw = mh * ratio; else mh = mw / ratio;
            sx = mw / 360.0;
            sy = mh / (LAT_TOP - LAT_BOTTOM);
            ox = (w - mw) / 2;
            oy = (h - mh) / 2;
            g.setColor(land);
            for (double[] poly : OUTLINES) {
                Path2D p = new Path2D.Double();
                for (int i = 0; i < poly.length; i += 2) {
                    Point.Double q = project(poly[i + 1], poly[i]);
                    if (i == 0) p.moveTo(q.x, q.y); else p.lineTo(q.x, q.y);
                }
                p.closePath();
                g.fill(p);
            }
            spots.clear();
            if (data.isEmpty()) {
                g.setColor(Theme.mutedColor());
                g.drawString("No stories placed yet.", 20, 24);
                g.dispose();
                return;
            }
            int maxN = data.values().stream().mapToInt(List::size).max().orElse(1);
            double maxR = Math.max(10, Math.min(w, h) / 14.0);
            Font f = UIManager.getFont("Label.font");
            g.setFont(f.deriveFont(Font.BOLD, f.getSize2D() * 0.85f));
            FontMetrics fm = g.getFontMetrics();
            List<Rectangle> labels = new ArrayList<>();
            for (Countries.Country c : order) {
                List<StoryCluster> s = data.get(c);
                Point.Double q = project(c.lat(), c.lon());
                double r = 4 + (maxR - 4) * Math.sqrt(s.size() / (double) maxN);
                Map<Category, Integer> votes = new EnumMap<>(Category.class);
                for (StoryCluster x : s) votes.merge(x.category, 1, Integer::sum);
                Category top = votes.entrySet().stream().max(Map.Entry.comparingByValue()).get().getKey();
                Color col = Theme.desk(top);
                Shape dot = new Ellipse2D.Double(q.x - r, q.y - r, 2 * r, 2 * r);
                spots.add(dot);
                g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 175));
                g.fill(dot);
                g.setColor(c.equals(selected) || c.equals(hover) ? Theme.text() : new Color(col.getRed(), col.getGreen(), col.getBlue()));
                g.setStroke(new BasicStroke(c.equals(selected) ? 2.5f : 1f));
                g.draw(dot);
            }
            // labels for the biggest countries (and the selected one), avoiding overlaps
            for (int i = 0; i < order.size(); i++) {
                Countries.Country c = order.get(i);
                if (i >= 14 && !c.equals(selected) && !c.equals(hover)) continue;
                Point.Double q = project(c.lat(), c.lon());
                String t = c.name() + " " + data.get(c).size();
                Rectangle box = new Rectangle((int) q.x - fm.stringWidth(t) / 2 - 2, (int) q.y - fm.getAscent() / 2 - 1, fm.stringWidth(t) + 4, fm.getHeight());
                if (labels.stream().anyMatch(box::intersects) && !c.equals(selected)) continue;
                labels.add(box);
                g.setColor(Theme.dark ? new Color(0, 0, 0, 120) : new Color(255, 255, 255, 170));
                g.fill(box);
                g.setColor(Theme.text());
                g.drawString(t, box.x + 2, box.y + fm.getAscent());
            }
            g.setFont(f.deriveFont(f.getSize2D() * 0.8f));
            g.setColor(Theme.mutedColor());
            g.drawString("Simplified map · circle size = stories · colour = desk", (int) ox + 6, (int) (oy + mh) - 6);
            g.dispose();
        }

        /** Rough outlines (longitude, latitude pairs) of the continents and large islands. */
        private static final double[][] OUTLINES = {
            // North and Central America
            {-168, 66, -162, 70, -150, 71, -140, 70, -128, 70, -115, 68, -100, 68, -95, 72, -85, 70, -80, 63, -90, 58, -95, 57, -82, 55,
             -78, 52, -70, 55, -62, 58, -56, 52, -60, 47, -66, 45, -70, 42, -74, 40, -76, 35, -81, 31, -80, 25, -84, 30, -90, 29, -97, 27,
             -97, 22, -94, 18, -87, 21, -88, 16, -84, 11, -79, 9, -78, 8, -80, 7.5, -86, 11, -92, 15, -105, 20, -110, 23, -115, 30, -117, 33,
             -121, 35, -124, 40, -124, 46, -125, 49, -131, 54, -138, 59, -148, 60, -155, 58, -162, 55, -165, 60},
            // South America
            {-77, 8, -72, 12, -63, 11, -60, 8, -52, 5, -50, 0, -44, -2, -35, -5, -35, -9, -39, -15, -41, -22, -48, -26, -53, -34, -58, -38,
             -62, -39, -65, -45, -68, -50, -69, -55, -74, -52, -73, -45, -72, -35, -71, -28, -70, -18, -75, -15, -81, -6, -80, 0, -78, 2},
            // Europe and Asia
            {-9, 37, -9, 43, -2, 44, -5, 48, 2, 51, 5, 53, 8, 55, 10, 57, 5, 58, 5, 62, 14, 68, 20, 70, 28, 71, 40, 68, 44, 66, 55, 68, 68, 70,
             70, 73, 80, 73, 95, 76, 105, 78, 115, 73, 130, 72, 140, 72, 160, 70, 170, 70, 180, 68, 180, 65, 178, 62, 165, 60, 160, 55, 156, 51,
             150, 59, 140, 58, 136, 54, 141, 48, 135, 43, 129, 41, 126, 38, 127, 35, 122, 30, 120, 25, 115, 22, 108, 21, 106, 17, 109, 12, 105, 9,
             103, 10, 100, 13, 99, 8, 103, 1.5, 100, 4, 98, 9, 97, 16, 94, 18, 92, 22, 87, 22, 80, 15, 78, 8, 76, 10, 73, 16, 72, 21, 67, 24,
             61, 25, 57, 26, 56, 24, 59, 22, 55, 17, 48, 13, 43, 13, 39, 21, 35, 28, 35, 33, 36, 36, 30, 36, 27, 37, 26, 40, 23, 40, 23, 37,
             21, 38, 19, 42, 14, 45, 12, 44, 16, 41, 18, 40, 16, 38, 12, 38.5, 10, 44, 7, 43.5, 3, 43, 3, 42, 0, 39, -1, 37, -5, 36},
            // Africa
            {-17, 21, -17, 15, -15, 11, -13, 8, -8, 4.5, -3, 5, 5, 6, 9, 4, 9, 1, 13, -6, 12, -17, 15, -27, 18, -34, 22, -34, 27, -34, 33, -26,
             35, -22, 40, -15, 40, -10, 39, -5, 42, 0, 48, 5, 51, 11, 44, 11, 43, 13, 38, 18, 34, 28, 32, 31, 25, 32, 20, 31, 15, 32, 11, 34,
             10, 37, 3, 37, -2, 35, -6, 36, -10, 30, -13, 27},
            // Australia
            {113, -22, 114, -26, 115, -34, 118, -35, 124, -34, 131, -31, 138, -35, 141, -38, 146, -39, 150, -37, 153, -32, 153, -25, 146, -19,
             142, -11, 140, -17, 136, -15, 137, -12, 131, -11, 126, -14, 122, -18},
            // Greenland, Iceland, Britain, Ireland
            {-73, 78, -60, 82, -30, 83, -20, 80, -18, 75, -22, 70, -32, 68, -42, 60, -48, 61, -53, 66, -58, 75},
            {-24, 65.5, -14, 66.5, -13.5, 65, -18, 63.5, -22, 64},
            {-5, 50, 1.5, 51, 1.7, 53, -0.5, 54.5, -2, 56, -2, 58, -5, 58.6, -6, 57, -5, 55, -3, 54.5, -4.5, 53, -5, 51.5},
            {-10, 51.5, -6, 52, -6, 54, -7.5, 55.3, -10, 54},
            // Japan, Taiwan, Philippines, Sri Lanka
            {130, 31, 132, 34, 135, 34, 140, 35, 141, 38, 142, 41, 141, 43.5, 145, 44, 144, 42.5, 141, 41.5, 140, 40, 139, 37, 136, 36, 133, 35.5, 130, 33.5},
            {120, 22, 122, 25, 121.5, 22},
            {120, 18, 122, 18, 124, 12, 126, 7, 122, 7, 120, 14},
            {80, 9.8, 81.8, 7.5, 80.5, 6, 79.8, 7.5},
            // Sumatra, Java, Borneo, Sulawesi, New Guinea
            {95, 5.5, 98, 4, 104, -1, 106, -6, 102, -4, 97, 1},
            {105, -6, 114, -7, 114, -8.5, 105, -7},
            {109, 1, 111, -3, 116, -4, 119, 1, 117, 7, 113, 4},
            {119, -5, 121, 1, 125, 1.5, 121, -1, 123, -5},
            {131, -1, 141, -2.5, 147, -6, 150, -10, 141, -9, 138, -8, 132, -4},
            // New Zealand, Madagascar, Cuba, Hispaniola
            {172, -34.5, 175, -37, 178, -38, 175, -41.5, 172, -41, 168, -46.5, 171, -44, 174, -39},
            {49, -12, 50, -15, 47, -25, 44, -24, 44, -17, 47, -14},
            {-85, 22, -80, 23, -74, 20, -77, 20, -82, 22},
            {-74.5, 19.8, -68.5, 19, -70, 18, -74.5, 18},
        };
    }
}
