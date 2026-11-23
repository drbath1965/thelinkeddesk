package com.linkeddesk.analysis;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The words the analyzer treats specially: aliases (different names for the same thing, such as
 * "Fed" and "Federal Reserve") and extra stop words (words that must never link stories).
 *
 * Both live in plain text files next to feeds.txt so they can be edited by hand:
 * <pre>
 * aliases.txt     Federal Reserve = Fed, US Federal Reserve
 * stopwords.txt   one word per line
 * </pre>
 * Lines starting with # are comments.
 */
public final class Lexicon {
    /** normalized alias key -> canonical key */
    private final Map<String, String> alias = new HashMap<>();
    /** canonical key -> display name */
    private final Map<String, String> display = new HashMap<>();
    private final Set<String> stop = new HashSet<>();
    private final List<String> problems = new ArrayList<>();

    public static final Lexicon EMPTY = new Lexicon();

    /** The built-in aliases, used when aliases.txt doesn't exist yet. */
    public static final String DEFAULT_ALIASES = """
            # Different names for the same thing. One line per thing:
            #   Canonical name = alias, alias, ...
            # Stories that use any of the names are treated as mentioning the same thing, so they can link.
            # Single-word aliases only count when written with a capital letter (so "fed up" isn't the Fed).
            # Edit freely; the change applies the next time stories are grouped.

            # ---- Countries and places
            United States = US, U.S., USA, U.S.A., America, United States of America
            # Washington is often used for the US government, but also for the state and the city.
            # Remove the # on the next line to treat it as the United States.
            # United States = Washington
            United Kingdom = UK, U.K., Britain, Great Britain
            Russia = Russian Federation, Moscow, Kremlin
            China = PRC, People's Republic of China, Beijing
            Ukraine = Kyiv, Kiev
            Iran = Tehran, Islamic Republic
            Israel = IDF, Israel Defense Forces
            Gaza = Gaza Strip
            Taiwan = Taipei
            North Korea = DPRK, Pyongyang
            South Korea = Republic of Korea, Seoul
            European Union = EU, E.U., European Commission
            DR Congo = DRC, Congo, Democratic Republic of Congo, Democratic Republic of the Congo
            Saudi Arabia = Riyadh
            Strait of Hormuz = Hormuz, Strait of Hormuz
            United Arab Emirates = UAE, Emirates

            # ---- International bodies
            United Nations = UN, U.N.
            World Health Organization = WHO, World Health Organisation
            NATO = North Atlantic Treaty Organization
            International Monetary Fund = IMF
            OPEC = Organization of the Petroleum Exporting Countries, OPEC+

            # ---- Central banks and markets
            Federal Reserve = Fed, US Federal Reserve, Federal Reserve Board
            European Central Bank = ECB
            Bank of England = BoE
            Bank of Japan = BoJ
            Wall Street = NYSE, New York Stock Exchange
            Nasdaq = Nasdaq Composite, Nasdaq 100
            S&P 500 = S&P, Standard & Poor's 500
            Kospi = Korea Composite Stock Price Index

            # ---- Leaders
            Donald Trump = Trump, President Trump
            Vladimir Putin = Putin
            Xi Jinping = Xi, President Xi
            Volodymyr Zelenskyy = Zelenskyy, Zelensky, Zelenskiy
            Benjamin Netanyahu = Netanyahu, Bibi
            Keir Starmer = Starmer
            Emmanuel Macron = Macron
            Narendra Modi = Modi
            Pedro Sanchez = Sanchez, Pedro Sánchez, Sánchez
            Alberto Nunez Feijoo = Feijoo, Feijóo, Alberto Feijoo, Alberto Núñez Feijóo
            Tedros Adhanom Ghebreyesus = Tedros
            Jay Clayton = Clayton

            # ---- Companies
            Alphabet = Google
            Meta = Facebook, Meta Platforms
            Nvidia = NVIDIA, Nvidia Corp
            Jensen Huang = Huang, Jensen
            Anthropic = Anthropic PBC
            OpenAI = Open AI
            Samsung = Samsung Electronics
            SK Hynix = Hynix
            TSMC = Taiwan Semiconductor, Taiwan Semiconductor Manufacturing

            # ---- Events and topics
            COP31 = Cop31, UN climate summit, U.N. climate summit
            Artificial Intelligence = AI, A.I.
            Director of National Intelligence = DNI
            """;

    public static final String DEFAULT_STOPWORDS = """
            # Extra words that should never link stories, one per line (in addition to the built-in list
            # of common words). Add a word here when it causes false links; it applies the next time stories
            # are grouped. Tools > Words behind links shows which words create the most links.
            """;

