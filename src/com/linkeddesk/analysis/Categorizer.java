package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.Category;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Assigns each article to a desk. The feed's own section is the starting point; strong keyword evidence
 * in the headline can move it (a World-feed story about stock markets becomes Finance).
 */
public final class Categorizer {
    private static final Map<Category, Pattern> RULES = new EnumMap<>(Category.class);

    static {
        RULES.put(Category.FINANCE, words(
                "stock", "stocks", "shares", "market", "markets", "wall street", "nasdaq", "dow", "s&p", "ftse",
                "nikkei", "earnings", "revenue", "profit", "profits", "loss", "losses", "inflation", "interest rate",
                "interest rates", "rate cut", "rate hike", "federal reserve", "the fed", "central bank", "ecb",
                "bank of england", "bond", "bonds", "yield", "yields", "treasury", "oil price", "oil prices", "crude",
                "brent", "opec", "ipo", "tariff", "tariffs", "trade deal", "gdp", "recession", "economy", "economic",
                "economists", "bank", "banks", "banking", "investor", "investors", "currency", "dollar", "euro", "yen",
                "bitcoin", "crypto", "jobs report", "unemployment", "payrolls", "imf", "world bank", "merger",
                "acquisition", "takeover", "ceo", "retail sales", "consumer", "prices", "mortgage", "housing market",
                "debt", "deficit", "budget", "tax", "taxes", "exports", "imports", "supply chain"));
        RULES.put(Category.POLITICS, words(
                "election", "elections", "vote", "votes", "voters", "voting", "ballot", "congress", "senate",
                "senator", "house speaker", "parliament", "parliamentary", "mp", "mps", "lawmakers", "legislation",
                "bill", "impeachment", "impeach", "supreme court", "white house", "cabinet", "campaign", "candidate",
                "democrat", "democrats", "republican", "republicans", "gop", "labour", "conservative", "conservatives",
                "party", "coalition", "referendum", "poll", "polls", "governor", "mayor", "prime minister",
                "chancellor", "president", "administration", "executive order", "midterm", "midterms", "filibuster",
                "veto", "politics", "political", "opposition", "minister"));
        RULES.put(Category.SOCIETY, words(
                "health", "hospital", "hospitals", "patients", "doctors", "nhs", "disease", "outbreak", "virus",
                "vaccine", "vaccines", "cancer", "mental health", "school", "schools", "students", "teachers",
                "education", "university", "climate", "climate change", "emissions", "environment", "pollution",
                "weather", "storm", "storms", "hurricane", "typhoon", "flood", "floods", "flooding", "wildfire",
                "wildfires", "heatwave", "drought", "earthquake", "religion", "church", "pope", "muslims",
                "christians", "migrants", "migration", "refugees", "asylum", "poverty", "homeless", "housing",
                "families", "children", "women", "rights", "protest", "protests", "police", "crime", "court",
                "famine", "hunger", "humanitarian", "aid", "culture", "festival", "cop31", "cop30", "biodiversity"));
        RULES.put(Category.TECH, words(
                "ai", "artificial intelligence", "chip", "chips", "semiconductor", "semiconductors", "software",
                "tech", "technology", "cyber", "cyberattack", "hack", "hackers", "data breach", "smartphone", "iphone", "apple", "google", "alphabet", "microsoft",
                "meta", "amazon", "nvidia", "openai", "anthropic", "chatgpt", "claude", "robot", "robots", "quantum",
                "science", "scientists", "research", "researchers", "study", "telescope", "physics", "biology",
                "genetic", "app", "apps", "social media", "tiktok", "algorithm"));
        RULES.put(Category.DEFENSE, words(
                "pentagon", "defense department", "department of defense", "dod", "war department", "army", "navy", "air force",
                "marine corps", "space force", "missile defense", "missile defense agency", "golden dome", "hypersonic", "hypersonics",
                "contract award", "defense contract", "defense contractor", "defense budget", "ndaa", "procurement", "munitions",
                "lockheed", "lockheed martin", "raytheon", "rtx", "northrop", "northrop grumman", "general dynamics", "l3harris",
                "boeing defense", "huntington ingalls", "anduril", "leidos", "saic", "booz allen", "f-35", "b-21", "sentinel icbm",
                "redstone", "redstone arsenal", "huntsville", "space command", "darpa", "fighter jet", "warship", "submarine",
                "space", "nasa", "spacex", "blue origin", "ula", "rocket", "launch", "launches", "satellite", "satellites", "orbit",
                "artemis", "space station", "iss", "astronaut", "astronauts", "moon", "mars", "marshall space flight center", "sls"));
        RULES.put(Category.WORLD, words(
                "war", "military", "troops", "strike", "strikes", "airstrike", "airstrikes", "missile",
                "missiles", "drone", "drones", "ceasefire", "truce", "peace talks", "diplomat", "diplomats",
                "diplomatic", "summit", "border", "attack", "attacks", "killed", "invasion", "sanctions", "nato",
                "united nations", "un", "embassy", "hostages", "rebels", "militants", "insurgents", "coup",
                "nuclear", "foreign minister"));
    }

    private static Pattern words(String... terms) {
        StringBuilder sb = new StringBuilder("(?<![a-z0-9])(");
        for (int i = 0; i < terms.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(Pattern.quote(terms[i]));
        }
        return Pattern.compile(sb.append(")(?![a-z0-9])").toString());
    }

    private Categorizer() {}

    public static Category categorize(Article a) {
        String title = a.title.toLowerCase(Locale.ROOT);
        String body = a.summary.toLowerCase(Locale.ROOT);
        Map<Category, Double> score = new EnumMap<>(Category.class);
        for (var e : RULES.entrySet()) {
            double s = 3.0 * count(e.getValue(), title) + count(e.getValue(), body);
            score.put(e.getKey(), s);
        }
        score.merge(a.feedCategory, 3.0, Double::sum);
        Category best = a.feedCategory;
        double bestScore = score.get(best);
        for (var e : score.entrySet()) {
            if (e.getValue() > bestScore + 1.0) { best = e.getKey(); bestScore = e.getValue(); }
        }
        return best;
    }

    private static int count(Pattern p, String s) {
        Matcher m = p.matcher(s);
        int n = 0;
        while (m.find()) n++;
        return n;
    }
}
