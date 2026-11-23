package com.linkeddesk.alerts;

import com.linkeddesk.Check;
import com.linkeddesk.analysis.Analyzer;
import com.linkeddesk.analysis.Calibrator;
import com.linkeddesk.analysis.Lexicon;
import com.linkeddesk.analysis.Regions;
import com.linkeddesk.app.Settings;
import com.linkeddesk.feeds.FeedParser;
import com.linkeddesk.feeds.FetchReport;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.service.NewsService;
import com.linkeddesk.store.ArticleStore;

import javax.net.ssl.*;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * P5 alerts without the window: watchlists (ALR-F01), notifications and quiet hours (ALR-F02), muted and weighted
 * sources (ALR-F03), the email digest over SMTP (ALR-F04, against a local stand-in mail server with plain,
 * STARTTLS and SSL connections) and tickers (ALR-F05).
 */
public final class AlertsTest {
    private static final long H = 3_600_000L;
    private static int n;

    static Article art(String title, String summary, String source, Category desk, long t) {
        String id = "al" + (++n);
        return new Article(id, "https://example.org/" + id, title, summary, source, desk, t, t);
    }

    public static void run(Check c) throws Exception {
        long now = System.currentTimeMillis();

        // ------------------------------------------------------------------ ALR-F01
        c.suite("Watchlists (ALR-F01)");
        Path home = Files.createTempDirectory("ld-alerts");
        Watches ws = new Watches(home).load(Lexicon.defaults());
        ws.save(List.of(new Watches.Watch("Fed", "Fed", true), new Watches.Watch("Hormuz", "\"strait of hormuz\" OR tankers", false),
                new Watches.Watch("Lockheed", "Lockheed -stock", true)));
        Watches again = new Watches(home).load(Lexicon.defaults());
        c.eq(3, again.all().size(), "the watchlist is saved in watchlist.txt and read back");
        c.that(!again.all().get(1).alert() && again.all().get(0).alert(), "the notify setting per watch is kept");
        Article fedLong = art("Federal Reserve holds rates as inflation cools", "The Federal Reserve left rates unchanged.", "Reuters", Category.FINANCE, now - 3 * H);
        Article fedShort = art("Fed signals a cut in March", "Powell said the Fed could cut.", "CNBC", Category.FINANCE, now - 2 * H);
        Article fedUp = art("Residents fed up with flooding", "People are fed up after the third flood.", "BBC", Category.SOCIETY, now - 2 * H);
        Article hormuz = art("Tanker seized near the Strait of Hormuz", "Iran seized a tanker.", "Al Jazeera", Category.WORLD, now - H);
        Article lmtStock = art("Lockheed stock falls after earnings", "Lockheed shares fell.", "CNBC", Category.FINANCE, now - H);
        Article lmt = art("Lockheed wins hypersonic contract", "The Army picked Lockheed Martin.", "Defense News", Category.TECH, now - H);
        c.that(names(again.matching(fedShort)).equals(List.of("Fed")), "a watch on \"Fed\" finds the Fed");
        c.that(names(again.matching(fedLong)).equals(List.of("Fed")), "… and \"Federal Reserve\" (through the alias list)");
        c.that(again.matching(fedUp).isEmpty(), "… but not \"fed up\" (lower case is not the Fed)");
        c.that(names(again.matching(hormuz)).equals(List.of("Hormuz")), "phrases and OR work like Search");
        c.that(again.matching(lmtStock).isEmpty() && names(again.matching(lmt)).equals(List.of("Lockheed")), "-word leaves articles out");
        var hits = again.newHits(List.of(fedLong, fedShort, fedUp, hormuz, lmt));
        c.that(hits.size() == 2 && hits.keySet().stream().noneMatch(w -> w.name().equals("Hormuz")),
                "new matches after a download are grouped by watch, only for watches that notify");
        c.eq(2, hits.entrySet().stream().filter(e -> e.getKey().name().equals("Fed")).findFirst().get().getValue().size(), "both Fed articles count");
        Analyzer.Result r = Analyzer.analyze(new ArrayList<>(List.of(fedLong, fedShort, fedUp, hormuz, lmt, lmtStock)));
        var byStory = again.stories(r.stories());
        c.that(byStory.keySet().stream().allMatch(s -> s.articles.stream().anyMatch(a -> !again.matching(a).isEmpty())) && byStory.size() >= 3,
                "stories are marked with the watches they match (for the highlight in Stories)");
        c.eq(0L, again.lastSeen("Fed"), "a watch never opened has no last visit");
        again.markSeen("Fed", now - 150 * 60_000L);
        Watches third = new Watches(home).load(Lexicon.defaults());
        c.eq(now - 150 * 60_000L, third.lastSeen("Fed"), "the last visit is remembered (watch-seen.tsv)");
        StoryCluster fedStory = byStory.keySet().stream().filter(s -> s.articles.contains(fedShort)).findFirst().get();
        c.that(fedStory.articles.stream().anyMatch(a -> a.fetched > third.lastSeen("Fed")), "articles fetched after the last visit count as new");
        c.that(third.load(Lexicon.defaults()) == third, "(re-reading is skipped while the files are unchanged)");

        // ------------------------------------------------------------------ ALR-F02
        c.suite("Notifications and quiet hours (ALR-F02)");
        var alert = Alerts.compose(hits, 0);
        c.that(alert.title().equals("3 new articles on your watchlist"), "one notification for the whole download: " + alert.title());
        c.that(alert.text().startsWith("Fed 2 · Lockheed 1") || alert.text().startsWith("Lockheed 1 · Fed 2"), "it lists the watches with counts");
        c.that(alert.text().contains("Federal Reserve holds rates"), "and the first headline");
        c.that(Alerts.compose(Map.of(), 0).isEmpty(), "nothing new, no notification");
        c.that(Alerts.compose(Map.of(), 2).title().equals("New links across desks"), "new links between desks can notify too (if switched on)");
        LocalTime ten = LocalTime.of(22, 0), seven = LocalTime.of(7, 0);
        c.that(Alerts.quiet(LocalTime.of(23, 30), ten, seven) && Alerts.quiet(LocalTime.of(6, 59), ten, seven) && !Alerts.quiet(LocalTime.of(7, 0), ten, seven)
                && !Alerts.quiet(LocalTime.of(12, 0), ten, seven), "quiet hours can run past midnight (22:00-07:00)");
        c.that(Alerts.quiet(LocalTime.of(13, 0), LocalTime.of(12, 0), LocalTime.of(14, 0)) && !Alerts.quiet(LocalTime.of(13, 0), null, null), "daytime quiet hours; none set");
        List<String> shown = new CopyOnWriteArrayList<>();
        Alerts.Display fake = (t, x) -> { shown.add(t + " | " + x); return true; };
        Alerts al = new Alerts(home, fake);
        c.that(al.deliver(alert, LocalTime.of(23, 0), ten, seven) == null && shown.isEmpty(), "during quiet hours nothing pops up");
        al.deliver(Alerts.compose(Map.of(new Watches.Watch("Fed", "Fed", true), List.of(fedShort)), 0), LocalTime.of(2, 0), ten, seven);
        c.eq(2, al.held().size(), "what arrives is kept (pending-alerts.txt)");
        var later = al.deliver(new Alerts.Alert("", "", 0), LocalTime.of(7, 5), ten, seven);
        c.that(later != null && later.title().startsWith("While you were away: 4 new articles") && shown.size() == 1,
                "after quiet hours everything kept is shown once, together: " + (later == null ? "" : later.title()));
        c.that(al.held().isEmpty(), "and then cleared");
        c.that(al.deliver(alert, LocalTime.of(12, 0), ten, seven) != null && shown.size() == 2, "outside quiet hours it shows at once");
        Alerts failing = new Alerts(home, (t, x) -> false);
        failing.deliver(alert, LocalTime.of(23, 0), ten, seven);
        failing.deliver(new Alerts.Alert("", "", 0), LocalTime.of(12, 0), ten, seven);
        c.that(failing.held().size() == 1, "if Windows can't show it, it's kept for the next time");
        Files.deleteIfExists(home.resolve("pending-alerts.txt"));

        // ------------------------------------------------------------------ ALR-F03
        c.suite("Muted and weighted sources (ALR-F03)");
        Sources src = new Sources(home).load();
        c.that(!src.any() && src.weight("CNBC") == 1 && !src.muted("CNBC"), "no rules: every outlet counts once");
        src.set("Daily Rumour", true, 1);
        src.set("Reuters", false, 2);
        src.set("CNBC", false, 0.5);
        Sources s2 = new Sources(home).load();
        c.that(s2.muted("Daily Rumour") && s2.weight("Reuters") == 2 && s2.weight("CNBC") == 0.5, "rules are saved in sources.txt");
        c.that(s2.muted("Daily Rumour (UK edition)") && !s2.muted("Rumour Mill"), "a rule matches the outlet name as whole words");
        Article rumour = art("Fed chair to resign, insiders claim", "Unconfirmed.", "Daily Rumour", Category.FINANCE, now - H);
        List<Article> mix = List.of(fedLong, fedShort, rumour);
        c.that(s2.visible(mix).size() == 2 && !s2.visible(mix).contains(rumour), "muted outlets disappear from lists");
        Article fedR = art("Federal Reserve holds rates steady as inflation cools", "The Federal Reserve held rates steady.", "Reuters", Category.FINANCE, now - 3 * H);
        Article fedC = art("Federal Reserve holds rates steady, inflation cools", "The Federal Reserve held rates steady on Wednesday.", "CNBC", Category.FINANCE, now - 2 * H);
        StoryCluster cl = Analyzer.analyze(new ArrayList<>(List.of(fedR, fedC))).stories().stream().filter(x -> x.articles.size() == 2).findFirst().orElse(null);
        c.that(cl != null && Math.abs(s2.coverage(cl) - 2.5) < 1e-9, "weights count toward coverage (Reuters 2 + CNBC 0.5)");
        s2.set("CNBC", false, 1);
        c.that(new Sources(home).load().rules().size() == 2, "weight 1 and not muted removes the rule");
        s2.set("Odd", false, 99);
        c.that(new Sources(home).load().weight("Odd") == 5, "weights are kept between 0.25 and 5");
        c.eq(2, (int) Sources.outlets(List.of(fedShort, lmtStock, rumour)).get("CNBC"), "outlets are counted for Tools > Sources");

        // ------------------------------------------------------------------ ALR-F05
        c.suite("Market and ticker watch (ALR-F05)");
        Tickers tk = new Tickers(home).load();
        c.that(tk.isEmpty(), "no tickers until you add some");
        tk.save(List.of(new Tickers.Ticker("NVDA", Tickers.knownNames("NVDA")), new Tickers.Ticker("LMT", Tickers.knownNames("LMT")),
                new Tickers.Ticker("BA", List.of()), new Tickers.Ticker("ACME", List.of("Acme Robotics"))));
        Tickers t2 = new Tickers(home).load();
        c.that(t2.all().get(0).names().contains("Nvidia") && t2.all().get(2).names().contains("Boeing"), "well-known symbols fill in their company names");
        c.that(Files.readString(home.resolve("tickers.txt")).contains("ACME = Acme Robotics\n") && Files.readString(home.resolve("tickers.txt")).contains("NVDA\n"),
                "tickers.txt stays short (names only where they differ)");
        c.eq(Set.of("NVDA"), t2.of(art("Chip stocks rally; $NVDA leads", "", "X", Category.FINANCE, now)), "$NVDA");
        c.eq(Set.of("NVDA"), t2.of(art("Nvidia Corp. (NVDA) beats estimates", "", "X", Category.FINANCE, now)), "(NVDA) and the company name");
        c.eq(Set.of("NVDA"), t2.of(art("Shares of NASDAQ: NVDA rose", "", "X", Category.FINANCE, now)), "NASDAQ: NVDA");
        c.eq(Set.of("LMT"), t2.of(art("Lockheed Martin's F-35 deliveries resume", "", "X", Category.TECH, now)), "Lockheed Martin's");
        c.eq(Set.of("BA"), t2.of(art("Boeing (NYSE: BA) to hire 2,000", "", "X", Category.FINANCE, now)), "a two-letter symbol in (NYSE: BA)");
        c.that(!t2.of(art("BA flights cancelled at Heathrow", "", "X", Category.WORLD, now)).contains("BA"), "a bare two-letter BA isn't taken as the ticker");
        c.that(t2.of(art("Acme Robotics ships its first arm", "", "X", Category.TECH, now)).contains("ACME"), "your own company names");
        c.that(t2.of(art("Nvidiaesque rally in small caps", "the lmt of growth", "X", Category.FINANCE, now)).isEmpty(), "no matches inside other words or lower case");

        // ------------------------------------------------------------------ the service: muting, the Finance tie, watch and ticker views, notify, email
        c.suite("Alerts in the app (ALR-F01-F05 together)");
        String oldHome = System.getProperty("linkeddesk.home");
        Path appHome = Files.createTempDirectory("ld-alerts-app");
        System.setProperty("linkeddesk.home", appHome.toString());
        Files.writeString(appHome.resolve(".feeds-v1.1"), "test"); Files.writeString(appHome.resolve(".feeds-v1.8"), "test");
        SmtpStandIn smtp = null;
        try {
            Settings st = Settings.load();
            ArticleStore store = new ArticleStore(appHome.resolve("data"));
            store.load();
            List<Article> real = new ArrayList<>();
            long newest = Calibrator.bundled().items().stream().mapToLong(l -> l.article().published).max().getAsLong();
            long shift = now - 3_600_000L - newest;
            for (Calibrator.Labelled l : Calibrator.bundled().items()) {
                Article x = l.article();
                real.add(new Article(FeedParser.idFor(x.url), x.url, x.title, x.summary, x.source, x.feedCategory, x.published + shift, x.fetched + shift));
            }
            List<Article> added = store.addAll(real);
            NewsService svc = new NewsService(store, st);
            int all = svc.analyze(7).stories().stream().mapToInt(s -> s.articles.size()).sum();
            String loud = Sources.outlets(real).keySet().iterator().next();
            svc.sources().set(loud, true, 1);
            Analyzer.Result muted = svc.analyze(7);
            int after = muted.stories().stream().mapToInt(s -> s.articles.size()).sum();
            c.that(after < all && muted.stories().stream().flatMap(s -> s.articles.stream()).noneMatch(a -> a.source.equals(loud)),
                    "a muted outlet (" + loud + ") is gone from the stories");
            c.eq(real.size(), store.size(), "… but its articles stay in the archive");
            c.that(svc.search(new com.linkeddesk.analysis.SearchQuery("source:\"" + loud + "\""), new NewsService.SearchFilter(0, 0, null, null)).total() > 0,
                    "and Search finds them when you ask for that outlet");
            svc.sources().set(loud, false, 1);

            // A watch over the real news
            Article sample = real.stream().filter(a -> a.title.contains("Iran") || a.title.contains("Ukraine") || a.title.contains("Trump")).findFirst().orElse(real.get(0));
            String word = sample.title.contains("Iran") ? "Iran" : sample.title.contains("Ukraine") ? "Ukraine" : sample.title.contains("Trump") ? "Trump" : sample.title.split(" ")[0];
            svc.watches().save(List.of(new Watches.Watch(word, word, true)));
            Analyzer.Result wsr = svc.watchStories(svc.watches().all().get(0), 30);
            c.that(!wsr.stories().isEmpty() && wsr.stories().stream().allMatch(s -> s.articles.stream().anyMatch(a -> !svc.watches().matching(a).isEmpty())),
                    "the Watchlist tab lists the stories about \"" + word + "\" (" + wsr.stories().size() + ")");
            FetchReport rep = new FetchReport();
            rep.addedArticles.addAll(added);
            List<String> pops = new ArrayList<>();
            st.reload(); st.setQuietHours(null, null); st.setNotifications(true); st.save();
            var a1 = svc.notifyAfterDownload(rep, (t, x) -> { pops.add(t + "\n" + x); return true; });
            c.that(a1 != null && pops.size() == 1 && pops.get(0).contains(word), "after a download one notification names the watch: " + (a1 == null ? "" : a1.title()));
            st.reload(); st.setNotifications(false); st.save();
            c.that(svc.notifyAfterDownload(rep, (t, x) -> { pops.add(t); return true; }) == null && pops.size() == 1, "notifications can be switched off");
            st.reload(); st.setNotifications(true); st.save();

            // Tickers tie stories to Finance
            Article nv = real.stream().filter(a -> a.title.contains("Nvidia")).findFirst().orElse(null);
            String sym = nv != null ? "NVDA" : null;
            if (sym == null) {
                Article any = real.stream().filter(a -> a.feedCategory != Category.FINANCE && a.title.matches(".*\\b[A-Z][a-z]{4,}\\b.*")).findFirst().get();
                Matcher m = Pattern.compile("\\b([A-Z][a-z]{4,})\\b").matcher(any.title);
                m.find();
                svc.tickers().save(List.of(new Tickers.Ticker("ZZTOP", List.of(m.group(1)))));
                sym = "ZZTOP";
            } else svc.tickers().save(List.of(new Tickers.Ticker("NVDA", Tickers.knownNames("NVDA"))));
            final String s = sym;
            Analyzer.Result tr = svc.tickerStories(s, 30);
            c.that(!tr.stories().isEmpty(), "the Markets tab lists stories for " + s + " (" + tr.stories().size() + ")");
            Analyzer.Result main = svc.analyze(7);
            c.that(main.stories().stream().filter(x -> !svc.tickers().of(x).isEmpty()).allMatch(x -> x.inDesk(Category.FINANCE)),
                    "every story about a followed ticker shows on the Finance desk");

            // Email: the digest content
            var mail = EmailDigest.build(svc.briefing(), null, Regions.defaults());
            c.that(mail.subject().startsWith("The Linked Desk · ") && mail.subject().contains("stories from"), "email subject: " + mail.subject());
            Matcher links = Pattern.compile("href=\"(https?://[^\"]+)\"").matcher(mail.html());
            int nl = 0; boolean allReal = true;
            Set<String> urls = new HashSet<>();
            for (Article a : real) urls.add(a.url);
            while (links.find()) { nl++; if (!urls.contains(links.group(1).replace("&amp;", "&"))) allReal = false; }
            c.that(nl >= 5 && allReal, "the HTML email's headlines link to the real articles (" + nl + " links)");
            c.that(mail.text().contains("https://") && !Pattern.compile("<(a|p|b|h\\d|br|span|div)[ >/]").matcher(mail.text()).find(), "with a plain-text version for mail apps without HTML");

            // Email: sending, against a local stand-in mail server
            smtp = new SmtpStandIn();
            SSLContext tls = smtp.tls();
            Mailer.Config plain = new Mailer.Config("localhost", smtp.port(SmtpStandIn.Mode.PLAIN), "NONE", "", "", "desk@example.org", List.of("you@example.org"));
            Mailer m0 = new Mailer();
            m0.send(plain, "Plain test", "hello", "<p>hello</p>");
            String got = smtp.last();
            c.that(got.contains("Subject: Plain test") && got.contains("multipart/alternative") && got.contains("text/plain") && got.contains("text/html"),
                    "a plain connection without a password sends a multipart message");
            c.that(smtp.rcpts().equals(List.of("you@example.org")), "to the right recipient");
            try {
                new Mailer().send(new Mailer.Config("localhost", smtp.port(SmtpStandIn.Mode.PLAIN), "NONE", "me", "secret", "desk@example.org", List.of("you@example.org")), "x", "x", "x");
                c.that(false, "a password over an unencrypted connection is refused");
            } catch (Mailer.MailException e) {
                c.that(e.getMessage().contains("unencrypted") && !smtp.transcriptText().contains("secret"), "a password is never sent over an unencrypted connection");
            }
            if (tls != null) {
                Mailer m1 = new Mailer(tls.getSocketFactory());
                m1.send(new Mailer.Config("localhost", smtp.port(SmtpStandIn.Mode.STARTTLS), "STARTTLS", "me@example.org", "right", "me@example.org",
                        Mailer.Config.list("you@example.org, boss@example.org")), "Ünïcode briefing", "text", "<b>html</b>");
                c.that(smtp.lastAuth().equals("me@example.org/right") && smtp.wasSecure(), "STARTTLS: the login happens after encryption starts");
                c.that(smtp.last().contains("Subject: =?UTF-8?B?") && smtp.rcpts().size() == 2, "non-ASCII subjects are encoded; two recipients");
                c.that(m1.transcript().stream().noneMatch(l -> l.contains("right")) && m1.transcript().stream().anyMatch(l -> l.contains("AUTH PLAIN ****")),
                        "the log of the conversation hides the password");
                Mailer m2 = new Mailer(tls.getSocketFactory());
                m2.send(new Mailer.Config("localhost", smtp.port(SmtpStandIn.Mode.SSL), "SSL", "me@example.org", "right", "me@example.org", List.of("you@example.org")),
                        "SSL test", "t", "<i>h</i>");
                c.that(smtp.last().contains("Subject: SSL test") && smtp.wasSecure(), "SSL (port 465 style) works too");
                try {
                    new Mailer(tls.getSocketFactory()).send(new Mailer.Config("localhost", smtp.port(SmtpStandIn.Mode.STARTTLS), "STARTTLS", "me@example.org", "wrong",
                            "me@example.org", List.of("you@example.org")), "x", "x", "x");
                    c.that(false, "a wrong password is reported");
                } catch (Mailer.MailException e) {
                    c.that(e.getMessage().contains("app password") && e.getMessage().contains("535"), "a wrong password gets a plain explanation (Gmail app password)");
                }
                try {
                    new Mailer(tls.getSocketFactory()).send(new Mailer.Config("127.0.0.1", smtp.port(SmtpStandIn.Mode.SSL), "SSL", "", "", "me@example.org", List.of("you@example.org")), "x", "x", "x");
                    c.that(false, "a certificate for another name is refused");
                } catch (Mailer.MailException e) {
                    c.that(e.getMessage().contains("secure connection"), "a server certificate for another name is refused");
                }
                try {
                    new Mailer().send(new Mailer.Config("localhost", smtp.port(SmtpStandIn.Mode.SSL), "SSL", "", "", "me@example.org", List.of("you@example.org")), "x", "x", "x");
                    c.that(false, "an untrusted certificate is refused");
                } catch (Mailer.MailException e) {
                    c.that(e.getMessage().contains("secure connection"), "an untrusted (self-made) certificate is refused");
                }

                // The service: once a day, settings from Settings
                st.reload();
                st.setEmailEnabled(true);
                st.setSmtp("localhost", smtp.port(SmtpStandIn.Mode.STARTTLS), "STARTTLS", "me@example.org", "me@example.org", "you@example.org");
                st.setSmtpPassword("right");
                st.save();
                svc.useMailer(new Mailer(tls.getSocketFactory()));
                int before = smtp.count();
                String first = svc.emailBriefing(false);
                String second = svc.emailBriefing(false);
                c.that(first.startsWith("Briefing emailed to you@example.org") && smtp.count() == before + 1, "the daily email is sent: " + first);
                c.that(second.contains("already sent today") && smtp.count() == before + 1, "only once a day");
                c.that(svc.emailBriefing(true).startsWith("Briefing emailed") && smtp.count() == before + 2, "Send test email sends anyway");
                c.that(smtp.lastAuth().equals("me@example.org/right"), "the saved (encrypted) password is used");
                String raw = smtp.last();
                String html = decodePart(raw, "text/html");
                c.that(html.contains("Your briefing") && html.contains("href=\"https://"), "the email that arrives holds the briefing with working links");
                st.reload(); st.setSmtpPassword("wrong"); st.setEmailLastSent(""); st.save();
                try { svc.emailBriefing(false); c.that(false, "a failed send is reported"); }
                catch (Exception e) { c.that(e.getMessage().contains("app password"), "a failed send is reported and logged, not marked as sent"); }
                st.reload();
                c.that(st.emailLastSent().isEmpty(), "(so the next download tries again)");
            } else {
                c.that(true, "(keytool not found: STARTTLS and SSL checks skipped)");
            }
            st.reload(); st.setEmailEnabled(false); st.save();
            c.eq("Email: off.", svc.emailBriefing(false), "email is off unless switched on");
        } finally {
            if (smtp != null) smtp.close();
            if (oldHome != null) System.setProperty("linkeddesk.home", oldHome); else System.clearProperty("linkeddesk.home");
        }
    }

