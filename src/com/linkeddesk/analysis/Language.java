package com.linkeddesk.analysis;

import java.util.*;

/**
 * Which language a headline is in (ANL-F11), offline: by writing system first (Cyrillic, Arabic, Chinese…), and for
 * the Latin alphabet by the short everyday words each language uses ("que", "der", "les"…).
 */
public final class Language {
    private Language() {}

    public static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("en", "English"), Map.entry("es", "Spanish"), Map.entry("fr", "French"), Map.entry("de", "German"), Map.entry("it", "Italian"),
            Map.entry("pt", "Portuguese"), Map.entry("nl", "Dutch"), Map.entry("pl", "Polish"), Map.entry("sv", "Swedish"), Map.entry("tr", "Turkish"),
            Map.entry("id", "Indonesian"), Map.entry("ru", "Russian"), Map.entry("uk", "Ukrainian"), Map.entry("ar", "Arabic"), Map.entry("fa", "Persian"),
            Map.entry("he", "Hebrew"), Map.entry("el", "Greek"), Map.entry("zh", "Chinese"), Map.entry("ja", "Japanese"), Map.entry("ko", "Korean"),
            Map.entry("hi", "Hindi"), Map.entry("th", "Thai"));

    private static final Map<String, Set<String>> WORDS = new LinkedHashMap<>();
    static {
        w("en", "the a an of and to in is for on with that by from as at are was has have will after over says new its about not but this be who "
                + "or it he she they we his her their more than into up out what how why when while during against amid could would should been");
        w("es", "el la los las de del que y en un una por para con se su al es como más pero sus le ya fue este ha sobre entre tras según durante");
        w("fr", "le la les des du de et en un une pour que qui dans est sur au aux par pas plus ce cette avec sont ont été après selon mais");
        w("de", "der die das und den dem des ein eine ist nicht mit sich auf für im zu von bei nach wird sind auch aus wie über gegen vor");
        w("it", "il lo la gli le di del della che e è un una per con non sono nel nella alla dopo anche più come tra ha secondo");
        w("pt", "o a os as de do da dos das que e em um uma para com não por se na no mais ao é foi como após são sobre segundo");
        w("nl", "de het een en van in is op te dat met voor niet zijn aan bij ook naar door om er na wordt over");
        w("pl", "i w na z do się nie to że jest o po od za przez jak co dla oraz ale już ich jego");
        w("sv", "och i att det som en på är av för med till den inte har de om ett var men efter");
        w("tr", "ve bir bu da de ile için çok daha olarak gibi en ama olan sonra kadar yeni değil");
        w("id", "dan yang di ini itu dengan untuk dari tidak akan pada juga ke dalam karena oleh");
    }
    private static void w(String lang, String words) { WORDS.put(lang, new HashSet<>(Arrays.asList(words.split(" ")))); }

    /** The language code ("en", "es", "ru"…); "en" when unsure. */
    public static String detect(String text) {
        if (text == null || text.isBlank()) return "en";
        int letters = 0, latin = 0, cyr = 0, arab = 0, han = 0, kana = 0, hangul = 0, greek = 0, hebrew = 0, deva = 0, thai = 0, ukr = 0, persian = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp)) continue;
            letters++;
            Character.UnicodeScript sc = Character.UnicodeScript.of(cp);
            switch (sc) {
                case LATIN -> latin++;
                case CYRILLIC -> { cyr++; if ("іїєґІЇЄҐ".indexOf(cp) >= 0) ukr++; }
                case ARABIC -> { arab++; if ("پچژگ".indexOf(cp) >= 0) persian++; }
                case HAN -> han++;
                case HIRAGANA, KATAKANA -> kana++;
                case HANGUL -> hangul++;
                case GREEK -> greek++;
                case HEBREW -> hebrew++;
                case DEVANAGARI -> deva++;
                case THAI -> thai++;
                default -> { }
            }
        }
        if (letters == 0) return "en";
        double other = letters - latin;
        if (other / letters > 0.4) {
            int max = Math.max(Math.max(Math.max(cyr, arab), Math.max(han + kana, hangul)), Math.max(Math.max(greek, hebrew), Math.max(deva, thai)));
            if (max == cyr) return ukr > 0 ? "uk" : "ru";
            if (max == arab) return persian > 0 ? "fa" : "ar";
            if (max == han + kana) return kana > 0 ? "ja" : "zh";
            if (max == hangul) return "ko";
            if (max == greek) return "el";
            if (max == hebrew) return "he";
            if (max == deva) return "hi";
            return "th";
        }
        Map<String, Integer> score = new HashMap<>();
        for (String tok : text.toLowerCase(Locale.ROOT).split("[^\\p{L}']+")) {
            if (tok.isEmpty()) continue;
            for (var e : WORDS.entrySet()) if (e.getValue().contains(tok)) score.merge(e.getKey(), 1, Integer::sum);
        }
        String low = text.toLowerCase(Locale.ROOT);
        if (low.indexOf('ñ') >= 0 || low.indexOf('¿') >= 0 || low.indexOf('¡') >= 0) score.merge("es", 2, Integer::sum);
        if (low.indexOf('ß') >= 0) score.merge("de", 2, Integer::sum);
        if (low.indexOf('ã') >= 0 || low.indexOf('õ') >= 0 || low.contains("ção")) score.merge("pt", 2, Integer::sum);
        if (low.contains("ł") || low.contains("ś") || low.contains("ż") || low.contains("ą") || low.contains("ę")) score.merge("pl", 2, Integer::sum);
        if (low.contains("ğ") || low.contains("ş") || low.contains("ı")) score.merge("tr", 2, Integer::sum);
        if (low.contains("ç") && !low.contains("ção")) score.merge("fr", 1, Integer::sum);
        if (low.contains("è") || low.contains("ê") || low.contains("à")) score.merge("fr", 1, Integer::sum);
        int en = score.getOrDefault("en", 0);
        String best = "en";
        int bestScore = en;
        for (var e : score.entrySet()) if (!e.getKey().equals("en") && e.getValue() > bestScore) { best = e.getKey(); bestScore = e.getValue(); }
        // Another language only when its words clearly outnumber English ones (names and loanwords are shared).
        return !best.equals("en") && bestScore >= 2 && bestScore >= en + 2 ? best : "en";
    }

    public static String name(String code) { return NAMES.getOrDefault(code, code); }
}
