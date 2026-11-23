package com.linkeddesk.analysis;

import com.linkeddesk.Check;
import com.linkeddesk.feeds.FeedParser;
import com.linkeddesk.feeds.FeedSource;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.model.Storyline;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * P2 linking quality: phrases (ANL-F05), aliases (ANL-F06), storylines (ANL-F07), link verdicts (ANL-F08),
 * desk rules (ANL-F09), stop words and the words-behind-links report (ANL-F10), and calibration on the
 * labelled real-news sample (ANL-F04).
 */
public final class AnalysisTest {
    private static final long H = 3_600_000L, DAY = 24 * H;
    private static final long T0 = 1_795_000_000_000L; // a fixed time in Nov 2026
    private static int n;

    static Article art(String title, String summary, String source, Category desk, long t) {
        String id = "t" + (++n);
        return new Article(id, "https://example.org/" + id, title, summary, source, desk, t, t);
    }

    static List<Article> sampleFeeds() throws Exception {
        String[][] list = {{"World", "BBC World", "bbc_world.xml"}, {"World", "Al Jazeera", "aljazeera.xml"}, {"Finance", "CNBC", "cnbc.xml"},
                {"Politics", "Politico", "politico.atom"}, {"Politics", "NPR Politics", "npr_politics.xml"}, {"Society", "Deutsche Welle", "dw.rdf"},
                {"World", "Google News World", "gnews.xml"}};
        List<Article> all = new ArrayList<>();
        for (String[] f : list)
            all.addAll(FeedParser.parse(Files.readAllBytes(Path.of("test/resources", f[2])), new FeedSource(Category.parse(f[0]), f[1], "x"), T0));
        return all;
    }

    static StoryCluster find(Analyzer.Result r, String headlinePart) {
        for (StoryCluster c : r.stories()) for (Article a : c.articles) if (a.title.contains(headlinePart)) return c;
        throw new AssertionError("no story with " + headlinePart);
    }

    static boolean linked(StoryCluster a, StoryCluster b) {
        for (StoryLink l : a.links) if (l.to() == b) return true;
        return false;
    }

    static List<String> terms(String text, Lexicon lex) {
        List<String> out = new ArrayList<>();
        for (Text.Token t : Text.tokens(text, lex)) out.add(t.term());
        return out;
    }

