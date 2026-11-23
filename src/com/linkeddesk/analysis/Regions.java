package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each outlet is based, so a story can show whether it is covered across regions or by one source only.
 * Kept in regions.txt ("part of the outlet name = Region", longest match wins), editable by hand.
 */
public final class Regions {
    public static final String UNKNOWN = "Unknown";
    public static final String WIRE = "Wire service";
    public static final String INTERNATIONAL = "International";

    public static final String DEFAULTS = """
            # Where outlets are based, for the "covered in N regions" indicator on each story.
            # One line per outlet:  part of the outlet name = Region
            # The longest matching name wins ("Politico Europe" beats "Politico"). Matching ignores capitals.
            # Wire services (Reuters, AP, AFP) are counted separately: they serve every region.
            # Edit freely; the change applies straight away.

            # ---- Wire services
            Reuters = Wire service
            Associated Press = Wire service
            AP News = Wire service
            Agence France-Presse = Wire service
            AFP = Wire service
            # International bodies' own news (also not counted as a region)
            UN News = International

            # ---- North America
            NPR = North America
            NYT = North America
            New York Times = North America
            CNBC = North America
            MarketWatch = North America
            Politico = North America
            The Hill = North America
            CBC = North America
            CNN = North America
            Fox News = North America
            Fox Business = North America
            ABC News = North America
            CBS News = North America
            NBC News = North America
            Bloomberg = North America
            Wall Street Journal = North America
            Washington Post = North America
            USA TODAY = North America
            HuffPost = North America
            Chicago Tribune = North America
            Newsday = North America
            mlive = North America
            Boston Globe = North America
            Mercury News = North America
            Press Democrat = North America
            News & Observer = North America
            Barron's = North America
            Investor's Business Daily = North America
            Motley Fool = North America
            24/7 Wall St = North America
            Yahoo = North America
            AOL = North America
            Forbes = North America
            TechCrunch = North America
            Seeking Alpha = North America
            Decrypt = North America
            Defense News = North America
            Breaking Defense = North America
            SpaceNews = North America
            Council on Foreign Relations = North America
            Independent Journal Review = North America
            11Alive = North America
            Newsweek = North America
            Axios = North America
            Business Insider = North America
            Latin Times = North America
            Yeshiva World = North America
            Long War Journal = North America
            Zacks = North America
            AL.com = North America
            CoinCentral = North America
            How Money Works = North America
            Miami Herald = North America

            # ---- Europe
            BBC = Europe
            Guardian = Europe
            Deutsche Welle = Europe
            DW = Europe
            France 24 = Europe
            Euronews = Europe
            Politico Europe = Europe
            Financial Times = Europe
            The Independent = Europe
            Sky News = Europe
            The Local = Europe
            Brussels Times = Europe
            Business Travel News Europe = Europe
            ITIJ = Europe
            Air Cargo Week = Europe
            Telegraph = Europe
            Le Monde = Europe
            Spiegel = Europe
            TCE = Europe

            # ---- Eastern Europe and Central Asia
            Moscow Times = Eastern Europe & Central Asia
            Kyiv Independent = Eastern Europe & Central Asia
            Meduza = Eastern Europe & Central Asia
            hromadske = Eastern Europe & Central Asia
            UA.NEWS = Eastern Europe & Central Asia
            TASS = Eastern Europe & Central Asia
            News.az = Eastern Europe & Central Asia

            # ---- Middle East
            Al Jazeera = Middle East
            Times of Israel = Middle East
            Middle East Eye = Middle East
            Israel Today = Middle East
            Anadolu = Middle East
            L'Orient = Middle East
            Arab News = Middle East
            Haaretz = Middle East
            Jerusalem Post = Middle East
            Gulf News = Middle East

            # ---- Africa
            AllAfrica = Africa
            Africanews = Africa
            Arise News = Africa
            News24 = Africa
            Punch = Africa

            # ---- South Asia
            Dawn = South Asia
            The Hindu = South Asia
            Times of India = South Asia
            Hindustan Times = South Asia
            NDTV = South Asia
            Press Trust of India = South Asia
            Outlook India = South Asia
            Business Standard = South Asia
            The News International = South Asia
            Free Press Kashmir = South Asia
            Firstpost = South Asia
            NewsBytes = South Asia
            Newspoint = South Asia
            PRIMETIMER = South Asia
            Indian Express = South Asia
            Coin Republic = South Asia

            # ---- East Asia
            South China Morning Post = East Asia
            Japan Times = East Asia
            Bastille Post = East Asia
            Korea Herald = East Asia
            Nikkei = East Asia
            Xinhua = East Asia
            Yonhap = East Asia
            The Diplomat = East Asia
            note = East Asia

            # ---- Southeast Asia
            CNA = Southeast Asia
            Manila Times = Southeast Asia
            Daily Tribune = Southeast Asia
            Malay Mail = Southeast Asia
            Yahoo Malaysia = Southeast Asia
            Straits Times = Southeast Asia
            Bangkok Post = Southeast Asia

            # ---- Oceania
            ABC Australia = Oceania
            SBS = Oceania
            Yahoo News Australia = Oceania
            Sydney Morning Herald = Oceania
            RNZ = Oceania
            International Business Times AU = Oceania

            # ---- Latin America
            MercoPress = Latin America
            Rio Times = Latin America
            Buenos Aires Times = Latin America
            """;

