package com.linkeddesk.analysis;

import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Positions for the connection map: stories as points, links as springs (force-directed layout,
 * Fruchterman-Reingold, with a grid so repulsion only looks at nearby stories). Deterministic: the same stories
 * always get the same picture.
 */
public final class GraphLayout {
    public record Edge(int a, int b, double weight, boolean crossDesk, boolean confirmed, List<String> terms, boolean byClaude) {}

    public final List<StoryCluster> nodes;
    public final List<Edge> edges;
    public final double[] x, y;
    public final int[] degree;
    /** How many stories matched the filters before the cap. */
    public final int matched;

    private GraphLayout(List<StoryCluster> nodes, List<Edge> edges, int matched) {
        this.nodes = nodes;
        this.edges = edges;
        this.matched = matched;
        x = new double[nodes.size()];
        y = new double[nodes.size()];
        degree = new int[nodes.size()];
        for (Edge e : edges) { degree[e.a()]++; degree[e.b()]++; }
    }

    /**
     * Picks the stories to draw and lays them out.
     * @param include  which stories qualify (desk, thread…)
     * @param withUnlinked  also draw stories without links
     * @param max  most stories to draw (the most connected and most covered first); 0 = all
     */
    public static GraphLayout build(List<StoryCluster> stories, Predicate<StoryCluster> include, boolean withUnlinked, int max) {
        List<StoryCluster> pick = new ArrayList<>();
        for (StoryCluster c : stories) {
            if (!include.test(c)) continue;
            if (!withUnlinked && c.links.stream().noneMatch(l -> include.test(l.to()))) continue;
            pick.add(c);
        }
        int matched = pick.size();
        if (max > 0 && pick.size() > max) {
            pick.sort(Comparator.comparingInt((StoryCluster c) -> c.links.size() * 2 + c.sourceCount()).reversed()
                    .thenComparing(c -> c.id));
            pick = new ArrayList<>(pick.subList(0, max));
        }
        pick.sort(Comparator.comparing(c -> c.id));
        Map<StoryCluster, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < pick.size(); i++) index.put(pick.get(i), i);
        List<Edge> edges = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (int i = 0; i < pick.size(); i++) {
            for (StoryLink l : pick.get(i).links) {
                Integer j = index.get(l.to());
                if (j == null || j == i) continue;
                long key = (long) Math.min(i, j) << 32 | Math.max(i, j);
                if (!seen.add(key)) continue;
                edges.add(new Edge(i, j, l.score(), l.crossDesk(), l.confirmed(), l.sharedTerms(), l.byClaude()));
            }
        }
        GraphLayout g = new GraphLayout(pick, edges, matched);
        g.layout();
        return g;
    }

    /** Ideal distance between linked stories, in layout units (the view scales to fit). */
    static final double K = 40;

    private void layout() {
        int n = nodes.size();
        if (n == 0) return;
        // Start: stories of the same desk start in the same sector, at a stable pseudo-random spot.
        double radius = K * Math.sqrt(n) * 0.9;
        for (int i = 0; i < n; i++) {
            long h = nodes.get(i).id.hashCode() * 2654435761L;
            double a = (nodes.get(i).category.ordinal() + ((h & 0xffff) / 65536.0)) * 2 * Math.PI / Category.values().length;
            double r = radius * Math.sqrt(((h >>> 16) & 0xffff) / 65536.0);
            x[i] = Math.cos(a) * r;
            y[i] = Math.sin(a) * r;
        }
        double[] dx = new double[n], dy = new double[n];
        int iterations = n > 1500 ? 140 : 220;
        double temp = radius / 4, cool = temp / (iterations + 1);
        double cell = 2 * K;
        for (int it = 0; it < iterations; it++) {
            java.util.Arrays.fill(dx, 0);
            java.util.Arrays.fill(dy, 0);
            // repulsion between nearby stories (grid)
            Map<Long, List<Integer>> grid = new HashMap<>();
            for (int i = 0; i < n; i++) grid.computeIfAbsent(cellKey(x[i], y[i], cell), k -> new ArrayList<>()).add(i);
            for (int i = 0; i < n; i++) {
                long cx = (long) Math.floor(x[i] / cell), cy = (long) Math.floor(y[i] / cell);
                for (long gx = cx - 1; gx <= cx + 1; gx++)
                    for (long gy = cy - 1; gy <= cy + 1; gy++) {
                        List<Integer> l = grid.get((gx << 32) ^ (gy & 0xffffffffL));
                        if (l == null) continue;
                        for (int j : l) {
                            if (j == i) continue;
                            double ddx = x[i] - x[j], ddy = y[i] - y[j];
                            double d2 = ddx * ddx + ddy * ddy;
                            if (d2 < 0.01) { ddx = 0.1 * ((i * 31 + j) % 7 - 3); ddy = 0.1 * ((i * 17 + j) % 5 - 2); d2 = ddx * ddx + ddy * ddy + 0.01; }
                            double f = K * K / d2;
                            dx[i] += ddx * f;
                            dy[i] += ddy * f;
                        }
                    }
            }
            // springs along links (stronger links pull harder)
            for (Edge e : edges) {
                double ddx = x[e.a()] - x[e.b()], ddy = y[e.a()] - y[e.b()];
                double d = Math.sqrt(ddx * ddx + ddy * ddy) + 0.01;
                double f = d / K * (0.6 + 2 * Math.min(0.5, e.weight()));
                dx[e.a()] -= ddx / d * f * d / 2; dy[e.a()] -= ddy / d * f * d / 2;
                dx[e.b()] += ddx / d * f * d / 2; dy[e.b()] += ddy / d * f * d / 2;
            }
            // gentle pull to the centre keeps separate groups from drifting away
            for (int i = 0; i < n; i++) { dx[i] -= x[i] * 0.02; dy[i] -= y[i] * 0.02; }
            for (int i = 0; i < n; i++) {
                double d = Math.sqrt(dx[i] * dx[i] + dy[i] * dy[i]);
                if (d > 0) { x[i] += dx[i] / d * Math.min(d, temp); y[i] += dy[i] / d * Math.min(d, temp); }
            }
            temp = Math.max(1, temp - cool);
        }
    }

    private static long cellKey(double x, double y, double cell) {
        long cx = (long) Math.floor(x / cell), cy = (long) Math.floor(y / cell);
        return (cx << 32) ^ (cy & 0xffffffffL);
    }

    /** Bounds of the layout: minX, minY, maxX, maxY. */
    public double[] bounds() {
        double a = Double.MAX_VALUE, b = Double.MAX_VALUE, c = -Double.MAX_VALUE, d = -Double.MAX_VALUE;
        for (int i = 0; i < x.length; i++) { a = Math.min(a, x[i]); b = Math.min(b, y[i]); c = Math.max(c, x[i]); d = Math.max(d, y[i]); }
        return x.length == 0 ? new double[]{-1, -1, 1, 1} : new double[]{a, b, c, d};
    }
}
