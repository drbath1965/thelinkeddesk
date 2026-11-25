package com.linkeddesk.alerts;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Companies and tickers you follow (ALR-F05), in tickers.txt:
 * <pre>NVDA = Nvidia, Jensen Huang</pre>
 * A story is about a ticker when it names the company, or writes the symbol as $NVDA, (NVDA) or NASDAQ: NVDA,
 * or in capitals on its own (symbols of 3+ letters). For well-known companies only the symbol is needed: the
 * names come from a built-in list of large US-listed companies (for others, and for companies listed elsewhere,
 * add the names yourself: "SAMSUNG = Samsung Electronics").
 */
public final class Tickers {
    public record Ticker(String symbol, List<String> names) {
        public String label() { return names.isEmpty() ? symbol : symbol + " · " + names.get(0); }
    }

    /** symbol -> company names, for large, often-reported companies. */
    static final Map<String, List<String>> KNOWN = new LinkedHashMap<>();
    static {
        String data = """
                AAPL=Apple|MSFT=Microsoft|NVDA=Nvidia|GOOGL=Alphabet,Google|GOOG=Alphabet,Google|AMZN=Amazon|META=Meta Platforms,Meta,Facebook
                TSLA=Tesla|AVGO=Broadcom|TSM=TSMC,Taiwan Semiconductor|ORCL=Oracle|AMD=AMD,Advanced Micro Devices|INTC=Intel|QCOM=Qualcomm
                ASML=ASML|ARM=Arm Holdings|MU=Micron|IBM=IBM|CRM=Salesforce|ADBE=Adobe|NFLX=Netflix|PLTR=Palantir|SNOW=Snowflake
                SONY=Sony|BABA=Alibaba|TCEHY=Tencent|BIDU=Baidu|PDD=PDD,Temu
                JPM=JPMorgan,JPMorgan Chase|BAC=Bank of America|GS=Goldman Sachs|MS=Morgan Stanley|C=Citigroup,Citi|WFC=Wells Fargo
                BRK.B=Berkshire Hathaway|V=Visa|MA=Mastercard|PYPL=PayPal|BLK=BlackRock|COIN=Coinbase
                XOM=Exxon,ExxonMobil,Exxon Mobil|CVX=Chevron|SHEL=Shell|BP=BP|TTE=TotalEnergies|COP=ConocoPhillips
                LMT=Lockheed Martin,Lockheed|RTX=RTX,Raytheon|NOC=Northrop Grumman,Northrop|GD=General Dynamics|BA=Boeing|LHX=L3Harris
                EADSY=Airbus|HII=Huntington Ingalls|LDOS=Leidos|BAH=Booz Allen,Booz Allen Hamilton|SAIC=SAIC
                GE=GE Aerospace,General Electric|HON=Honeywell|CAT=Caterpillar|DE=Deere,John Deere|UPS=UPS|FDX=FedEx
                WMT=Walmart|COST=Costco|TGT=Target Corp|HD=Home Depot|NKE=Nike|SBUX=Starbucks|MCD=McDonald's|KO=Coca-Cola|PEP=PepsiCo
                JNJ=Johnson & Johnson|PFE=Pfizer|MRK=Merck|LLY=Eli Lilly,Lilly|NVO=Novo Nordisk|ABBV=AbbVie|UNH=UnitedHealth|AZN=AstraZeneca
                F=Ford,Ford Motor|GM=General Motors|TM=Toyota|VWAGY=Volkswagen|BYDDY=BYD|STLA=Stellantis|RIVN=Rivian
                DIS=Disney|CMCSA=Comcast|T=AT&T|VZ=Verizon|SPOT=Spotify|UBER=Uber|ABNB=Airbnb|SHOP=Shopify|MSTR=MicroStrategy,Strategy
                BTC=Bitcoin|ETH=Ether,Ethereum
                """;
        for (String part : data.replace("\n", "|").split("\\|")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            String sym = part.substring(0, eq).strip();
            List<String> names = new ArrayList<>();
            for (String n : part.substring(eq + 1).split(",")) if (!n.isBlank()) names.add(n.strip());
            KNOWN.putIfAbsent(sym, names);
        }
    }

    /** Capitalised words that are also symbols but almost always mean something else in news text. */
    private static final Set<String> NOT_SYMBOLS = Set.of("CEO", "CFO", "USA", "GDP", "IPO", "NATO", "AI", "UN", "EU", "UK", "US", "IMF", "WHO",
            "IT", "ON", "ALL", "NOW", "FOR", "ARE", "NEW", "BIG", "HAS", "ONE", "TWO", "CAN", "OIL", "GAS", "WAR", "NYSE", "SEC", "FBI", "CIA", "DNI");