    private final List<Map.Entry<String, String>> rules = new ArrayList<>();
    private final List<java.util.regex.Pattern> patterns = new ArrayList<>();
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    public static Regions defaults() { return parse(DEFAULTS); }

    public static Regions load(Path file) throws IOException {
        if (!Files.exists(file)) Files.writeString(file, DEFAULTS, StandardCharsets.UTF_8);
        return parse(Files.readString(file, StandardCharsets.UTF_8));
    }

    public static Regions parse(String text) {
        Regions r = new Regions();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            String key = line.substring(0, eq).strip().toLowerCase(Locale.ROOT), region = line.substring(eq + 1).strip();
            if (!key.isEmpty() && !region.isEmpty()) r.rules.add(Map.entry(key, region));
        }
        r.rules.sort(Comparator.comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed());
        for (var e : r.rules) r.patterns.add(java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(e.getKey()) + "(?![\\p{L}\\p{N}])"));
        return r;
    }

    /** The region of an outlet, by the longest rule whose name it contains as whole words. */
    public String of(String outlet) {
        if (outlet == null || outlet.isBlank()) return UNKNOWN;
        return cache.computeIfAbsent(outlet, o -> {
            String s = o.toLowerCase(Locale.ROOT).replace('’', '\'');
            for (int i = 0; i < rules.size(); i++) if (patterns.get(i).matcher(s).find()) return rules.get(i).getValue();
            return UNKNOWN;
        });
    }

    /** How a story's coverage spreads over regions. */
    public record Spread(int outlets, Map<String, Integer> regions) {
        /** Regions with an outlet based there (wire services, international bodies and unknown outlets not counted). */
        public int regionCount() {
            int n = 0;
            for (String r : regions.keySet()) if (!r.equals(WIRE) && !r.equals(UNKNOWN) && !r.equals(INTERNATIONAL)) n++;
            return n;
        }
        public boolean singleSource() { return outlets <= 1; }
        /** Several outlets, but all from one region. */
        public boolean oneRegion() { return outlets > 1 && regionCount() <= 1; }
        public String label() {
            if (singleSource()) return "Single source";
            int n = regionCount();
            return outlets + " outlets · " + (n <= 1 ? "one region" : n + " regions");
        }
    }

    public Spread spread(StoryCluster c) {
        Map<String, java.util.Set<String>> byRegion = new LinkedHashMap<>();
        for (Article a : c.articles) byRegion.computeIfAbsent(of(a.source), k -> new java.util.HashSet<>()).add(a.source);
        Map<String, Integer> counts = new LinkedHashMap<>();
        byRegion.entrySet().stream()
                .sorted(Map.Entry.<String, java.util.Set<String>>comparingByValue(Comparator.comparingInt(java.util.Set::size)).reversed())
                .forEach(e -> counts.put(e.getKey(), e.getValue().size()));
        return new Spread(c.sourceCount(), counts);
    }
}
