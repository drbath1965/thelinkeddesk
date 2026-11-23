package com.linkeddesk.analysis;

import com.linkeddesk.Check;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.store.ReadState;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P3 reading features without the window: read/starred state (UI-F07), source diversity (UI-F08),
 * the daily briefing (UI-F04), search syntax (UI-F10), the connection-map layout (UI-F05) and countries for the
 * world map (UI-F11). The window itself is checked on a virtual screen (screenshots).
 */
public final class ReadingTest {
    private static final long H = 3_600_000L, DAY = 24 * H;
    private static int n;

    static Article art(String title, String summary, String source, Category desk, long t) {
        String id = "r" + (++n);
        return new Article(id, "https://example.org/" + id, title, summary, source, desk, t, t);
    }

    static StoryCluster find(Analyzer.Result r, String part) {
        for (StoryCluster c : r.stories()) for (Article a : c.articles) if (a.title.contains(part)) return c;
        throw new AssertionError("no story with " + part);
    }

    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();

        // ------------------------------------------------------------------ UI-F07
        c.suite("Read, starred and saved stories (UI-F07)");
        Path dir = Files.createTempDirectory("ld-read");
        List<Article> day1 = new ArrayList<>(List.of(
                art("Storm Bruna floods Lisbon streets", "Storm Bruna brought floods to Lisbon.", "BBC World", Category.SOCIETY, now - 30 * H),
                art("Storm Bruna floods Lisbon metro", "Storm Bruna flooded the Lisbon metro.", "Euronews", Category.SOCIETY, now - 29 * H),
                art("Nvidia shares soar on Huang return", "Nvidia shares jumped after Jensen Huang came back.", "CNBC", Category.FINANCE, now - 20 * H)));
        Analyzer.Result r1 = Analyzer.analyze(day1);
        StoryCluster storm = find(r1, "Storm Bruna floods Lisbon streets");
        ReadState rs = new ReadState(dir).load();
        c.that(rs.isUnread(storm), "a new story is unread");
        rs.markRead(storm);
        rs.star(storm);
        ReadState again = new ReadState(dir).load();
        c.that(!again.isUnread(storm) && again.isStarred(storm), "read and starred state survive a restart (read.txt, starred.tsv)");
        List<Article> day2 = new ArrayList<>(day1);
        day2.add(art("Storm Bruna floods Lisbon again as river rises", "Storm Bruna floods return to Lisbon.", "Reuters", Category.SOCIETY, now - 10 * H));
        Analyzer.Result r2 = Analyzer.analyze(day2);
        StoryCluster storm2 = find(r2, "Storm Bruna floods Lisbon streets");
        c.that(storm2.articles.size() == 3, "(the next download adds an article to the story)");
        c.that(again.isStarred(storm2), "a starred story stays starred as it grows");
        c.that(again.isUnread(storm2) && again.hasNewSinceRead(storm2) && again.unreadCount(storm2) == 1,
                "a story you read shows its new coverage as unread (1 new article)");
        c.that(again.isUnread(find(r2, "Nvidia")), "a story you never opened is still unread");
        again.markUnread(storm2);
        c.that(new ReadState(dir).load().unreadCount(storm2) == 3, "Mark unread forgets it as read (also after a restart)");
        again.unstar(storm2);
        c.that(!new ReadState(dir).load().isStarred(storm2) && new ReadState(dir).load().stars().isEmpty(), "unstar removes it from Saved");
        Path bigDir = Files.createTempDirectory("ld-read-big");
        ReadState big = new ReadState(bigDir).load();
        List<Article> many = new ArrayList<>();
        for (int i = 0; i < 50_000; i++) many.add(new Article("id" + i, "u", "t", "", "s", Category.WORLD, now, now));
        long t0 = System.nanoTime();
        big.markRead(many);
        ReadState bigBack = new ReadState(bigDir).load();
        c.that(bigBack.readCount() >= 50_000 && (System.nanoTime() - t0) / 1e9 < 2, "50,000 read articles saved and loaded in under 2 s");