    public static final String HEADER = """
            # Companies and tickers you follow. One per line:  SYMBOL = company name, other name
            # For well-known companies the symbol alone is enough (NVDA, AAPL, LMT…). Stories about them also show in Finance.
            """;

    private final Path file;
    private final List<Ticker> list = new ArrayList<>();
    private final List<Pattern> patterns = new ArrayList<>();
    private long stamp = Long.MIN_VALUE;

    public Tickers(Path home) { this.file = home.resolve("tickers.txt"); }

    public synchronized Tickers load() {
        long st;
        try { st = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() * 31 + Files.size(file) : 0; } catch (IOException e) { st = 0; }
        if (st == stamp) return this;
        stamp = st;
        list.clear();
        patterns.clear();
        try {
            {
                for (String raw : com.linkeddesk.app.SafeFiles.readLines(file)) {
                    String line = raw.strip();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    String sym = (eq < 0 ? line : line.substring(0, eq)).strip().toUpperCase(Locale.ROOT).replace("$", "");
                    if (sym.isEmpty()) continue;
                    List<String> names = new ArrayList<>();
                    if (eq >= 0) for (String n : line.substring(eq + 1).split(",")) if (!n.isBlank()) names.add(n.strip());
                    if (names.isEmpty()) names.addAll(KNOWN.getOrDefault(sym, List.of()));
                    list.add(new Ticker(sym, names));
                }
            }
            unreadable = false;
        } catch (IOException e) { unreadable = true; stamp = Long.MIN_VALUE; }
        for (Ticker t : list) patterns.add(pattern(t));
        return this;
    }

    private static Pattern pattern(Ticker t) {
        String q = Pattern.quote(t.symbol());
        StringBuilder sb = new StringBuilder("(?<![\\p{L}\\p{N}])\\$" + q + "(?![\\p{L}\\p{N}])"
                + "|\\(\\s*(?:[A-Z]+:\\s*)?" + q + "\\s*\\)"
                + "|(?:NASDAQ|NYSE|Nasdaq|LSE|TSX|ASX|HKEX|TSE|NYSEArca|Xetra)\\s*:\\s*" + q + "(?![\\p{L}\\p{N}])");
        if (t.symbol().length() >= 3 && !NOT_SYMBOLS.contains(t.symbol()) && t.symbol().chars().allMatch(Character::isLetter))
            sb.append("|(?<![\\p{L}\\p{N}$])").append(q).append("(?![\\p{L}\\p{N}])");
        for (String n : t.names()) sb.append("|(?<![\\p{L}\\p{N}])").append(Pattern.quote(n)).append("(?:'s|’s)?(?![\\p{L}\\p{N}])");
        return Pattern.compile(sb.toString());
    }

    public synchronized List<Ticker> all() { return List.copyOf(list); }
    public synchronized boolean isEmpty() { return list.isEmpty(); }

    private boolean unreadable;

    public synchronized void save(List<Ticker> tickers) throws IOException {
        com.linkeddesk.app.SafeFiles.checkSafeToSave(unreadable, file);
        StringBuilder sb = new StringBuilder(HEADER);
        for (Ticker t : tickers) {
            sb.append(t.symbol());
            List<String> known = KNOWN.getOrDefault(t.symbol(), List.of());
            if (!t.names().isEmpty() && !t.names().equals(known)) sb.append(" = ").append(String.join(", ", t.names()));
            sb.append('\n');
        }
        com.linkeddesk.app.SafeFiles.write(file, sb.toString());
        stamp = Long.MIN_VALUE;
        load();
    }

    /** Built-in names for a symbol (empty if not a well-known company). */
    public static List<String> knownNames(String symbol) { return KNOWN.getOrDefault(symbol.toUpperCase(Locale.ROOT), List.of()); }

    /** The followed tickers an article is about. */
    public Set<String> of(Article a) {
        Set<String> out = new LinkedHashSet<>();
        List<Ticker> l;
        List<Pattern> p;
        synchronized (this) { l = List.copyOf(list); p = List.copyOf(patterns); }
        String text = a.title + "\n" + a.summary;
        for (int i = 0; i < l.size(); i++) {
            if (!mayMention(text, l.get(i))) continue;       // quick check before the pattern
            Matcher m = p.get(i).matcher(text);
            if (m.find()) out.add(l.get(i).symbol());
        }
        return out;
    }

    private static boolean mayMention(String text, Ticker t) {
        if (text.contains(t.symbol())) return true;
        for (String n : t.names()) if (text.contains(n)) return true;
        return false;
    }

    /** The followed tickers a story is about (any of its articles). */
    public Set<String> of(StoryCluster c) {
        Set<String> out = new LinkedHashSet<>();
        for (Article a : c.articles) out.addAll(of(a));
        return out;
    }
}