    public static void run(Check c) throws Exception {
        Lexicon lex = Lexicon.defaults();

        // ------------------------------------------------------------------ ANL-F05
        c.suite("Multi-word names and phrases (ANL-F05)");
        List<String> t1 = terms("Tankers were hit by mines in the Strait of Hormuz on Thursday.", Lexicon.EMPTY);
        c.that(t1.contains("strait of hormuz"), "'Strait of Hormuz' is one term (joiner 'of' inside a name)");
        c.that(t1.contains("hormuz") && t1.contains("tanker"), "its single words are still indexed alongside");
        c.that(terms("Shares rose after Jensen Huang returned to Nvidia.", Lexicon.EMPTY).contains("jensen huang"), "'Jensen Huang' is one term");
        c.that(terms("The Bank of England held rates.", Lexicon.EMPTY).contains("bank of england"), "'Bank of England' is one term");
        c.that(terms("Democrats want to end the Iran war.", Lexicon.EMPTY).contains("iran war"), "a name plus an event word ('Iran war') is one subject");
        List<Text.Token> tc = Text.tokens("Belgian General Strike Against Austerity Paralyzes Air Travel", Lexicon.EMPTY);
        c.that(tc.stream().filter(Text.Token::phrase).allMatch(Text.Token::titleCase), "capitalized runs in a Title Case Headline are only tentative names");
        List<String> hy = terms("Xi-Trump-Putin summit", Lexicon.EMPTY);
        c.that(hy.containsAll(List.of("trump", "putin")) && !hy.contains("xi-trump-putin"), "hyphenated names are split ('Xi-Trump-Putin' -> Trump, Putin)");
        List<Text.Token> southKorea = Text.tokens("Markets in South Korea fell.", Lexicon.EMPTY);
        c.that(southKorea.stream().anyMatch(t -> t.term().equals("south korea")) && southKorea.stream().noneMatch(t -> t.term().equals("south") && t.entity()),
                "'South' inside 'South Korea' is a word, not a name on its own");
        Analyzer.Result sample = Analyzer.analyze(sampleFeeds());
        StoryCluster hormuz = find(sample, "Five oil tankers ablaze");
        long iranWar = hormuz.links.stream().filter(l -> l.to().headline.contains("Iran war")).count();
        c.that(iranWar >= 2, "regression: the Hormuz tanker story now links to the Iran-war politics stories (" + iranWar + " links)");
        c.that(hormuz.links.stream().filter(l -> l.to().headline.contains("Iran war")).allMatch(l -> l.sharedTerms().contains("Iran war")),
                "... and the link shows 'Iran war' as a shared term");
        c.that(hormuz.keyTerms.get(0).equals("Strait of Hormuz"), "the story's top key term is 'Strait of Hormuz'");
        c.eq(20, sample.stories().size(), "the sample still forms 20 stories");

        // ------------------------------------------------------------------ ANL-F06
        c.suite("Editable alias list (ANL-F06)");
        c.eq(lex.canonical("federal reserve"), lex.canonical(Lexicon.key("Fed")), "'Fed' and 'Federal Reserve' are the same");
        c.eq(lex.canonical(Lexicon.key("United States")), lex.canonical(Lexicon.key("U.S.")), "'U.S.' and 'United States' are the same");
        c.that(terms("The Fed raised rates.", lex).contains("federal reserve"), "'Fed' in text counts as Federal Reserve");
        c.that(!terms("Voters are fed up with prices.", lex).contains("federal reserve"), "lower-case 'fed up' is not the Fed");
        c.that(terms("WHO warns on Ebola", lex).contains("world health organization"), "'WHO' in capitals is the World Health Organization");
        c.that(!terms("Who is Alberto Feijoo?", lex).contains("world health organization"), "'Who' as a question word is not");
        c.that(terms("Fighting in DRC disrupts response", lex).contains("dr congo") && terms("Democratic Republic of Congo outbreak", lex).contains("dr congo"),
                "'DRC' and 'Democratic Republic of Congo' are both DR Congo");
        List<Article> fed = List.of(
                art("Fed holds rates steady as Powell signals patience", "The Fed kept rates unchanged; Powell said inflation is cooling.", "Reuters", Category.FINANCE, T0),
                art("Federal Reserve chair Powell faces pressure over rate path", "Lawmakers pressed the Federal Reserve on Powell's rate path.", "AP", Category.FINANCE, T0 + 50 * H));
        Analyzer.Result rf = Analyzer.analyze(fed);
        StoryCluster f1 = find(rf, "Fed holds"), f2 = find(rf, "Federal Reserve chair");
        c.that(f1 != f2 && linked(f1, f2), "stories using different names ('Fed' / 'Federal Reserve') link");
        c.that(f1.links.get(0).sharedTerms().contains("Federal Reserve"), "the shared term shows the full name ('Federal Reserve')");
        Path dir = Files.createTempDirectory("ld-lex");
        Lexicon loaded = Lexicon.load(dir.resolve("aliases.txt"), dir.resolve("stopwords.txt"));
        c.that(Files.exists(dir.resolve("aliases.txt")) && Files.exists(dir.resolve("stopwords.txt")), "aliases.txt and stopwords.txt are created with the defaults");
        c.eq(lex.aliasCount(), loaded.aliasCount(), "the created aliases.txt holds the built-in list");
        Files.writeString(dir.resolve("aliases.txt"), "Redstone Arsenal = RSA, Redstone\nbroken line without equals\n");
        Lexicon mine = Lexicon.load(dir.resolve("aliases.txt"), dir.resolve("stopwords.txt"));
        c.that(terms("Army moves command to Redstone", mine).contains("redstone arsenal"), "your own alias ('Redstone' = 'Redstone Arsenal') applies");
        c.eq(1, mine.problems().size(), "a line without '=' is reported, not fatal");

        // ------------------------------------------------------------------ ANL-F10
        c.suite("Stop words and the words-behind-links report (ANL-F10)");
        List<Article> gen = List.of(
                art("Acme Corp shuts Ohio plant", "Acme Corp said the Ohio plant will close.", "A", Category.FINANCE, T0),
                art("Acme Corp unveils drone delivery in Texas", "Acme Corp launched drones in Texas.", "B", Category.TECH, T0 + 30 * H),
                art("Acme Corp sponsors marathon in Boston", "Acme Corp will sponsor the Boston marathon.", "C", Category.SOCIETY, T0 + 60 * H));
        Analyzer.Result rg = Analyzer.analyze(gen);
        long acmeLinks = rg.stories().stream().mapToLong(s -> s.links.size()).sum() / 2;
        c.that(acmeLinks >= 2, "unrelated stories linked only by 'Acme Corp' (" + acmeLinks + " links)");
        c.that(rg.linkTerms().stream().anyMatch(u -> u.term().startsWith("Acme") && u.links() >= 2), "the report shows 'Acme…' behind those links");
        Lexicon.addStopWords(dir.resolve("stopwords.txt"), List.of("Acme", "Corp", "acme corp"));
        Lexicon withStop = Lexicon.load(dir.resolve("aliases.txt"), dir.resolve("stopwords.txt"));
        Analyzer.Result rg2 = Analyzer.analyze(gen, Analyzer.Options.DEFAULT.with(withStop));
        c.eq(0L, rg2.stories().stream().mapToLong(s -> s.links.size()).sum(), "after adding the words to stopwords.txt, the links they caused are gone");
        c.that(Files.readString(dir.resolve("stopwords.txt")).contains("acme corp"), "stopwords.txt keeps your words");
        Lexicon.addStopWords(dir.resolve("stopwords.txt"), List.of("Acme"));
        c.eq(1L, Files.readString(dir.resolve("stopwords.txt")).lines().filter(l -> l.equals("acme")).count(), "adding a word twice keeps one line");

        // ------------------------------------------------------------------ ANL-F07
        c.suite("Storylines: one event across days (ANL-F07)");
        List<Article> fire = new ArrayList<>(List.of(
                art("Fire at Indonesian candy factory leaves 3 dead, dozens missing", "A fire swept through a candy factory near Jakarta, Indonesia.", "AP", Category.SOCIETY, T0),
                art("Indonesia candy factory fire: 3 dead and 37 unaccounted for", "Rescuers in Indonesia search the candy factory.", "Reuters", Category.SOCIETY, T0 + 2 * H),
                art("Indonesia candy factory fire death toll rises to 24", "Rescuers in Indonesia recovered 24 bodies from the candy factory near Jakarta.", "AP", Category.SOCIETY, T0 + 3 * DAY),
                art("Indonesia candy factory fire kills 24 as search continues", "The death toll at the Indonesian candy factory rose to 24.", "BBC", Category.SOCIETY, T0 + 3 * DAY + 3 * H),
                art("Floods hit northern Italy after record rain", "Floods in Italy forced evacuations after record rain.", "DW", Category.SOCIETY, T0 + 3 * DAY)));
        Analyzer.Result rl = Analyzer.analyze(fire);
        StoryCluster day1 = find(rl, "leaves 3 dead"), day3 = find(rl, "death toll rises");
        c.that(day1 != day3, "day 1 and day 3 (no coverage on day 2) are separate stories");
        c.that(day3.continues == day1, "day 3 is connected to day 1 as the same storyline");
        c.eq(1, rl.storylines().size(), "one storyline found");
        Storyline sl = rl.storylines().get(0);
        c.that(sl.stories().get(0) == day1 && sl.latest() == day3 && sl.days() >= 3, "storyline runs oldest first over " + sl.days() + " days");
        c.that(find(rl, "Floods hit").continues == null, "an unrelated story on day 3 isn't part of it");
        c.that(day1.storyline().size() == 2 && day3.storyline().size() == 2, "either story leads to the whole storyline");

        // ------------------------------------------------------------------ ANL-F08
        c.suite("Right/wrong verdicts on links (ANL-F08)");
        Feedback fb = new Feedback();
        StoryCluster acme1 = find(rg, "shuts Ohio"), acme2 = find(rg, "drone delivery");
        StoryLink wrongLink = acme1.links.stream().filter(l -> l.to() == acme2).findFirst().orElseThrow();
        fb.add(new Feedback.Entry(Feedback.Kind.WRONG, T0, Feedback.ids(acme1), Feedback.ids(acme2), wrongLink.sharedTerms(), acme1.headline, acme2.headline));
        Analyzer.Options withFb = new Analyzer.Options(lex, Thresholds.defaults(), fb, DeskRules.NONE);
        Analyzer.Result rw = Analyzer.analyze(gen, withFb);
        c.that(!linked(find(rw, "shuts Ohio"), find(rw, "drone delivery")), "a link marked wrong is hidden");
        c.eq(1, find(rw, "shuts Ohio").hiddenLinks, "... and counted as hidden on the story");
        List<Article> more = new ArrayList<>(gen);
        more.add(art("Acme Corp Ohio plant closure leaves 400 jobless", "Workers at the Acme Corp Ohio plant lose jobs.", "D", Category.FINANCE, T0 + 5 * H));
        Analyzer.Result rw2 = Analyzer.analyze(more, withFb);
        c.eq(2, find(rw2, "shuts Ohio").articles.size(), "after a new download the Ohio story has grown…");
        c.that(!linked(find(rw2, "shuts Ohio"), find(rw2, "drone delivery")), "… and the rejected link still doesn't come back");
        StoryLink marathon = find(rw, "marathon").links.stream().filter(l -> l.to() == find(rw, "drone delivery")).findFirst().orElse(null);
        StoryLink before = find(rg, "marathon").links.stream().filter(l -> l.to() == find(rg, "drone delivery")).findFirst().orElse(null);
        c.that(before != null && (marathon == null || marathon.score() < before.score() - 1e-9),
                "other links resting on the same two words count less after a wrong verdict");
        Feedback ok = new Feedback();
        StoryCluster nvidia = find(sample, "Nvidia shares plunge"), fedStory = find(sample, "Fed officials weigh");
        c.that(!linked(nvidia, fedStory), "(Nvidia and Fed stories aren't linked by the words alone)");
        ok.add(new Feedback.Entry(Feedback.Kind.RIGHT, T0, Feedback.ids(nvidia), Feedback.ids(fedStory), List.of(), nvidia.headline, fedStory.headline));
        Analyzer.Result rr = Analyzer.analyze(sampleFeeds(), new Analyzer.Options(lex, Thresholds.defaults(), ok, DeskRules.NONE));
        StoryCluster nv2 = find(rr, "Nvidia shares plunge");
        c.that(nv2.links.stream().anyMatch(l -> l.confirmed() && l.to().headline.contains("Fed officials")), "a link marked right is always shown, marked as confirmed");
        c.that(nv2.links.get(0).confirmed(), "confirmed links are listed first");
        Feedback.Entry found = ok.find(nv2, find(rr, "Fed officials weigh"));
        c.that(found != null && found.kind() == Feedback.Kind.RIGHT, "the verdict is found again for undo");
        ok.remove(found);
        c.that(ok.isEmpty(), "undo removes it");
        // Not this story
        List<Article> mix = List.of(
                art("Storm Bruna floods Lisbon streets", "Storm Bruna brought floods to Lisbon.", "A", Category.SOCIETY, T0),
                art("Storm Bruna floods Lisbon metro", "Storm Bruna flooded the Lisbon metro.", "B", Category.SOCIETY, T0 + H));
        Analyzer.Result rm = Analyzer.analyze(mix);
        c.eq(1, rm.stories().size(), "(two articles grouped into one story)");
        Feedback split = new Feedback();
        split.add(new Feedback.Entry(Feedback.Kind.SPLIT, T0, Set.of(mix.get(1).id), Set.of(mix.get(0).id), List.of(), "", ""));
        c.eq(2, Analyzer.analyze(mix, new Analyzer.Options(lex, Thresholds.defaults(), split, DeskRules.NONE)).stories().size(),
                "'Not this story' keeps the article out of that story");
        Path fbFile = dir.resolve("link-feedback.tsv");
        fb.add(split.entries().get(0));
        fb.save(fbFile);
        Feedback back = Feedback.load(fbFile);
        c.eq(2, back.entries().size(), "verdicts saved to link-feedback.tsv and read back");
        c.eq(fb.entries().get(0).terms(), back.entries().get(0).terms(), "shared words kept");
        fb.add(new Feedback.Entry(Feedback.Kind.RIGHT, T0, Feedback.ids(acme1), Feedback.ids(acme2), List.of(), "", ""));
        c.eq(1L, fb.count(Feedback.Kind.RIGHT) + fb.count(Feedback.Kind.WRONG), "a new verdict on the same pair replaces the old one");

        // ------------------------------------------------------------------ ANL-F09
        c.suite("Desk corrections (ANL-F09)");
        List<Article> desks = new ArrayList<>(List.of(
                art("Sanctions on Russian oil hit tanker rates", "New sanctions on Russia's oil exports pushed tanker rates higher.", "Reuters", Category.WORLD, T0),
                art("Bitcoin slides below $80,000", "Bitcoin fell as crypto investors sold.", "BBC", Category.WORLD, T0 + H),
                art("Space Force awards launch contracts", "The Space Force awarded new launch contracts.", "Defense News", Category.TECH, T0 + 2 * H)));
        Analyzer.Result rd0 = Analyzer.analyze(desks);
        StoryCluster sanctions = find(rd0, "Sanctions on Russian");
        DeskRules rules = new DeskRules();
        rules.add(new DeskRules.Rule(DeskRules.Kind.STORY, String.join(",", Feedback.ids(sanctions)), Category.FINANCE, Category.WORLD, sanctions.headline));
        rules.add(new DeskRules.Rule(DeskRules.Kind.SOURCE, "Defense News", Category.WORLD, null, "Defense News"));
        rules.add(new DeskRules.Rule(DeskRules.Kind.KEYWORD, "bitcoin", Category.FINANCE, null, "bitcoin"));
        Analyzer.Options dOpt = new Analyzer.Options(lex, Thresholds.defaults(), Feedback.NONE, rules);
        Analyzer.Result rd = Analyzer.analyze(desks, dOpt);
        StoryCluster s1 = find(rd, "Sanctions on Russian");
        c.that(s1.category == Category.FINANCE && s1.also == Category.WORLD && s1.deskSetByYou, "a story moved to Finance, also shown in World");
        c.that(s1.inDesk(Category.FINANCE) && s1.inDesk(Category.WORLD) && !s1.inDesk(Category.POLITICS), "it shows in both desks' lists");
        c.eq(Category.WORLD, find(rd, "Space Force").category, "an outlet rule moves everything from Defense News");
        c.eq(Category.FINANCE, find(rd, "Bitcoin").category, "a headline-word rule moves every Bitcoin story");
        desks.add(art("Tanker rates jump as Russia oil sanctions bite", "Sanctions on Russia's oil keep tanker rates high.", "AP", Category.WORLD, T0 + 6 * H));
        StoryCluster s1b = find(Analyzer.analyze(desks, dOpt), "Sanctions on Russian");
        c.that(s1b.articles.size() == 2 && s1b.category == Category.FINANCE, "the moved story stays moved after the next download adds to it");
        Path rf2 = dir.resolve("desk-rules.tsv");
        rules.save(rf2);
        DeskRules rback = DeskRules.load(rf2);
        c.eq(3, rback.rules().size(), "desk rules saved to desk-rules.tsv and read back");
        rules.add(new DeskRules.Rule(DeskRules.Kind.SOURCE, "defense news", Category.TECH, null, "x"));
        c.eq(3, rules.rules().size(), "a new rule for the same outlet replaces the old one");
        List<Article> both = List.of(
                art("Oil prices jump after strike on refinery", "Oil prices rose after a drone strike on a refinery.", "CNBC", Category.FINANCE, T0),
                art("Oil prices jump as refinery strike shuts output", "A drone strike on the refinery lifted oil prices.", "BBC", Category.WORLD, T0 + H),
                art("Drone strike on refinery lifts oil prices", "Oil prices rose after the drone strike on the refinery.", "Al Jazeera", Category.WORLD, T0 + 2 * H),
                art("Refinery strike: oil prices climb", "Oil prices climbed after the refinery strike.", "MarketWatch", Category.FINANCE, T0 + 3 * H));
        StoryCluster auto = Analyzer.analyze(both).stories().get(0);
        c.that(auto.articles.size() == 4 && auto.also != null && auto.also != auto.category, "a story covered by two desks' feeds shows in both automatically ("
                + auto.category.label() + " + " + (auto.also == null ? "-" : auto.also.label()) + ")");

        // ------------------------------------------------------------------ ANL-F04
        c.suite("Calibration on labelled real news (ANL-F04)");
        Calibrator.Sample real = Calibrator.bundled();
        long events = real.items().stream().map(Calibrator.Labelled::label).distinct().count();
        long outlets = real.items().stream().map(l -> l.article().source).distinct().count();
        c.that(real.items().size() >= 150 && events >= 60 && outlets >= 50, "labelled sample: " + real.items().size() + " real articles, "
                + events + " events, " + outlets + " outlets");
        Calibrator.Metrics m = Calibrator.evaluate(real, Analyzer.Options.DEFAULT);
        c.that(m.groupPrecision() >= 0.90, String.format("same-event grouping precision %.0f%% (target 90%%)", 100 * m.groupPrecision()));
        c.that(m.linkPrecision() >= 0.80, String.format("link precision %.0f%% (target 80%%)", 100 * m.linkPrecision()));
        c.that(m.groupRecall() >= 0.80, String.format("grouping recall %.0f%%", 100 * m.groupRecall()));
        c.that(m.linkRecall() >= 0.40, String.format("link recall %.0f%%", 100 * m.linkRecall()));
        c.that(m.storylinePrecision() >= 0.90, "storylines: " + m.storylineCorrect() + " of " + m.storylineSteps() + " right");
        Calibrator.Metrics old = Calibrator.evaluate(real, new Analyzer.Options(Lexicon.EMPTY, Thresholds.uniform(0.36, 0.10), Feedback.NONE, DeskRules.NONE));
        c.that(m.groupRecall() > old.groupRecall() + 0.10 && old.meetsTargets(),
                String.format("aliases and per-desk settings group more of each event than without them (recall %.0f%% -> %.0f%%)",
                        100 * old.groupRecall(), 100 * m.groupRecall()));
        Calibrator.Suggestion sug = Calibrator.tune(real, List.of(), new Analyzer.Options(lex, Thresholds.uniform(0.24, 0.06), Feedback.NONE, DeskRules.NONE), x -> { }, () -> false);
        c.that(sug.sample().meetsTargets(), "Find best settings, started from poor settings, ends up meeting both targets ("
                + Math.round(100 * sug.sample().groupPrecision()) + "% / " + Math.round(100 * sug.sample().linkPrecision()) + "%)");
        Path cal = dir.resolve("calibration.properties");
        sug.thresholds().save(cal);
        c.eq(sug.thresholds(), Thresholds.load(cal), "settings saved to calibration.properties and read back");
        Files.writeString(cal, "sameEvent.WORLD=abc\nlink.FINANCE=7\n");
        Thresholds bad = Thresholds.load(cal);
        c.that(bad.sameEvent(Category.WORLD) == Thresholds.defaults().sameEvent(Category.WORLD) && bad.link(Category.FINANCE) == Thresholds.defaults().link(Category.FINANCE),
                "unreadable or out-of-range values fall back to the defaults");
        // Your verdicts are scored on your own archive and steer the search.
        List<Article> mine2 = real.articles();
        Analyzer.Result base = Analyzer.analyze(mine2);
        Feedback v = new Feedback();
        int wrongs = 0;
        for (StoryCluster s : base.stories()) for (StoryLink l : s.links) {
            if (System.identityHashCode(s) > System.identityHashCode(l.to())) continue;  // each pair once
            String la = label(real, s), lb = label(real, l.to());
            if (!real.related(la, lb) && wrongs < 5) {
                v.add(new Feedback.Entry(Feedback.Kind.WRONG, T0, Feedback.ids(s), Feedback.ids(l.to()), l.sharedTerms(), s.headline, l.to().headline));
                wrongs++;
            }
        }
        Calibrator.VerdictScore vs = Calibrator.evaluate(mine2, v, Analyzer.Options.DEFAULT);
        c.that(vs.wrong() == wrongs && vs.wrongShown() == wrongs, "your " + wrongs + " wrong verdicts are counted against the current settings (" + vs.wrongShown() + " would show)");
        Calibrator.Suggestion withYours = Calibrator.tune(real, mine2, new Analyzer.Options(lex, Thresholds.defaults(), v, DeskRules.NONE), x -> { }, () -> false);
        c.that(withYours.yours().wrongShown() < wrongs && withYours.sample().meetsTargets(),
                "with your verdicts, Find best settings avoids " + (wrongs - withYours.yours().wrongShown()) + " of them and still meets the targets");
    }

    private static String label(Calibrator.Sample s, StoryCluster c) {
        java.util.Map<String, Integer> v = new java.util.HashMap<>();
        for (Article a : c.articles) for (Calibrator.Labelled l : s.items()) if (l.article().id.equals(a.id)) v.merge(l.topic(), 1, Integer::sum);
        return v.entrySet().stream().max(java.util.Map.Entry.comparingByValue()).get().getKey();
    }
}
