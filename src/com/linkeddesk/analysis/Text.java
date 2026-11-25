package com.linkeddesk.analysis;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tokenizing helpers: words, light stemming, stop words and "entity-like" capitalized words. */
public final class Text {
    private Text() {}

    /**
     * A token plus whether it looked like a proper noun or acronym in the original text.
     * {@code phrase}: a multi-word name such as "Strait of Hormuz" (emitted alongside its single words).
     * {@code event}: a name followed by an event word ("Iran war").
     * {@code titleCase}: the text it came from capitalizes almost every word (many headlines do), so a
     * capitalized run there isn't good evidence of a name.
     */
    public record Token(String term, String surface, boolean entity, boolean phrase, boolean titleCase, boolean event) {
        public Token(String term, String surface, boolean entity) { this(term, surface, entity, false, false, false); }
        public Token(String term, String surface, boolean entity, boolean phrase, boolean titleCase) { this(term, surface, entity, phrase, titleCase, false); }
    }

    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}\\p{N}'’&.-]*[\\p{L}\\p{N}]|\\p{L}");

    /** Small words allowed inside a name: Strait of Hormuz, Bank of England, Abu Bakr al Baghdadi. */
    private static final Set<String> JOINERS = Set.of("of", "de", "del", "da", "du", "la", "le", "al", "bin", "ibn", "van", "von", "der", "den", "y");
    /** Capitalized words that are stop words on their own but start real names (New York, New Zealand). */
    private static final Set<String> NAME_STARTS = Set.of("new");
    private static final int MAX_PHRASE_WORDS = 4;
    /**
     * Words that are capitalized as part of names ("South Korea", "Prime Minister", "National Intelligence")
     * but name nothing on their own. They still count as words, never as names.
     */
    static final Set<String> NOT_NAMES = Set.copyOf(List.of(
            "south", "north", "east", "west", "central", "northern", "southern", "eastern", "western", "upper", "lower",
            "united", "great", "prime", "minister", "ministry", "president", "presidential", "chief", "director", "general",
            "national", "international", "global", "republic", "democratic", "state", "states", "city", "kingdom", "union",
            "party", "army", "military", "court", "supreme", "house", "senate", "council", "department", "secretary",
            "federal", "royal", "foreign", "defense", "defence", "affairs", "agency", "authority", "commission", "committee",
            "university", "institute", "company", "group", "bank", "fund", "organization", "organisation", "association",
            "sea", "ocean", "river", "island", "islands", "mount", "lake", "gulf", "bay", "strait", "province", "region"));

    /**
     * Event words that, right after a name, make one subject: "Iran war", "Gaza ceasefire", "Anthropic IPO".
     * Stories about the "Iran war" link even when they share no other name.
     */
    static final Set<String> EVENT_NOUNS = Set.copyOf(List.of(
            "war", "wars", "crisis", "ceasefire", "truce", "conflict", "invasion", "offensive", "blockade", "sanctions",
            "talks", "deal", "summit", "election", "elections", "referendum", "protests", "strike", "strikes", "tariffs",
            "outbreak", "epidemic", "ipo", "merger", "scandal", "trial", "impeachment", "shutdown", "budget", "earthquake",
            "floods", "wildfires", "hurricane", "typhoon", "attack", "attacks", "bombing", "massacre", "coup", "uprising"));

    private record W(String surface, String lower, boolean acronym, boolean capital, boolean entity, boolean plainGapBefore) {}

    public static List<Token> tokens(String text) { return tokens(text, Lexicon.EMPTY); }

    public static List<Token> tokens(String text, Lexicon lex) {
        List<Token> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        List<W> words = words(text);
        boolean titleCase = isTitleCase(words);

        List<W> run = new ArrayList<>();
        for (W w : words) {
            // ---- phrases: runs of capitalized words, with joiners inside, separated only by spaces
            boolean joiner = JOINERS.contains(w.lower()) && !run.isEmpty() && w.plainGapBefore();
            boolean nameWord = w.capital() && (w.entity() || NAME_STARTS.contains(w.lower()))
                    && !DEMONYMS.containsKey(w.lower())
                    && (!STOP.contains(w.lower()) || NAME_STARTS.contains(w.lower()) || w.acronym());
            if (nameWord && (run.isEmpty() || w.plainGapBefore())) run.add(w);
            else if (joiner) run.add(w);
            else {
                emitPhrases(run, lex, titleCase, out);
                run.clear();
                if (nameWord) run.add(w);
            }
            // ---- single words
            single(w, lex, titleCase, out);
        }
        emitPhrases(run, lex, titleCase, out);
        // ---- name + event word: "Iran war", "Ukraine peace talks" (last two words), "Anthropic IPO"
        for (int i = 0; i + 1 < words.size(); i++) {
            W name = words.get(i), ev = words.get(i + 1);
            if (!ev.plainGapBefore() || !EVENT_NOUNS.contains(ev.lower())) continue;
            String nameKey;
            String demonym = DEMONYMS.get(name.lower());
            if (demonym != null) nameKey = Lexicon.key(demonym);
            else if (name.capital() && name.entity() && !STOP.contains(name.lower()) && !NOT_NAMES.contains(name.lower())
                    && !EVENT_NOUNS.contains(name.lower()) && name.lower().length() >= 2) nameKey = Lexicon.key(name.surface());
            else continue;
            String c = lex.canonical(nameKey);
            if (c != null && (!STOP.contains(nameKey) || name.acronym())) nameKey = c;
            else if (STOP.contains(nameKey)) continue;
            String term = nameKey + " " + Text.stem(ev.lower());
            if (lex.isStop(term) || lex.isStop(nameKey)) continue;
            String shown = (c != null && lex.display(c) != null ? lex.display(c) : demonym != null ? capitalize(demonym) : name.surface()) + " " + ev.lower();
            out.add(new Token(term, shown, true, true, false, true));
        }
        return out;
    }

    private static void single(W w, Lexicon lex, boolean titleCase, List<Token> out) {
        String lower = w.lower();
        String demonym = DEMONYMS.get(lower);
        if (demonym != null) {
            String c = lex.canonical(Lexicon.key(demonym));
            if (c != null) { if (!lex.isStop(c)) out.add(new Token(c, lex.display(c), true, c.indexOf(' ') > 0, titleCase)); }
            else if (!lex.isStop(demonym)) out.add(new Token(demonym, capitalize(demonym), true));
            return;
        }
        if (w.entity() || w.acronym()) {
            String k = Lexicon.key(w.surface());
            String c = lex.canonical(k);
            // An alias that is also an everyday word ("US", "WHO") only counts in capitals.
            if (c != null && (!STOP.contains(k) || w.acronym())) {
                if (lex.isStop(c)) return;
                out.add(new Token(c, lex.display(c), true, c.indexOf(' ') > 0, titleCase));
                return;
            }
        }
        if (lower.length() < 3 && !w.acronym()) return;
        if (STOP.contains(lower)) return;
        if (lower.chars().allMatch(Character::isDigit)) return;
        String term = stem(lower);
        if (lex.isStop(term) || lex.isStop(lower)) return;
        out.add(new Token(term, w.acronym() ? w.surface() : capitalize(w.surface()), w.entity() && !NOT_NAMES.contains(lower)));
    }

    /** Emits the known names inside a run of capitalized words, and the run itself when it is 2-4 words. */
    private static void emitPhrases(List<W> run, Lexicon lex, boolean titleCase, List<Token> out) {
        while (!run.isEmpty() && JOINERS.contains(run.get(run.size() - 1).lower()) && !run.get(run.size() - 1).capital()) run.remove(run.size() - 1);
        int n = run.size();
        if (n < 2) return;
        // Known multi-word names inside the run, longest first, not overlapping.
        boolean[] used = new boolean[n];
        boolean wholeKnown = false;
        for (int len = n; len >= 2; len--) {
            for (int i = 0; i + len <= n; i++) {
                boolean free = true;
                for (int j = i; j < i + len; j++) if (used[j]) { free = false; break; }
                if (!free || JOINERS.contains(run.get(i).lower()) || JOINERS.contains(run.get(i + len - 1).lower())) continue;
                String c = lex.canonical(Lexicon.key(join(run, i, i + len)));
                if (c == null) continue;
                if (!lex.isStop(c)) out.add(new Token(c, lex.display(c), true, true, titleCase));
                for (int j = i; j < i + len; j++) used[j] = true;
                if (len == n) wholeKnown = true;
            }
        }
        int names = 0;
        for (W w : run) if (!JOINERS.contains(w.lower()) || w.capital()) names++;
        if (wholeKnown || names > MAX_PHRASE_WORDS) return;
        String surface = join(run, 0, n);
        String k = Lexicon.key(surface);
        if (k.indexOf(' ') < 0 || lex.isStop(k)) return;
        out.add(new Token(k, surface, true, true, titleCase));
    }

    private static String join(List<W> run, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < to; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(run.get(i).surface());
        }
        return sb.toString();
    }

    /** Splits text into words, noting capitals, acronyms, sentence starts and whether only a space separates a word from the previous one. */
    private static List<W> words(String text) {
        List<W> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        boolean sentenceStart = true;
        int lastEnd = 0;
        while (m.find()) {
            if (endsSentence(text, lastEnd, m.start())) sentenceStart = true;
            boolean plain = lastEnd > 0 && onlySpaces(text, lastEnd, m.start());
            lastEnd = m.end();
            String g = m.group().replace('’', '\'');
            // Hyphenated words are split into their parts: "Xi-Trump-Putin", "Kaliningrad-bound".
            String[] parts = g.indexOf('-') > 0 ? g.split("-+") : new String[]{g};
            for (int p = 0; p < parts.length; p++) {
                String w = parts[p];
                if (w.endsWith("'s")) w = w.substring(0, w.length() - 2);
                w = w.replaceAll("[.']+$", "");
                if (w.isEmpty() || !Character.isLetter(w.charAt(0))) continue;
                String lower = w.toLowerCase(Locale.ROOT);
                boolean acronym = w.length() >= 2 && w.equals(w.toUpperCase(Locale.ROOT)) && w.chars().anyMatch(Character::isLetter);
                boolean capital = Character.isUpperCase(w.charAt(0));
                boolean entity = acronym || (capital && !sentenceStart);
                if (capital && sentenceStart && !STOP.contains(lower) && !COMMON_START.contains(lower)) entity = true;
                out.add(new W(w, lower, acronym, capital, entity, p == 0 ? plain : false));
                sentenceStart = false;
            }
        }
        return out;
    }

    private static boolean onlySpaces(String text, int from, int to) {
        for (int i = from; i < to; i++) if (!Character.isWhitespace(text.charAt(i))) return false;
        return to > from;
    }

    /** "Ukrainian Drones Hit Russian Warship At Naval Base In Libya": most words capitalized. */
    private static boolean isTitleCase(List<W> words) {
        int counted = 0, caps = 0;
        for (W w : words) {
            if (w.lower().length() < 4 && STOP.contains(w.lower())) continue;
            counted++;
            if (w.capital()) caps++;
        }
        return counted >= 4 && caps >= 0.75 * counted;
    }

    /** Maps adjectives to the place they refer to, so "Russian refineries" links to "Russia talks". */
    static final java.util.Map<String, String> DEMONYMS = java.util.Map.ofEntries(
            java.util.Map.entry("russian", "russia"), java.util.Map.entry("russians", "russia"),
            java.util.Map.entry("ukrainian", "ukraine"), java.util.Map.entry("ukrainians", "ukraine"),
            java.util.Map.entry("iranian", "iran"), java.util.Map.entry("iranians", "iran"),
            java.util.Map.entry("chinese", "china"), java.util.Map.entry("israeli", "israel"),
            java.util.Map.entry("israelis", "israel"), java.util.Map.entry("palestinian", "palestine"),
            java.util.Map.entry("palestinians", "palestine"), java.util.Map.entry("syrian", "syria"),
            java.util.Map.entry("iraqi", "iraq"), java.util.Map.entry("saudi", "saudi arabia"),
            java.util.Map.entry("turkish", "turkey"), java.util.Map.entry("türkiye", "turkey"),
            java.util.Map.entry("german", "germany"), java.util.Map.entry("french", "france"),
            java.util.Map.entry("british", "britain"), java.util.Map.entry("uk", "britain"),
            java.util.Map.entry("japanese", "japan"), java.util.Map.entry("korean", "korea"),
            java.util.Map.entry("indian", "india"), java.util.Map.entry("pakistani", "pakistan"),
            java.util.Map.entry("venezuelan", "venezuela"), java.util.Map.entry("mexican", "mexico"),
            java.util.Map.entry("canadian", "canada"), java.util.Map.entry("brazilian", "brazil"),
            java.util.Map.entry("european", "europe"), java.util.Map.entry("african", "africa"),
            java.util.Map.entry("sudanese", "sudan"), java.util.Map.entry("lebanese", "lebanon"),
            java.util.Map.entry("taiwanese", "taiwan"), java.util.Map.entry("haitian", "haiti"),
            java.util.Map.entry("colombian", "colombia"), java.util.Map.entry("egyptian", "egypt"),
            java.util.Map.entry("afghan", "afghanistan"), java.util.Map.entry("yemeni", "yemen"),
            java.util.Map.entry("belgian", "belgium"), java.util.Map.entry("spanish", "spain"), java.util.Map.entry("polish", "poland"), java.util.Map.entry("finnish", "finland"), java.util.Map.entry("dutch", "netherlands"), java.util.Map.entry("italian", "italy"), java.util.Map.entry("greek", "greece"), java.util.Map.entry("swedish", "sweden"), java.util.Map.entry("norwegian", "norway"), java.util.Map.entry("danish", "denmark"), java.util.Map.entry("austrian", "austria"), java.util.Map.entry("swiss", "switzerland"), java.util.Map.entry("irish", "ireland"), java.util.Map.entry("scottish", "scotland"), java.util.Map.entry("welsh", "wales"), java.util.Map.entry("indonesian", "indonesia"), java.util.Map.entry("filipino", "philippines"), java.util.Map.entry("thai", "thailand"), java.util.Map.entry("vietnamese", "vietnam"), java.util.Map.entry("malaysian", "malaysia"), java.util.Map.entry("australian", "australia"), java.util.Map.entry("nigerian", "nigeria"), java.util.Map.entry("kenyan", "kenya"), java.util.Map.entry("ethiopian", "ethiopia"), java.util.Map.entry("somali", "somalia"), java.util.Map.entry("libyan", "libya"), java.util.Map.entry("jordanian", "jordan"), java.util.Map.entry("qatari", "qatar"), java.util.Map.entry("emirati", "united arab emirates"), java.util.Map.entry("kuwaiti", "kuwait"), java.util.Map.entry("argentine", "argentina"), java.util.Map.entry("argentinian", "argentina"), java.util.Map.entry("chilean", "chile"), java.util.Map.entry("peruvian", "peru"), java.util.Map.entry("cuban", "cuba"), java.util.Map.entry("estonian", "estonia"), java.util.Map.entry("latvian", "latvia"), java.util.Map.entry("lithuanian", "lithuania"), java.util.Map.entry("hungarian", "hungary"), java.util.Map.entry("romanian", "romania"), java.util.Map.entry("serbian", "serbia"), java.util.Map.entry("czech", "czech republic"), java.util.Map.entry("slovak", "slovakia"), java.util.Map.entry("belarusian", "belarus"), java.util.Map.entry("armenian", "armenia"), java.util.Map.entry("azerbaijani", "azerbaijan"), java.util.Map.entry("kazakh", "kazakhstan"), java.util.Map.entry("uzbek", "uzbekistan"), java.util.Map.entry("bangladeshi", "bangladesh"), java.util.Map.entry("nepali", "nepal"), java.util.Map.entry("burmese", "myanmar"), java.util.Map.entry("congolese", "dr congo"), java.util.Map.entry("algerian", "algeria"), java.util.Map.entry("moroccan", "morocco"), java.util.Map.entry("tunisian", "tunisia"), java.util.Map.entry("ghanaian", "ghana"), java.util.Map.entry("zimbabwean", "zimbabwe"), java.util.Map.entry("ugandan", "uganda"), java.util.Map.entry("rwandan", "rwanda"), java.util.Map.entry("portuguese", "portugal"), java.util.Map.entry("bulgarian", "bulgaria"), java.util.Map.entry("croatian", "croatia"), java.util.Map.entry("slovenian", "slovenia"), java.util.Map.entry("moldovan", "moldova"), java.util.Map.entry("icelandic", "iceland"), java.util.Map.entry("venezuelans", "venezuela"), java.util.Map.entry("nicaraguan", "nicaragua"), java.util.Map.entry("ecuadorian", "ecuador"), java.util.Map.entry("bolivian", "bolivia"), java.util.Map.entry("guatemalan", "guatemala"), java.util.Map.entry("salvadoran", "el salvador"), java.util.Map.entry("honduran", "honduras"), java.util.Map.entry("jamaican", "jamaica"), java.util.Map.entry("singaporean", "singapore"), java.util.Map.entry("cambodian", "cambodia"), java.util.Map.entry("laotian", "laos"), java.util.Map.entry("mongolian", "mongolia"), java.util.Map.entry("tibetan", "tibet"), java.util.Map.entry("kurdish", "kurds"), java.util.Map.entry("houthi", "houthis"), java.util.Map.entry("eritrean", "eritrea"), java.util.Map.entry("malian", "mali"), java.util.Map.entry("senegalese", "senegal"), java.util.Map.entry("cameroonian", "cameroon"), java.util.Map.entry("zambian", "zambia"), java.util.Map.entry("angolan", "angola"), java.util.Map.entry("mozambican", "mozambique"), java.util.Map.entry("tanzanian", "tanzania"), java.util.Map.entry("namibian", "namibia"), java.util.Map.entry("botswanan", "botswana"), java.util.Map.entry("libyans", "libya"));

    /** Nationality words for a place ("belgium" -> belgian), for searching. */
    public static java.util.List<String> demonymsOf(String place) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (var e : DEMONYMS.entrySet()) if (e.getValue().equals(place)) out.add(e.getKey());
        return out;
    }

    /** True if the gap between two words ends with sentence punctuation (ignoring spaces). */
    private static boolean endsSentence(String text, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            char ch = text.charAt(i);
            if (Character.isWhitespace(ch)) continue;
            return ".!?:;\"“”|–—".indexOf(ch) >= 0;
        }
        return false;
    }

    static String stem(String w) {
        if (w.length() > 5 && w.endsWith("ies")) return w.substring(0, w.length() - 3) + "y";
        if (w.length() > 4 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is")) return w.substring(0, w.length() - 1);
        return w;
    }

    private static String capitalize(String w) {
        if (w.isEmpty() || Character.isUpperCase(w.charAt(0))) return w;
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }

    /** Words that start sentences/headlines often and are not names. */
    private static final Set<String> COMMON_START = java.util.Set.copyOf(java.util.List.of(
            "how", "why", "what", "when", "where", "who", "watch", "live", "breaking", "analysis",
            "opinion", "explainer", "inside", "exclusive", "first", "new", "more", "some", "many", "most",
            "here", "there", "this", "that", "these", "those", "after", "before", "as", "at", "in", "on",
            "is", "are", "was", "were", "can", "could", "will", "would", "should", "may", "might", "does", "did"));

    public static final Set<String> STOP = java.util.Set.copyOf(java.util.List.of(
            "a", "about", "above", "across", "after", "again", "against", "ago", "all", "almost", "along", "already",
            "also", "although", "always", "am", "amid", "among", "an", "and", "another", "any", "anyone", "are", "around",
            "as", "at", "back", "be", "became", "because", "become", "been", "before", "behind", "being", "below",
            "best", "better", "between", "big", "biggest", "both", "but", "by", "call", "calls", "came", "can", "cannot",
            "could", "day", "days", "did", "does", "doing", "done", "down", "during", "each", "early", "else", "end",
            "even", "ever", "every", "few", "first", "for", "from", "further", "get", "gets", "getting", "give", "given",
            "go", "goes", "going", "gone", "got", "had", "has", "have", "having", "he", "her", "here", "hers", "him",
            "his", "how", "however", "if", "in", "including", "inside", "into", "is", "it", "its", "just", "key", "know",
            "last", "late", "later", "latest", "least", "less", "let", "like", "likely", "live", "long", "look", "made",
            "make", "makes", "making", "many", "may", "me", "might", "more", "most", "move", "moves", "much", "must",
            "my", "near", "need", "needs", "never", "new", "news", "next", "no", "not", "now", "of", "off", "old", "on",
            "once", "one", "only", "onto", "or", "other", "others", "our", "out", "over", "own", "part", "past", "per",
            "plan", "plans", "put", "rather", "really", "report", "reports", "reported", "right", "said", "same", "say",
            "saying", "says", "see", "seen", "set", "she", "should", "show", "shows", "since", "so", "some", "still",
            "such", "take", "takes", "taken", "than", "that", "the", "their", "them", "then", "there", "these", "they",
            "thing", "things", "this", "those", "though", "three", "through", "time", "times", "to", "today", "told",
            "too", "top", "toward", "towards", "two", "under", "until", "up", "update", "updates", "upon", "us", "use",
            "used", "very", "via", "video", "want", "wants", "was", "watch", "way", "we", "week", "weeks", "well",
            "were", "what", "when", "where", "whether", "which", "while", "who", "whom", "whose", "why", "will", "with",
            "within", "without", "would", "year", "years", "yet", "you", "your", "monday", "tuesday", "wednesday",
            "thursday", "friday", "saturday", "sunday", "january", "february", "march", "april", "june", "july",
            "august", "september", "october", "november", "december", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
            "sep", "sept", "oct", "nov", "dec", "mr", "mrs", "ms", "dr", "according", "continue", "reading", "read",
            "full", "story", "click", "here", "image", "photo", "photos", "getty", "images", "reuters", "ap", "afp",
            "bbc", "cnn", "nyt", "npr", "cnbc", "guardian", "post", "says", "week's", "people", "man", "woman", "world",
            "first", "second", "third", "four", "five", "six", "seven", "eight", "nine", "ten", "hundred", "thousand",
            "million", "billion", "percent", "nearly", "around", "officials", "official", "people's", "could", "amid",
            "despite", "against", "after", "early", "become", "help", "home", "high", "low", "lot", "lead", "leads",
            "warns", "warn", "warning", "urges", "hit", "hits", "face", "faces", "amid", "following", "ahead", "hold", "holds", "held", "start", "starts", "started", "open", "opens", "opened", "jump", "jumps", "jumped", "run", "runs", "come", "comes", "different", "effectively", "effort", "find", "finds", "found", "named", "weigh", "weighs", "tie", "ties", "break", "breaks", "track", "rose", "rise", "rises", "risen", "fall", "falls", "fell", "slide", "slides", "deepen", "deepens", "beat", "beats", "cut", "cutting", "win", "wins", "winning", "won", "ends", "ended", "shelve", "shelves", "raise", "raising", "driven", "remain", "remains", "lose", "losing", "lost", "surprise", "visit", "visits", "trip", "offset", "keep", "keeps", "kept", "seek", "seeks", "sought", "bring", "brings", "brought", "turn", "turns", "pay", "pays", "deal", "deals", "plunge", "plunges", "soar", "soars", "surge", "surges", "steady", "steadies", "climb", "climbs", "drop", "drops", "boost", "boosts", "big", "huge", "major", "rare", "hot", "true", "real", "full", "free", "good", "bad", "great", "small", "large", "largest", "highest", "lowest", "record", "annually", "announce", "announces", "announced", "claim", "claims", "claimed"));
}
