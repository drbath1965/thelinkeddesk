package com.linkeddesk.analysis;

import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finds the countries a story is about (names, nationality words and capitals in headlines and summaries),
 * for the World map. Each country has an approximate centre (latitude, longitude) for its marker.
 */
public final class Countries {
    public record Country(String name, String code, double lat, double lon) {}

    /** name | code | lat | lon | other names, nationality words and capitals (comma separated) */
    private static final String DATA = """
            United States|US|39.8|-98.6|US,U.S.,USA,U.S.A.,America,American,Americans,Washington,White House,Pentagon
            Canada|CA|56.1|-106.3|Canadian,Canadians,Ottawa
            Mexico|MX|23.6|-102.5|Mexican,Mexicans,Mexico City
            Guatemala|GT|15.8|-90.2|Guatemalan
            Honduras|HN|15.2|-86.2|Honduran
            El Salvador|SV|13.8|-88.9|Salvadoran
            Nicaragua|NI|12.9|-85.2|Nicaraguan,Managua
            Costa Rica|CR|9.7|-83.8|Costa Rican
            Panama|PA|8.5|-80.8|Panamanian,Panama Canal
            Cuba|CU|21.5|-77.8|Cuban,Havana
            Haiti|HT|19.0|-72.3|Haitian,Port-au-Prince
            Dominican Republic|DO|18.7|-70.2|Santo Domingo
            Jamaica|JM|18.1|-77.3|Jamaican,Kingston
            Puerto Rico|PR|18.2|-66.6|Puerto Rican,San Juan
            Colombia|CO|4.6|-74.3|Colombian,Bogota,Bogotá
            Venezuela|VE|6.4|-66.6|Venezuelan,Venezuelans,Caracas
            Ecuador|EC|-1.8|-78.2|Ecuadorian,Quito
            Peru|PE|-9.2|-75.0|Peruvian,Lima
            Bolivia|BO|-16.3|-63.6|Bolivian,La Paz
            Brazil|BR|-14.2|-51.9|Brazilian,Brasilia,Brasília,Rio de Janeiro,Sao Paulo,São Paulo
            Chile|CL|-35.7|-71.5|Chilean,Santiago
            Argentina|AR|-38.4|-63.6|Argentine,Argentinian,Buenos Aires
            Uruguay|UY|-32.5|-55.8|Uruguayan,Montevideo
            Paraguay|PY|-23.4|-58.4|Paraguayan,Asuncion
            Guyana|GY|4.9|-58.9|Guyanese,Georgetown
            United Kingdom|GB|54.0|-2.5|UK,U.K.,Britain,British,England,English,Scotland,Scottish,Wales,Welsh,London,Downing Street
            Ireland|IE|53.4|-8.2|Irish,Dublin
            France|FR|46.6|2.2|French,Paris,Elysee,Élysée
            Spain|ES|40.2|-3.7|Spanish,Madrid,Barcelona,Catalonia
            Portugal|PT|39.6|-8.0|Portuguese,Lisbon
            Germany|DE|51.2|10.4|German,Germans,Berlin,Bundestag
            Netherlands|NL|52.1|5.3|Dutch,Amsterdam,The Hague
            Belgium|BE|50.6|4.5|Belgian,Brussels
            Luxembourg|LU|49.8|6.1|
            Switzerland|CH|46.8|8.2|Swiss,Bern,Geneva,Zurich,Davos
            Austria|AT|47.5|14.6|Austrian,Vienna
            Italy|IT|42.8|12.6|Italian,Italians,Rome,Milan
            Vatican|VA|41.9|12.5|Vatican,Pope,Holy See
            Greece|GR|39.1|22.0|Greek,Athens
            Cyprus|CY|35.1|33.4|Cypriot,Nicosia
            Malta|MT|35.9|14.4|Maltese
            Denmark|DK|56.0|10.0|Danish,Copenhagen
            Greenland|GL|72.0|-40.0|Greenlandic,Nuuk
            Norway|NO|61.0|9.0|Norwegian,Oslo
            Sweden|SE|62.0|15.5|Swedish,Stockholm
            Finland|FI|64.0|26.0|Finnish,Helsinki
            Iceland|IS|64.9|-18.6|Icelandic,Reykjavik
            Estonia|EE|58.6|25.0|Estonian,Tallinn
            Latvia|LV|56.9|24.6|Latvian,Riga
            Lithuania|LT|55.2|23.9|Lithuanian,Vilnius
            Poland|PL|52.0|19.4|Polish,Warsaw
            Czech Republic|CZ|49.8|15.5|Czech,Czechia,Prague
            Slovakia|SK|48.7|19.7|Slovak,Bratislava
            Hungary|HU|47.2|19.5|Hungarian,Budapest
            Romania|RO|45.9|25.0|Romanian,Bucharest
            Bulgaria|BG|42.7|25.5|Bulgarian,Sofia
            Serbia|RS|44.0|20.9|Serbian,Belgrade
            Croatia|HR|45.1|15.2|Croatian,Zagreb
            Bosnia|BA|43.9|17.7|Bosnian,Sarajevo,Bosnia and Herzegovina
            Kosovo|XK|42.6|20.9|Kosovar,Pristina
            Albania|AL|41.2|20.2|Albanian,Albanians,Tirana
            North Macedonia|MK|41.6|21.7|Macedonian,Skopje
            Slovenia|SI|46.2|14.9|Slovenian,Ljubljana
            Moldova|MD|47.4|28.4|Moldovan,Chisinau
            Ukraine|UA|48.4|31.2|Ukrainian,Ukrainians,Kyiv,Kiev,Zelenskyy,Zelensky,Kharkiv,Odesa,Donbas,Crimea
            Belarus|BY|53.7|28.0|Belarusian,Minsk,Lukashenko
            Russia|RU|61.5|96.0|Russian,Russians,Moscow,Kremlin,Putin,Kaliningrad
            Georgia|GE|42.3|43.4|Tbilisi
            Armenia|AM|40.1|45.0|Armenian,Yerevan
            Azerbaijan|AZ|40.1|47.6|Azerbaijani,Baku
            Kazakhstan|KZ|48.0|66.9|Kazakh,Astana
            Uzbekistan|UZ|41.4|64.6|Uzbek,Tashkent
            Turkmenistan|TM|38.9|59.6|Turkmen
            Kyrgyzstan|KG|41.2|74.8|Kyrgyz,Bishkek
            Tajikistan|TJ|38.9|71.3|Tajik,Dushanbe
            Turkey|TR|39.0|35.2|Turkish,Türkiye,Ankara,Istanbul,Antalya,Erdogan,Erdoğan
            Syria|SY|35.0|38.5|Syrian,Syrians,Damascus,Aleppo
            Lebanon|LB|33.9|35.9|Lebanese,Beirut,Hezbollah
            Israel|IL|31.0|34.9|Israeli,Israelis,Jerusalem,Tel Aviv,IDF,Netanyahu
            Palestine|PS|31.9|35.2|Palestinian,Palestinians,Gaza,West Bank,Hamas,Ramallah
            Jordan|JO|31.0|36.0|Jordanian,Amman
            Iraq|IQ|33.2|43.7|Iraqi,Baghdad,Mosul,Kurdistan
            Iran|IR|32.4|53.7|Iranian,Iranians,Tehran,Khamenei,Strait of Hormuz,Hormuz
            Saudi Arabia|SA|23.9|45.1|Saudi,Saudis,Riyadh
            Yemen|YE|15.6|48.5|Yemeni,Sanaa,Houthi,Houthis
            Oman|OM|21.5|55.9|Omani,Muscat
            United Arab Emirates|AE|23.4|53.8|UAE,Emirati,Abu Dhabi,Dubai
            Qatar|QA|25.4|51.2|Qatari,Doha
            Kuwait|KW|29.3|47.5|Kuwaiti
            Bahrain|BH|26.0|50.6|Bahraini,Manama
            Egypt|EG|26.8|30.8|Egyptian,Cairo,Suez
            Libya|LY|26.3|17.2|Libyan,Tripoli,Benghazi
            Tunisia|TN|33.9|9.5|Tunisian,Tunis
            Algeria|DZ|28.0|1.7|Algerian,Algiers
            Morocco|MA|31.8|-7.1|Moroccan,Rabat
            Sudan|SD|12.9|30.2|Sudanese,Khartoum,Darfur
            South Sudan|SS|7.9|30.0|South Sudanese,Juba
            Ethiopia|ET|9.1|40.5|Ethiopian,Addis Ababa
            Eritrea|ER|15.2|39.8|Eritrean,Asmara
            Somalia|SO|5.2|46.2|Somali,Mogadishu,Al-Shabaab
            Kenya|KE|0.0|37.9|Kenyan,Nairobi
            Uganda|UG|1.4|32.3|Ugandan,Kampala
            Rwanda|RW|-1.9|29.9|Rwandan,Kigali
            Burundi|BI|-3.4|29.9|Burundian
            Tanzania|TZ|-6.4|34.9|Tanzanian,Dodoma,Dar es Salaam
            DR Congo|CD|-4.0|21.8|DRC,Congo,Congolese,Kinshasa,Democratic Republic of Congo,Democratic Republic of the Congo,Goma,Ituri
            Nigeria|NG|9.1|8.7|Nigerian,Abuja,Lagos
            Ghana|GH|7.9|-1.0|Ghanaian,Accra
            Senegal|SN|14.5|-14.5|Senegalese,Dakar
            Mali|ML|17.6|-4.0|Malian,Bamako
            Burkina Faso|BF|12.2|-1.6|Burkinabe,Ouagadougou
            Niger|NE|17.6|8.1|Nigerien,Niamey
            Chad|TD|15.5|18.7|Chadian,N'Djamena
            Cameroon|CM|7.4|12.4|Cameroonian,Yaounde
            Ivory Coast|CI|7.5|-5.5|Ivorian,Cote d'Ivoire,Côte d'Ivoire,Abidjan
            Liberia|LR|6.4|-9.4|Liberian,Monrovia
            Sierra Leone|SL|8.5|-11.8|Freetown
            Guinea|GN|9.9|-9.7|Guinean,Conakry
            Angola|AO|-11.2|17.9|Angolan,Luanda
            Zambia|ZM|-13.1|27.8|Zambian,Lusaka
            Zimbabwe|ZW|-19.0|29.2|Zimbabwean,Harare
            Mozambique|MZ|-18.7|35.5|Mozambican,Maputo
            Malawi|MW|-13.3|34.3|Malawian,Lilongwe
            Madagascar|MG|-18.8|46.9|Malagasy,Antananarivo
            Namibia|NA|-22.9|18.5|Namibian,Windhoek
            Botswana|BW|-22.3|24.7|Botswanan,Gaborone
            South Africa|ZA|-30.6|22.9|South African,Pretoria,Johannesburg,Cape Town
            Afghanistan|AF|33.9|67.7|Afghan,Afghans,Kabul,Taliban
            Pakistan|PK|30.4|69.3|Pakistani,Islamabad,Karachi,Lahore
            India|IN|20.6|79.0|Indian,Indians,New Delhi,Delhi,Mumbai,Modi
            Bangladesh|BD|23.7|90.4|Bangladeshi,Dhaka
            Sri Lanka|LK|7.9|80.8|Sri Lankan,Colombo
            Nepal|NP|28.4|84.1|Nepali,Nepalese,Kathmandu
            Myanmar|MM|21.9|95.9|Burmese,Burma,Naypyidaw,Yangon
            Thailand|TH|15.9|101.0|Thai,Bangkok
            Cambodia|KH|12.6|104.9|Cambodian,Phnom Penh
            Laos|LA|19.9|102.5|Laotian,Vientiane
            Vietnam|VN|14.1|108.3|Vietnamese,Hanoi,Ho Chi Minh City
            Malaysia|MY|4.2|102.0|Malaysian,Kuala Lumpur
            Singapore|SG|1.35|103.8|Singaporean
            Indonesia|ID|-0.8|113.9|Indonesian,Jakarta,Bali
            Philippines|PH|12.9|121.8|Filipino,Philippine,Manila
            China|CN|35.9|104.2|Chinese,Beijing,Shanghai,Shenzhen,Xi Jinping,Xinjiang,Tibet
            Hong Kong|HK|22.3|114.2|
            Taiwan|TW|23.7|121.0|Taiwanese,Taipei
            Mongolia|MN|46.9|103.8|Mongolian,Ulaanbaatar
            North Korea|KP|40.3|127.5|North Korean,Pyongyang,DPRK,Kim Jong Un
            South Korea|KR|35.9|127.8|South Korean,Seoul,Kospi
            Japan|JP|36.2|138.3|Japanese,Tokyo,Osaka,Nikkei
            Australia|AU|-25.3|133.8|Australian,Australians,Canberra,Sydney,Melbourne
            New Zealand|NZ|-41.0|174.0|New Zealander,Wellington,Auckland
            Papua New Guinea|PG|-6.3|143.9|Port Moresby
            Fiji|FJ|-17.7|178.1|Fijian
            Antarctica|AQ|-82.0|0.0|Antarctic
            """;

