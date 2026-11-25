package com.linkeddesk.ui;

import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.analysis.GraphLayout;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryThread;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The Map tab: linked stories as a network of dots (coloured by desk, bigger = more outlets) and lines
 * (thicker = stronger link). Drag to move, scroll to zoom, hover for the headline, click to read the story.
 * Shows the time window chosen in the toolbar; filter by desk or thread here.
 */
final class MapPanel extends JPanel {
    private final Host host;
    private final JComboBox<Object> desk = new JComboBox<>();
    private final JComboBox<Object> thread = new JComboBox<>();
    private final JComboBox<String> size = new JComboBox<>(new String[]{"200 stories", "500 stories", "1,000 stories", "All stories"});
    private final JCheckBox unlinked = new JCheckBox("Include stories without links");
    private final JLabel info = new JLabel(" ");
    private final Canvas canvas = new Canvas();
    private final StoryPane pane;
    private Analyzer.Result result = new Analyzer.Result(List.of(), List.of(), 0);
    private GraphLayout layout;
    private int run;
    /** How long the last layout took (shown, and checked by the tests). */
    long lastMillis;

    MapPanel(Host host) {
        super(new BorderLayout());
        this.host = host;
        pane = new StoryPane(host);
        pane.setEmptyMessage("Click a dot to read the story. Drag to move the map, scroll to zoom.");
        pane.lookup = this::find;
        pane.openStory = id -> { StoryCluster c = find(id); if (c != null) { canvas.selected = c; canvas.centreOn(c); pane.show(c, true); canvas.repaint(); } };
        pane.stateChanged = canvas::repaint;
        canvas.onPick = c -> pane.show(c, true);
        size.setSelectedIndex(1);
        unlinked.setOpaque(false);
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(new JLabel("Desk:")); top.add(desk);
        top.add(new JLabel("Thread:")); top.add(thread);
        top.add(new JLabel("Show up to:")); top.add(size);
        top.add(unlinked);
        JButton fit = new JButton("Fit to window");
        fit.addActionListener(e -> { canvas.fit(); canvas.repaint(); });
        top.add(fit);
        top.add(info);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, canvas, pane);
        split.setResizeWeight(0.65);
        split.setDividerLocation(820);
        add(top, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        for (JComboBox<?> b : List.of(desk, thread, size)) b.addActionListener(e -> { if (!filling) rebuild(); });
        unlinked.addActionListener(e -> rebuild());
        thread.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                Object shown = v instanceof StoryThread t ? t.topic() + " (" + t.stories().size() + ")" : v;
                if (shown instanceof String str && str.length() > 50) shown = str.substring(0, 50) + "…";
                return super.getListCellRendererComponent(l, shown, i, s, f);
            }
        });
        desk.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                return super.getListCellRendererComponent(l, v instanceof Category c ? c.label() : v, i, s, f);
            }
        });
        filling = true;
        desk.addItem("All desks");
        for (Category c : Category.values()) desk.addItem(c);
        thread.addItem("All threads");
        filling = false;
    }

    private boolean filling;

    private StoryCluster find(String id) {
        for (StoryCluster c : result.stories()) if (c.id.equals(id)) return c;
        return null;
    }

    /** New analysis from the main window (time window changed, new download, corrections). */
    void setResult(Analyzer.Result r) {
        result = r;
        filling = true;
        Object keep = thread.getSelectedItem();
        thread.removeAllItems();
        thread.addItem("All threads");
        for (StoryThread t : r.threads()) thread.addItem(t);
        if (keep instanceof StoryThread kt) for (int i = 1; i < thread.getItemCount(); i++)
            if (((StoryThread) thread.getItemAt(i)).topic().equals(kt.topic())) thread.setSelectedIndex(i);
        filling = false;
        rebuild();
    }

    private void rebuild() {
        int r = ++run;
        Category d = desk.getSelectedItem() instanceof Category c ? c : null;
        Set<StoryCluster> inThread = thread.getSelectedItem() instanceof StoryThread t ? new HashSet<>(t.stories()) : null;
        Predicate<StoryCluster> include = c -> (d == null || c.inDesk(d)) && (inThread == null || inThread.contains(c));
        int max = switch (size.getSelectedIndex()) { case 0 -> 200; case 1 -> 500; case 2 -> 1000; default -> 0; };
        boolean withUnlinked = unlinked.isSelected();
        List<StoryCluster> stories = result.stories();
        info.setText("Laying out…");
        new SwingWorker<GraphLayout, Void>() {
            long ms;
            @Override protected GraphLayout doInBackground() {
                long t0 = System.nanoTime();
                GraphLayout g = GraphLayout.build(stories, include, withUnlinked, max);
                ms = (System.nanoTime() - t0) / 1_000_000;
                return g;
            }
            @Override protected void done() {
                if (r != run) return;
                try {
                    layout = get();
                    lastMillis = ms;
                    canvas.setLayout(layout);
                    info.setText(String.format("%,d stories, %,d links%s · %.1f s", layout.nodes.size(), layout.edges.size(),
                            layout.matched > layout.nodes.size() ? " (the " + layout.nodes.size() + " most connected of " + String.format("%,d", layout.matched) + ")" : "",
                            ms / 1000.0));
                } catch (Exception ex) { com.linkeddesk.app.Logs.error("Drawing the connection map", ex); info.setText("Could not draw the map: " + ex.getMessage()); }
            }
        }.execute();
    }

    GraphLayout layoutShown() { return layout; }
    void refreshPage() { pane.refresh(); canvas.repaint(); }

    // ------------------------------------------------------------------ drawing

    private static final class Canvas extends JComponent {
        private GraphLayout g;
        private final AffineTransform view = new AffineTransform();
        private StoryCluster hover, selected;
        private Point drag;
        java.util.function.Consumer<StoryCluster> onPick = c -> { };

        Canvas() {
            setToolTipText("");
            setPreferredSize(new Dimension(700, 500));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { drag = e.getPoint(); }
                @Override public void mouseDragged(MouseEvent e) {
                    if (drag == null) return;
                    AffineTransform t = AffineTransform.getTranslateInstance(e.getX() - drag.x, e.getY() - drag.y);
                    view.preConcatenate(t);
                    drag = e.getPoint();
                    repaint();
                }
                @Override public void mouseReleased(MouseEvent e) { drag = null; }
                @Override public void mouseMoved(MouseEvent e) {
                    StoryCluster h = at(e.getPoint());
                    if (h != hover) { hover = h; repaint(); }
                    setCursor(Cursor.getPredefinedCursor(h == null ? Cursor.MOVE_CURSOR : Cursor.HAND_CURSOR));
                }
                @Override public void mouseClicked(MouseEvent e) {
                    StoryCluster h = at(e.getPoint());
                    if (h != null) { selected = h; onPick.accept(h); repaint(); }
                }
                @Override public void mouseWheelMoved(MouseWheelEvent e) {
                    double f = Math.pow(1.15, -e.getPreciseWheelRotation());
                    AffineTransform t = new AffineTransform();
                    t.translate(e.getX(), e.getY());
                    t.scale(f, f);
                    t.translate(-e.getX(), -e.getY());
                    view.preConcatenate(t);
                    repaint();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
            addMouseWheelListener(m);
        }

        void setLayout(GraphLayout layout) {
            g = layout;
            hover = null;
            if (selected != null && !g.nodes.contains(selected)) selected = null;
            fit();
            repaint();
        }

        void fit() {
            if (g == null || getWidth() == 0) { view.setToIdentity(); return; }
            double[] b = g.bounds();
            double w = Math.max(1, b[2] - b[0]), h = Math.max(1, b[3] - b[1]);
            double s = Math.min((getWidth() - 60) / w, (getHeight() - 60) / h);
            s = Math.max(0.05, Math.min(s, 3));
            view.setToIdentity();
            view.translate(getWidth() / 2.0, getHeight() / 2.0);
            view.scale(s, s);
            view.translate(-(b[0] + b[2]) / 2, -(b[1] + b[3]) / 2);
        }

        void centreOn(StoryCluster c) {
            int i = g == null ? -1 : g.nodes.indexOf(c);
            if (i < 0) return;
            Point2D p = view.transform(new Point2D.Double(g.x[i], g.y[i]), null);
            view.preConcatenate(AffineTransform.getTranslateInstance(getWidth() / 2.0 - p.getX(), getHeight() / 2.0 - p.getY()));
        }

        private double radius(int i) { return 4 + 2.2 * Math.sqrt(g.nodes.get(i).sourceCount()) + Math.min(4, g.degree[i] * 0.5); }

        private StoryCluster at(Point p) {
            if (g == null) return null;
            double s = Math.sqrt(Math.abs(view.getDeterminant()));
            StoryCluster best = null;
            double bestD = Double.MAX_VALUE;
            for (int i = 0; i < g.nodes.size(); i++) {
                Point2D q = view.transform(new Point2D.Double(g.x[i], g.y[i]), null);
                double r = Math.max(4, radius(i) * Math.min(1.5, Math.max(0.6, s)));
                double d = q.distance(p);
                if (d <= r + 2 && d < bestD) { bestD = d; best = g.nodes.get(i); }
            }
            return best;
        }

        @Override public String getToolTipText(MouseEvent e) {
            StoryCluster c = at(e.getPoint());
            if (c == null) return null;
            return "<html><b>" + Html.esc(c.headline) + "</b><br>" + c.category.label() + " · " + c.sourceCount()
                    + (c.sourceCount() == 1 ? " source" : " sources") + " · " + c.links.size() + (c.links.size() == 1 ? " link" : " links")
                    + " · " + Html.ago(c.lastSeen) + "<br><i>Click to read</i></html>";
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D gr = (Graphics2D) g0.create();
            gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            gr.setColor(Theme.canvas());
            gr.fillRect(0, 0, getWidth(), getHeight());
            if (g == null || g.nodes.isEmpty()) {
                gr.setColor(Theme.mutedColor());
                gr.drawString(g == null ? "Drawing the map…" : "No linked stories to show with these filters.", 20, 30);
                gr.dispose();
                return;
            }
            if (view.isIdentity()) fit();
            double s = Math.sqrt(Math.abs(view.getDeterminant()));
            int n = g.nodes.size();
            Point2D[] pts = new Point2D[n];
            for (int i = 0; i < n; i++) pts[i] = view.transform(new Point2D.Double(g.x[i], g.y[i]), null);
            Set<StoryCluster> near = new HashSet<>();
            StoryCluster focus = hover != null ? hover : selected;
            if (focus != null) { near.add(focus); focus.links.forEach(l -> near.add(l.to())); }

            // links
            for (GraphLayout.Edge e : g.edges) {
                boolean lit = focus != null && (g.nodes.get(e.a()) == focus || g.nodes.get(e.b()) == focus);
                Color c = lit ? Theme.text() : e.byClaude() ? Theme.c(Theme.star()) : e.crossDesk() ? Theme.desk(Category.TECH) : Theme.lineColor();
                int alpha = lit ? 230 : focus != null ? 50 : e.crossDesk() ? 150 : 200;
                gr.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
                float w = (float) (0.8 + 4 * Math.min(0.5, Math.max(e.byClaude() ? 0.2 : 0, e.weight()))) * (float) Math.min(1.6, Math.max(0.6, s));
                gr.setStroke(e.confirmed() ? new BasicStroke(w + 1) : e.crossDesk()
                        ? new BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{6f, 4f}, 0f) : new BasicStroke(w));
                gr.draw(new Line2D.Double(pts[e.a()], pts[e.b()]));
            }
            // stories
            for (int i = 0; i < n; i++) {
                StoryCluster c = g.nodes.get(i);
                double r = radius(i) * Math.min(1.5, Math.max(0.6, s));
                Color col = Theme.desk(c.category);
                boolean dim = focus != null && !near.contains(c);
                gr.setColor(dim ? new Color(col.getRed(), col.getGreen(), col.getBlue(), 70) : col);
                Ellipse2D dot = new Ellipse2D.Double(pts[i].getX() - r, pts[i].getY() - r, 2 * r, 2 * r);
                gr.fill(dot);
                gr.setColor(c == selected ? Theme.text() : Theme.canvas());
                gr.setStroke(new BasicStroke(c == selected || c == hover ? 2.5f : 1f));
                gr.draw(dot);
            }
            // labels: the most connected stories, plus the one under the mouse and its neighbours
            Font f = UIManager.getFont("Label.font");
            gr.setFont(f.deriveFont(f.getSize2D() * 0.85f));
            FontMetrics fm = gr.getFontMetrics();
            int labels = (int) Math.min(n, 12 + 10 * s);
            Integer[] order = new Integer[n];
            for (int i = 0; i < n; i++) order[i] = i;
            java.util.Arrays.sort(order, (a, b) -> Integer.compare(g.degree[b] * 3 + g.nodes.get(b).sourceCount(), g.degree[a] * 3 + g.nodes.get(a).sourceCount()));
            List<Rectangle> used = new java.util.ArrayList<>();
            for (int k = 0; k < n; k++) {
                int i = order[k];
                StoryCluster c = g.nodes.get(i);
                boolean must = focus != null && near.contains(c);
                if (!must && (k >= labels || focus != null)) continue;
                String t = c.headline.length() > 60 ? c.headline.substring(0, 58) + "…" : c.headline;
                int tx = (int) pts[i].getX() + 8, ty = (int) pts[i].getY() - 6;
                if (tx + fm.stringWidth(t) > getWidth() - 4) tx = (int) pts[i].getX() - 8 - fm.stringWidth(t);   // keep labels inside
                tx = Math.max(4, tx);
                Rectangle box = new Rectangle(tx - 2, ty - fm.getAscent(), fm.stringWidth(t) + 4, fm.getHeight());
                if (!must && used.stream().anyMatch(box::intersects)) continue;
                used.add(box);
                gr.setColor(new Color(Theme.canvas().getRed(), Theme.canvas().getGreen(), Theme.canvas().getBlue(), 200));
                gr.fill(box);
                gr.setColor(c == focus ? Theme.text() : Theme.mutedColor());
                gr.drawString(t, tx, ty);
            }
            // legend
            int lx = 12, ly = getHeight() - 14;
            for (Category c : Category.values()) {
                gr.setColor(Theme.desk(c));
                gr.fillOval(lx, ly - 9, 10, 10);
                gr.setColor(Theme.mutedColor());
                gr.drawString(c.label(), lx + 14, ly);
                lx += 22 + fm.stringWidth(c.label());
            }
            gr.drawString("· dashed = link across desks · thick = confirmed by you · gold = found by Claude", lx, ly);
            gr.dispose();
        }
    }
}
