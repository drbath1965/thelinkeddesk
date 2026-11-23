package com.linkeddesk.ui;

import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A thread or storyline on a timeline: one lane per desk, a marker per story at the time it was reported
 * (bigger = more outlets). Hover for the headline; click to open the story.
 */
final class TimelinePanel extends JComponent {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d");
    private List<StoryCluster> stories = List.of();
    private final List<Shape> shapes = new ArrayList<>();
    private StoryCluster hover;
    Consumer<StoryCluster> onOpen = c -> { };

    TimelinePanel() {
        setPreferredSize(new Dimension(600, 170));
        setToolTipText("");
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                StoryCluster h = at(e.getPoint());
                if (h != hover) { hover = h; repaint(); }
                setCursor(Cursor.getPredefinedCursor(h == null ? Cursor.DEFAULT_CURSOR : Cursor.HAND_CURSOR));
            }
            @Override public void mouseClicked(MouseEvent e) {
                StoryCluster h = at(e.getPoint());
                if (h != null) onOpen.accept(h);
            }
            @Override public void mouseExited(MouseEvent e) { hover = null; repaint(); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    void setStories(List<StoryCluster> list) {
        stories = new ArrayList<>(list);
        stories.sort(java.util.Comparator.comparingLong(c -> c.firstSeen));
        hover = null;
        repaint();
    }

    @Override public String getToolTipText(MouseEvent e) {
        StoryCluster c = at(e.getPoint());
        if (c == null) return null;
        return "<html><b>" + Html.esc(c.headline) + "</b><br>" + c.category.label() + " · " + c.sourceCount()
                + (c.sourceCount() == 1 ? " source" : " sources") + " · " + DAY.format(Instant.ofEpochMilli(c.firstSeen).atZone(ZoneId.systemDefault()))
                + "<br><i>Click to open</i></html>";
    }

    private StoryCluster at(Point p) {
        for (int i = shapes.size() - 1; i >= 0; i--) if (shapes.get(i).contains(p)) return stories.get(i);
        return null;
    }

    /** Lanes: the desks that appear, in the usual order. */
    private List<Category> lanes() {
        Set<Category> s = EnumSet.noneOf(Category.class);
        for (StoryCluster c : stories) s.add(c.category);
        return new ArrayList<>(s);
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight();
        g.setColor(Theme.canvas());
        g.fillRect(0, 0, w, h);
        shapes.clear();
        if (stories.isEmpty()) { g.dispose(); return; }
        Font f = UIManager.getFont("Label.font");
        g.setFont(f.deriveFont(f.getSize2D() * 0.9f));
        FontMetrics fm = g.getFontMetrics();
        List<Category> lanes = lanes();
        int labelW = 0;
        for (Category c : lanes) labelW = Math.max(labelW, fm.stringWidth(c.label()));
        int left = labelW + 24, right = w - 24, top = 14, axis = h - fm.getHeight() - 10;
        int laneH = Math.max(18, (axis - top) / Math.max(1, lanes.size()));

        ZoneId z = ZoneId.systemDefault();
        LocalDate d0 = Instant.ofEpochMilli(stories.get(0).firstSeen).atZone(z).toLocalDate();
        long maxT = stories.stream().mapToLong(c -> c.lastSeen).max().getAsLong();
        LocalDate d1 = Instant.ofEpochMilli(maxT).atZone(z).toLocalDate().plusDays(1);
        long t0 = d0.atStartOfDay(z).toInstant().toEpochMilli(), t1 = d1.atStartOfDay(z).toInstant().toEpochMilli();
        double span = Math.max(1, t1 - t0);

        // lanes and day grid
        for (int i = 0; i < lanes.size(); i++) {
            int y = top + i * laneH + laneH / 2;
            g.setColor(Theme.lineColor());
            g.drawLine(left, y, right, y);
            g.setColor(Theme.desk(lanes.get(i)));
            g.drawString(lanes.get(i).label(), 8, y + fm.getAscent() / 2 - 2);
        }
        long days = Math.max(1, java.time.temporal.ChronoUnit.DAYS.between(d0, d1));
        int every = (int) Math.max(1, Math.ceil(days / Math.max(1.0, (right - left) / 70.0)));
        g.setColor(Theme.mutedColor());
        for (LocalDate d = d0; !d.isAfter(d1); d = d.plusDays(every)) {
            long t = d.atStartOfDay(z).toInstant().toEpochMilli();
            int x = left + (int) ((t - t0) / span * (right - left));
            g.setColor(Theme.lineColor());
            g.drawLine(x, top, x, axis);
            g.setColor(Theme.mutedColor());
            String s = DAY.format(d);
            g.drawString(s, Math.min(right - fm.stringWidth(s), Math.max(left, x - fm.stringWidth(s) / 2)), axis + fm.getAscent() + 4);
        }

        // markers: a bar from first to last report, a dot at the first report sized by outlets
        for (StoryCluster c : stories) {
            int lane = lanes.indexOf(c.category);
            int y = top + lane * laneH + laneH / 2;
            int x0 = left + (int) ((c.firstSeen - t0) / span * (right - left));
            int x1 = left + (int) ((c.lastSeen - t0) / span * (right - left));
            double r = Math.min(laneH / 2.0 - 2, 5 + 2.2 * Math.sqrt(c.sourceCount()));
            Color col = Theme.desk(c.category);
            if (x1 > x0 + 2) {
                g.setColor(new Color(col.getRed(), col.getGreen(), col.getBlue(), 90));
                g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.drawLine(x0, y, x1, y);
            }
            Shape dot = new Ellipse2D.Double(x0 - r, y - r, 2 * r, 2 * r);
            shapes.add(dot);
            g.setColor(col);
            g.fill(dot);
            g.setStroke(new BasicStroke(c == hover ? 2.5f : 1f));
            g.setColor(c == hover ? Theme.text() : Theme.canvas());
            g.draw(dot);
        }
        g.dispose();
    }
}