        // ------------------------------------------------------------------ UI-F08
        c.suite("Source diversity (UI-F08)");
        Regions reg = Regions.defaults();
        c.eq("Europe", reg.of("BBC World"), "BBC is Europe");
        c.eq("Europe", reg.of("Politico Europe"), "Politico Europe is Europe (longest name wins)…");
        c.eq("North America", reg.of("Politico"), "… and Politico is North America");
        c.eq("Middle East", reg.of("Al Jazeera"), "Al Jazeera is Middle East");
        c.eq("North America", reg.of("CBC.ca"), "CBC.ca is North America");
        c.eq("Unknown", reg.of("notebookcheck"), "names match whole words only ('note' is not 'notebookcheck')");
        c.eq(Regions.WIRE, reg.of("Reuters"), "Reuters is a wire service");
        List<String> missing = new ArrayList<>();
        for (String f : List.of("AllAfrica", "Africanews", "Financial Times Markets", "Dawn (Pakistan)", "The Hindu International", "Times of Israel",
                "Middle East Eye", "CNA (Singapore)", "The Diplomat", "Kyiv Independent", "The Moscow Times", "MercoPress (South America)", "Politico Europe",
                "Defense News", "Breaking Defense", "SpaceNews", "BBC World", "Al Jazeera", "NPR World", "The Guardian World", "Deutsche Welle", "France 24",
                "NYT World", "CBC World", "ABC Australia", "Times of India World", "South China Morning Post", "Japan Times", "Euronews", "CNBC Top News",
                "MarketWatch", "The Hill")) if (reg.of(f).equals(Regions.UNKNOWN)) missing.add(f);
        c.that(missing.isEmpty(), "every named default feed has a region" + (missing.isEmpty() ? "" : " (missing " + missing + ")"));
        Calibrator.Sample real = Calibrator.bundled();
        long known = real.items().stream().map(l -> l.article().source).distinct().filter(o -> !reg.of(o).equals(Regions.UNKNOWN)).count();
        long outlets = real.items().stream().map(l -> l.article().source).distinct().count();
        c.that(known >= outlets - 2, "outlets in the real-news sample with a region: " + known + " of " + outlets);
        Analyzer.Result rr = Analyzer.analyze(real.articles());
        StoryCluster libya = rr.stories().stream().filter(s -> s.headline.contains("Libya") && s.articles.size() > 5).findFirst().orElseThrow();
        Regions.Spread sp = reg.spread(libya);
        c.that(sp.regionCount() >= 3 && !sp.singleSource() && !sp.oneRegion(), "Ukraine-Libya story: " + sp.label() + " " + sp.regions());
        StoryCluster single = rr.stories().stream().filter(s -> s.sourceCount() == 1).findFirst().orElseThrow();
        c.that(reg.spread(single).singleSource() && reg.spread(single).label().equals("Single source"), "a one-outlet story is flagged 'Single source'");
        StoryCluster us = Analyzer.analyze(List.of(
                art("Senate passes funding bill", "The Senate passed the bill.", "NPR Politics", Category.POLITICS, now),
                art("Senate passes funding bill after late vote", "The Senate passed the funding bill.", "The Hill", Category.POLITICS, now + H))).stories().get(0);
        c.that(reg.spread(us).oneRegion(), "two outlets from the same region are flagged 'one region'");
        Regions mine = Regions.parse("Madison Courier = North America\n");
        c.eq("North America", mine.of("The Madison Courier"), "regions.txt: your own outlets");

        // ------------------------------------------------------------------ UI-F04
        c.suite("Daily briefing (UI-F04)");
        List<Article> b = new ArrayList<>();
        for (int i = 0; i < 6; i++) b.add(art("Oil prices jump as Iran tanker attacks spread " + i, "Oil prices jumped as tanker attacks in the Strait of Hormuz spread.", "Outlet " + i, Category.FINANCE, now - (2 + i) * H));
        for (int i = 0; i < 3; i++) b.add(art("Senate votes on Iran war powers " + i, "The Senate voted on Iran war powers after tanker attacks.", "Paper " + i, Category.POLITICS, now - (3 + i) * H));
        b.add(art("Old story about a museum opening", "A museum opened in Oslo.", "Paper X", Category.SOCIETY, now - 3 * DAY));
        b.add(art("Small story about a local market", "A market opened.", "Paper Y", Category.FINANCE, now - 5 * H));
        Analyzer.Result br = Analyzer.analyze(b);
        Briefing brief = Briefing.build(br, now);
        List<Briefing.Ranked> fin = brief.desks().get(Category.FINANCE);
        c.that(!fin.isEmpty() && fin.get(0).story().headline.contains("Oil prices"), "Finance: the most covered story is first");
        c.that(fin.get(0).last24h() == 6 && fin.get(0).last6h() >= 4, "it shows how fast coverage grew (6 in 24 h, " + fin.get(0).last6h() + " in the last 6 h)");
        c.that(brief.desks().values().stream().flatMap(List::stream).noneMatch(x -> x.story().headline.contains("museum")), "stories older than 24 hours are left out");
        c.that(brief.desks().values().stream().allMatch(l -> l.size() <= Briefing.PER_DESK), "at most 5 stories per desk");
        c.that(brief.newLinks().stream().anyMatch(l -> l.crossDesk()), "new links across desks are listed (" + brief.newLinks().size() + ")");
        c.eq(11 - 1, brief.articles(), "counts the articles of the last 24 hours");
        c.that(Briefing.build(Analyzer.analyze(List.of()), now).isEmpty(), "an empty day gives an empty briefing, not an error");