    private static final List<Country> ALL = new ArrayList<>();
    /** lower-case name (1-6 words) -> country */
    private static final Map<String, Country> NAMES = new HashMap<>();
    /** names that only count in capitals (they are also everyday words or common names) */
    private static final Map<String, Country> CASED = new HashMap<>();
    private static int longest = 1;

    static {
        for (String line : DATA.split("\\R")) {
            if (line.isBlank()) continue;
            String[] p = line.split("\\|", -1);
            Country c = new Country(p[0], p[1], Double.parseDouble(p[2]), Double.parseDouble(p[3]));
            ALL.add(c);
            add(p[0], c);
            for (String a : p[4].split(",")) if (!a.isBlank()) add(a.strip(), c);
        }
    }

    private static void add(String name, Country c) {
        String k = norm(name);
        // Short all-capital names (US, UK, DRC) and everyday words count only as written.
        boolean cased = name.equals(name.toUpperCase(Locale.ROOT)) && name.replace(".", "").length() <= 4
                || java.util.Set.of("turkey", "chad", "jordan", "niger", "guinea", "georgia", "america", "lima", "santiago", "sofia", "kingston", "congo").contains(k);
        (cased ? CASED : NAMES).putIfAbsent(cased ? name.replace(".", "") : k, c);
        longest = Math.max(longest, k.split(" ").length);
    }

