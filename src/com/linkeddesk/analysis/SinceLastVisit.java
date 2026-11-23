package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;

import java.util.*;
import java.util.function.Function;

/**
 * What's new since you last looked (UI-F14): watchlist hits, new links between desks, new stories and stories with
 * fresh coverage, each counted from the moment you last had the app open.
 */
public final class SinceLastVisit {
    private SinceLastVisit() {}

    public enum Kind { WATCH, LINK, NEW, MORE }

    public record Item(StoryCluster story, Kind kind, String tag) {}

    public record Result(long since, List<Item> items, int watch, int links, int fresh, int more) {
        public boolean isEmpty() { return items.isEmpty(); }
        public String summary() {
            List<String> p = new ArrayList<>();
            if (watch > 0) p.add(watch + (watch == 1 ? " watchlist story" : " watchlist stories"));
            if (links > 0) p.add(links + (links == 1 ? " new link between desks" : " new links between desks"));
            if (fresh > 0) p.add(fresh + (fresh == 1 ? " new story" : " new stories"));
            if (more > 0) p.add(more + (more == 1 ? " story with new coverage" : " stories with new coverage"));
            return p.isEmpty() ? "Nothing new since you last looked." : String.join(" · ", p);
        }
    }

    static boolean newer(StoryCluster c, long since) { for (Article a : c.articles) if (a.fetched > since) return true; return false; }
    static boolean older(StoryCluster c, long since) { for (Article a : c.articles) if (a.fetched <= since) return true; return false; }

    public static Result compute(List<StoryCluster> stories, long since, Function<StoryCluster, List<String>> watchesFor) {
        List<Item> watch = new ArrayList<>(), links = new ArrayList<>(), fresh = new ArrayList<>(), more = new ArrayList<>();
        Set<StoryCluster> placed = new HashSet<>();
        for (StoryCluster c : stories) {
            if (!newer(c, since)) continue;
            List<String> w = watchesFor.apply(c);
            if (!w.isEmpty()) { watch.add(new Item(c, Kind.WATCH, "watchlist: " + String.join(", ", w))); placed.add(c); }
        }
        Set<String> pairs = new HashSet<>();
        for (StoryCluster c : stories) {
            for (StoryLink l : c.links) {
                if (!l.crossDesk() || !(newer(c, since) || newer(l.to(), since))) continue;
                String k = c.id.compareTo(l.to().id) < 0 ? c.id + "|" + l.to().id : l.to().id + "|" + c.id;
                if (!pairs.add(k) || placed.contains(c)) continue;
                links.add(new Item(c, Kind.LINK, "new link to " + l.to().category.label() + ": " + shorten(l.to().headline)));
                placed.add(c);
            }
        }
        for (StoryCluster c : stories) {
            if (placed.contains(c) || !newer(c, since)) continue;
            if (older(c, since)) {
                int n = 0;
                for (Article a : c.articles) if (a.fetched > since) n++;
                more.add(new Item(c, Kind.MORE, n + (n == 1 ? " new article" : " new articles")));
            } else fresh.add(new Item(c, Kind.NEW, "new"));
        }
        Comparator<Item> cov = Comparator.comparingInt((Item i) -> i.story().independentCount()).reversed();
        fresh.sort(cov);
        more.sort(cov);
        List<Item> all = new ArrayList<>(watch);
        all.addAll(links);
        all.addAll(fresh);
        all.addAll(more);
        return new Result(since, all, watch.size(), links.size(), fresh.size(), more.size());
    }

    private static String shorten(String s) { return s.length() > 60 ? s.substring(0, 58) + "…" : s; }
}