    /** Reads aliases.txt and stopwords.txt, creating them with the defaults if they don't exist. */
    public static Lexicon load(Path aliasesFile, Path stopwordsFile) throws IOException {
        if (!Files.exists(aliasesFile)) Files.writeString(aliasesFile, DEFAULT_ALIASES, StandardCharsets.UTF_8);
        if (!Files.exists(stopwordsFile)) Files.writeString(stopwordsFile, DEFAULT_STOPWORDS, StandardCharsets.UTF_8);
        return parse(Files.readString(aliasesFile, StandardCharsets.UTF_8), Files.readString(stopwordsFile, StandardCharsets.UTF_8));
    }

    public static Lexicon defaults() { return parse(DEFAULT_ALIASES, ""); }

    public static Lexicon parse(String aliasText, String stopText) {
        Lexicon x = new Lexicon();
        int n = 0;
        for (String raw : aliasText.split("\\R")) {
            n++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) { x.problems.add("aliases.txt line " + n + ": no '=' in \"" + line + "\""); continue; }
            String canonical = line.substring(0, eq).strip();
            String ck = key(canonical);
            if (ck.isEmpty()) { x.problems.add("aliases.txt line " + n + ": empty name"); continue; }
            // A name already used as an alias of something else stays with its first owner.
            String owner = x.alias.get(ck);
            if (owner != null && !owner.equals(ck)) ck = owner;
            else { x.alias.put(ck, ck); x.display.putIfAbsent(ck, canonical); }
            for (String a : line.substring(eq + 1).split(",")) {
                String ak = key(a);
                if (ak.isEmpty()) continue;
                x.alias.putIfAbsent(ak, ck);
            }
        }
        for (String raw : stopText.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            for (String w : line.split("[\\s,]+")) {
                String k = key(w);
                if (!k.isEmpty()) x.stop.add(k);
            }
        }
        return x;
    }

    /**
     * Normalizes a name for matching: lower case, dots and apostrophes removed, possessive and leading
     * "the" dropped, each word lightly stemmed. "The U.S." -> "us", "Strait of Hormuz" -> "strait of hormuz".
     */
    public static String key(String s) {
        if (s == null) return "";
        boolean simple = !s.isEmpty();
        for (int i = 0; i < s.length() && simple; i++) {
            char ch = s.charAt(i);
            simple = Character.isLetterOrDigit(ch) || ch == '&';
        }
        if (simple) return Text.stem(s.toLowerCase(Locale.ROOT));   // one plain word: the common case
        String t = s.strip().replace('’', '\'').toLowerCase(Locale.ROOT);
        if (t.endsWith("'s")) t = t.substring(0, t.length() - 2);
        t = t.replace(".", "").replace("'", "");
        String[] words = t.split("[\\s-]+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            String w = words[i];
            if (w.isEmpty()) continue;
            if (sb.length() == 0 && w.equals("the") && words.length > 1) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Text.stem(w));
        }
        return sb.toString();
    }

    /** The canonical key for this normalized key, or null if it isn't a known name. */
    public String canonical(String key) { return alias.get(key); }

    /** Display form of a canonical key ("Federal Reserve"). */
    public String display(String canonicalKey) { return display.get(canonicalKey); }

    /** True if this term, or any word of a multi-word term, is one of your stop words. */
    public boolean isStop(String key) {
        if (stop.isEmpty()) return false;
        if (stop.contains(key)) return true;
        if (key.indexOf(' ') < 0) return false;
        for (String w : key.split(" ")) if (stop.contains(w)) return true;
        return false;
    }

    public int aliasCount() { return alias.size(); }
    public int stopCount() { return stop.size(); }
    public List<String> problems() { return problems; }

    /** Adds words to stopwords.txt (keeping what's there). */
    public static void addStopWords(Path stopwordsFile, List<String> words) throws IOException {
        String text = Files.exists(stopwordsFile) ? Files.readString(stopwordsFile, StandardCharsets.UTF_8) : DEFAULT_STOPWORDS;
        Set<String> have = parse("", text).stop;
        StringBuilder sb = new StringBuilder(text);
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append(System.lineSeparator());
        for (String w : words) {
            String k = key(w);
            if (k.isEmpty() || !have.add(k)) continue;
            sb.append(w.strip().toLowerCase(Locale.ROOT)).append(System.lineSeparator());
        }
        Files.writeString(stopwordsFile, sb.toString(), StandardCharsets.UTF_8);
    }
}