        // ------------------------------------------------------------------ UI-F10
        c.suite("Search syntax (UI-F10)");
        Article bbc = art("Nvidia shares soar", "Chip stocks rallied.", "BBC World", Category.FINANCE, java.time.LocalDate.of(2026, 11, 10).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 5 * H);
        Article fox = art("Nvidia shares soar again", "Chip stocks rallied.", "Fox Business", Category.TECH, java.time.LocalDate.of(2026, 11, 20).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 5 * H);
        java.util.function.BiPredicate<String, Article> m = (q, a) -> {
            SearchQuery sq = new SearchQuery(q);
            return (!sq.hasWords() || sq.matches(a)) && sq.fieldsMatch(a, reg::of, x -> x == bbc, x -> x == fox);
        };
        c.that(m.test("nvidia source:bbc", bbc) && !m.test("nvidia source:bbc", fox), "source:bbc");
        c.that(m.test("source:\"fox business\"", fox) && !m.test("source:\"fox business\"", bbc), "source:\"fox business\" (quoted, no other words)");
        c.that(m.test("desk:finance", bbc) && !m.test("desk:finance", fox), "desk:finance");
        c.that(m.test("nvidia after:2026-11-15", fox) && !m.test("nvidia after:2026-11-15", bbc), "after:2026-11-15");
        c.that(m.test("nvidia before:2026-11-15", bbc) && !m.test("nvidia before:2026-11-15", fox), "before:2026-11-15");
        c.that(m.test("region:europe", bbc) && !m.test("region:europe", fox), "region:europe");
        c.that(m.test("is:unread", fox) && !m.test("is:unread", bbc), "is:unread (BBC one is read)");
        c.that(m.test("is:starred", fox) && !m.test("is:starred", bbc), "is:starred");
        c.that(m.test("nvidia -source:bbc", fox) && !m.test("nvidia -source:bbc", bbc), "-source:bbc leaves an outlet out");
        Article recent = art("Nvidia news", "x", "CNBC", Category.FINANCE, now - 2 * DAY);
        c.that(m.test("after:7d", recent) && !m.test("after:1d", recent), "after:7d = within the last 7 days");
        SearchQuery bad = new SearchQuery("nvidia after:yesterday desk:sports");
        c.eq(2, bad.problems().size(), "filters it can't understand are reported (" + String.join("; ", bad.problems()) + ")");
        c.eq("nvidia OR anthropic \"jensen huang\"", new SearchQuery("nvidia OR anthropic source:bbc \"jensen huang\" after:3d").wordsText(),
                "live search and topics get the words without the filters");
        c.that(!new SearchQuery("source:bbc").isEmpty() && !new SearchQuery("source:bbc").hasWords(), "a search can be filters only");
        c.that(new SearchQuery("time:noon").hasWords(), "unknown 'name:' words are searched as plain words");