    private static String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace('’', '\'').replace(".", "").replaceAll("[^\\p{L}\\p{N}' ]+", " ").replaceAll("\\s+", " ").trim();
    }

    public static List<Country> all() { return List.copyOf(ALL); }

    public static Country byCode(String code) {
        for (Country c : ALL) if (c.code().equals(code)) return c;
        return null;
    }

    private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("[\\p{L}\\p{N}'’.]+");

    /** Countries mentioned in a text, with how often. */
    public static Map<Country, Integer> mentions(String text) {
        Map<Country, Integer> out = new LinkedHashMap<>();
        if (text == null || text.isEmpty()) return out;
        List<String> raw = new ArrayList<>();
        java.util.regex.Matcher m = WORD.matcher(text);
        while (m.find()) {
            String w = m.group().replace('’', '\'');
            if (w.endsWith("'s")) w = w.substring(0, w.length() - 2);
            while (w.endsWith(".") && w.length() > 1 && !w.matches("(?:[A-Z]\\.)+")) w = w.substring(0, w.length() - 1);
            raw.add(w);
        }
        for (int i = 0; i < raw.size(); i++) {
            Country found = null;
            int len = 0;
            for (int n = Math.min(longest, raw.size() - i); n >= 1 && found == null; n--) {
                StringBuilder sb = new StringBuilder();
                for (int j = i; j < i + n; j++) { if (sb.length() > 0) sb.append(' '); sb.append(raw.get(j)); }
                String phrase = sb.toString();
                if (!Character.isUpperCase(phrase.charAt(0))) continue;         // names are capitalized
                Country c = CASED.get(phrase.replace(".", ""));
                if (c == null) c = NAMES.get(norm(phrase));
                if (c != null) { found = c; len = n; }
            }
            if (found != null) { out.merge(found, 1, Integer::sum); i += len - 1; }
        }
        return out;
    }

    /**
     * The countries a story is about: named in a headline, or mentioned at least twice across its coverage.
     * Strongest first.
     */
    public static List<Country> of(StoryCluster s) {
        Map<Country, Integer> score = new HashMap<>();
        for (Article a : s.articles) {
            for (var e : mentions(a.title).entrySet()) score.merge(e.getKey(), 3 * e.getValue(), Integer::sum);
            for (var e : mentions(a.summary).entrySet()) score.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        List<Map.Entry<Country, Integer>> list = new ArrayList<>(score.entrySet());
        list.removeIf(e -> e.getValue() < 2);
        list.sort(Map.Entry.<Country, Integer>comparingByValue().reversed().thenComparing(e -> e.getKey().name()));
        List<Country> out = new ArrayList<>();
        for (var e : list) out.add(e.getKey());
        return out;
    }

    /** For each country, the stories about it (a story can be about several countries). */
    public static Map<Country, List<StoryCluster>> index(List<StoryCluster> stories) {
        Map<Country, List<StoryCluster>> out = new LinkedHashMap<>();
        for (StoryCluster s : stories) for (Country c : of(s)) out.computeIfAbsent(c, k -> new ArrayList<>()).add(s);
        return out;
    }
}