    static List<String> names(List<Watches.Watch> ws) {
        List<String> out = new ArrayList<>();
        for (Watches.Watch w : ws) out.add(w.name());
        return out;
    }

    static String decodePart(String raw, String type) {
        int i = raw.indexOf("Content-Type: " + type);
        if (i < 0) return "";
        int b = raw.indexOf("\r\n\r\n", i) + 4;
        int e = raw.indexOf("\r\n--", b);
        return new String(Base64.getMimeDecoder().decode(raw.substring(b, e)), StandardCharsets.UTF_8);
    }

    /**
     * A minimal mail server on localhost for tests: plain, STARTTLS and SSL listeners, AUTH PLAIN (password "right"),
     * keeps the messages it receives. TLS uses a certificate for "localhost" made with keytool.
     */
    static final class SmtpStandIn implements Closeable {
        enum Mode { PLAIN, STARTTLS, SSL }
        private final Map<Mode, ServerSocket> servers = new EnumMap<>(Mode.class);
        private final List<String> messages = new CopyOnWriteArrayList<>();
        private final StringBuffer transcript = new StringBuffer();
        private volatile List<String> rcpts = List.of();
        private volatile String auth = "";
        private volatile boolean secure;
        private SSLContext serverTls, clientTls;

        SmtpStandIn() throws Exception {
            makeTls();
            for (Mode m : Mode.values()) {
                if (m != Mode.PLAIN && serverTls == null) continue;
                ServerSocket ss = m == Mode.SSL ? serverTls.getServerSocketFactory().createServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress())
                        : new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress());
                servers.put(m, ss);
                Thread t = new Thread(() -> serve(ss, m), "smtp-" + m);
                t.setDaemon(true);
                t.start();
            }
        }

        private void makeTls() {
            try {
                Path dir = Files.createTempDirectory("ld-tls");
                Path ks = dir.resolve("server.p12");
                String keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool").toString();
                Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "smtp", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                        "-dname", "CN=localhost", "-ext", "SAN=dns:localhost", "-keystore", ks.toString(), "-storetype", "PKCS12",
                        "-storepass", "changeit", "-keypass", "changeit").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() != 0) return;
                KeyStore k = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(ks)) { k.load(in, "changeit".toCharArray()); }
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(k, "changeit".toCharArray());
                serverTls = SSLContext.getInstance("TLS");
                serverTls.init(kmf.getKeyManagers(), null, null);
                KeyStore trust = KeyStore.getInstance("PKCS12");
                trust.load(null, null);
                trust.setCertificateEntry("smtp", k.getCertificate("smtp"));
                TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                tmf.init(trust);
                clientTls = SSLContext.getInstance("TLS");
                clientTls.init(null, tmf.getTrustManagers(), null);
            } catch (Exception e) {
                serverTls = null;
                clientTls = null;
            }
        }

        SSLContext tls() { return clientTls; }
        int port(Mode m) { return servers.get(m).getLocalPort(); }
        String last() { return messages.isEmpty() ? "" : messages.get(messages.size() - 1); }
        int count() { return messages.size(); }
        List<String> rcpts() { return rcpts; }
        String lastAuth() { return auth; }
        boolean wasSecure() { return secure; }
        String transcriptText() { return transcript.toString(); }

        private void serve(ServerSocket ss, Mode mode) {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    s.setSoTimeout(10_000);
                    session(s, mode);
                } catch (Exception ignored) { }
            }
        }

        private void session(Socket s0, Mode mode) throws IOException {
            Socket s = s0;
            boolean tls = mode == Mode.SSL;
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            List<String> to = new ArrayList<>();
            send(out, "220 localhost test mail server");
            String line;
            while ((line = in.readLine()) != null) {
                transcript.append(line).append('\n');
                String up = line.toUpperCase(Locale.ROOT);
                if (up.startsWith("EHLO")) {
                    send(out, "250-localhost");
                    if (mode == Mode.STARTTLS && !tls) send(out, "250-STARTTLS");
                    send(out, "250 AUTH PLAIN LOGIN");
                } else if (up.equals("STARTTLS")) {
                    send(out, "220 go ahead");
                    SSLSocket t = (SSLSocket) serverTls.getSocketFactory().createSocket(s, "localhost", s.getPort(), true);
                    t.setUseClientMode(false);
                    t.startHandshake();
                    s = t;
                    tls = true;
                    in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                } else if (up.startsWith("AUTH PLAIN ")) {
                    String[] p = new String(Base64.getDecoder().decode(line.substring(11).trim()), StandardCharsets.UTF_8).split("\0");
                    auth = p[1] + "/" + p[2];
                    secure = tls;
                    send(out, p[2].equals("right") ? "235 ok" : "535 5.7.8 Username and Password not accepted");
                } else if (up.startsWith("MAIL FROM")) {
                    to.clear();
                    send(out, "250 ok");
                } else if (up.startsWith("RCPT TO:<")) {
                    to.add(line.substring(9, line.indexOf('>')));
                    send(out, "250 ok");
                } else if (up.equals("DATA")) {
                    send(out, "354 end with .");
                    StringBuilder msg = new StringBuilder();
                    String l;
                    while ((l = in.readLine()) != null && !l.equals(".")) msg.append(l.startsWith("..") ? l.substring(1) : l).append("\r\n");
                    messages.add(msg.toString());
                    rcpts = List.copyOf(to);
                    secure = tls;
                    send(out, "250 queued");
                } else if (up.equals("QUIT")) {
                    send(out, "221 bye");
                    return;
                } else send(out, "500 unknown");
            }
        }

        private static void send(Writer out, String l) throws IOException { out.write(l + "\r\n"); out.flush(); }

        @Override public void close() { for (ServerSocket ss : servers.values()) try { ss.close(); } catch (IOException ignored) { } }
    }
}