        // ------------------------------------------------------------------ UI-F05
        c.suite("Connection map layout (UI-F05)");
        GraphLayout g = GraphLayout.build(rr.stories(), s -> true, false, 500);
        c.that(g.nodes.size() > 10 && g.nodes.stream().allMatch(s -> !s.links.isEmpty()), "only linked stories by default (" + g.nodes.size() + " stories, " + g.edges.size() + " links)");
        c.that(g.edges.stream().allMatch(e -> e.a() != e.b()), "each link drawn once between two different stories");
        double minLinked = Double.MAX_VALUE, avgAll = 0;
        int pairs = 0;
        for (GraphLayout.Edge e : g.edges) minLinked = Math.min(minLinked, Math.hypot(g.x[e.a()] - g.x[e.b()], g.y[e.a()] - g.y[e.b()]));
        double sumLinked = 0;
        for (GraphLayout.Edge e : g.edges) sumLinked += Math.hypot(g.x[e.a()] - g.x[e.b()], g.y[e.a()] - g.y[e.b()]);
        for (int i = 0; i < g.nodes.size(); i++) for (int j = i + 1; j < g.nodes.size(); j++) { avgAll += Math.hypot(g.x[i] - g.x[j], g.y[i] - g.y[j]); pairs++; }
        c.that(sumLinked / g.edges.size() < avgAll / pairs, "linked stories end up closer together than stories in general");
        GraphLayout g2 = GraphLayout.build(rr.stories(), s -> true, false, 500);
        c.that(java.util.Arrays.equals(g.x, g2.x), "the same stories always give the same picture");
        c.eq(rr.stories().size(), GraphLayout.build(rr.stories(), s -> true, true, 0).nodes.size(), "'Include stories without links' shows all");
        GraphLayout fd = GraphLayout.build(rr.stories(), s -> s.inDesk(Category.FINANCE), false, 500);
        c.that(fd.nodes.stream().allMatch(s -> s.inDesk(Category.FINANCE)), "desk filter keeps only that desk (" + fd.nodes.size() + ")");
        GraphLayout capped = GraphLayout.build(rr.stories(), s -> true, true, 10);
        c.that(capped.nodes.size() == 10 && capped.matched == rr.stories().size(), "'Show up to' keeps the most connected stories");

        // ------------------------------------------------------------------ UI-F11
        c.suite("Countries for the world map (UI-F11)");
        Map<Countries.Country, Integer> mt = Countries.mentions("Zelenskyy says Ukraine sank a Russian warship off Libya; the Kremlin and the U.S. responded.");
        List<String> names = mt.keySet().stream().map(Countries.Country::name).toList();
        c.that(names.containsAll(List.of("Ukraine", "Russia", "Libya", "United States")), "names, nationality words, leaders and 'U.S.' found: " + names);
        c.that(Countries.mentions("Turkey prices rise before the holiday dinner").containsKey(Countries.byCode("TR"))
                && !Countries.mentions("roast turkey prices rise").containsKey(Countries.byCode("TR")), "'turkey' only counts with a capital");
        c.that(Countries.mentions("us officials said").isEmpty() && !Countries.mentions("US officials said").isEmpty(), "'us' isn't the US; 'US' is");
        c.that(Countries.mentions("Fighting in Democratic Republic of Congo's Ituri").containsKey(Countries.byCode("CD")), "multi-word names ('Democratic Republic of Congo')");
        Map<Countries.Country, List<StoryCluster>> idx = Countries.index(rr.stories());
        Countries.Country belgium = Countries.byCode("BE"), indonesia = Countries.byCode("ID"), ukraine = Countries.byCode("UA");
        c.that(idx.containsKey(belgium) && idx.get(belgium).stream().anyMatch(s -> s.headline.contains("Belgi")), "the Belgian strike is placed in Belgium");
        c.that(idx.containsKey(indonesia) && idx.get(indonesia).stream().anyMatch(s -> s.headline.toLowerCase().contains("candy")), "the factory fire is placed in Indonesia");
        c.that(idx.get(ukraine).size() >= 3, "Ukraine has " + idx.get(ukraine).size() + " stories");
        long placed = rr.stories().stream().filter(s -> !Countries.of(s).isEmpty()).count();
        c.that(placed >= rr.stories().size() * 0.6, "real-news sample: " + placed + " of " + rr.stories().size() + " stories placed on the map");
        c.that(Countries.all().size() >= 150, Countries.all().size() + " countries and territories with map positions");
        c.that(Countries.all().stream().allMatch(k -> Math.abs(k.lat()) <= 90 && Math.abs(k.lon()) <= 180), "every position is a valid latitude/longitude");
    }
}
