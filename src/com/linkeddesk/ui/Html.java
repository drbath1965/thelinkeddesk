package com.linkeddesk.ui;

import com.linkeddesk.analysis.SearchQuery;
import com.linkeddesk.model.Article;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.model.StoryLink;
import com.linkeddesk.model.StoryThread;
import com.linkeddesk.model.Storyline;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Builds the HTML shown in the detail panes (Swing's HTML renderer supports basic HTML 3.2 + simple CSS). */
final class Html {
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d").withZone(ZoneId.systemDefault());

    private Html() {}

    /** Page styles in the current theme and text size. */
    static String css() {
        return "body{font-family:'Segoe UI',Arial,sans-serif;font-size:" + Theme.pt(11) + ";margin:10px;color:" + Theme.fg() + ";background:" + Theme.bg() + "}"
            + "h1{font-family:Georgia,serif;font-size:" + Theme.pt(17) + ";margin:4px 0 6px 0}"
            + "h2{font-family:Georgia,serif;font-size:" + Theme.pt(13) + ";margin:14px 0 4px 0;border-bottom:1px solid " + Theme.rule() + "}"
            + "a.hl{color:" + Theme.fg() + ";text-decoration:none}.read{margin:2px 0 10px 0;font-size:" + Theme.pt(10.5) + "}.read a{font-weight:bold}"
            + ".meta{color:" + Theme.muted() + ";font-size:" + Theme.pt(9.5) + "}.tag{font-size:" + Theme.pt(9) + ";font-weight:bold}"
            + ".terms{color:" + Theme.note() + ";font-size:" + Theme.pt(9.5) + "}.sum{margin:4px 0 8px 0}"
            + "a{color:" + Theme.link() + "}td{vertical-align:top;padding:3px 4px}"
            + ".act{font-size:" + Theme.pt(9) + ";color:" + Theme.faint() + "}.ok{color:" + Theme.ok() + ";font-size:" + Theme.pt(9) + ";font-weight:bold}.here{background:" + Theme.here() + "}"
            + ".warn{color:" + Theme.warn() + ";font-weight:bold}.star{color:" + Theme.star() + ";font-weight:bold}"
            + ".new{color:#ffffff;background:" + Theme.link() + ";font-size:" + Theme.pt(8.5) + ";padding:1px 4px}"
            + ".unread{font-weight:bold}.read{color:" + Theme.read() + "}.good{color:" + Theme.ok() + ";font-weight:bold}.bad{color:" + Theme.bad() + ";font-weight:bold}";
    }

    /**
     * A width in screen pixels as a CSS "px" value for Swing's HTML, which counts 1px as 1.3 screen pixels;
     * without this, list rows were wider than the list and long headlines were cut off instead of wrapping.
     */
    static int cssPx(int screenPixels) { return Math.max(50, Math.round(screenPixels / 1.3f)); }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 2) return "just now";
        if (m < 60) return m + " min ago";
        if (m < 48 * 60) return (m / 60) + " h ago";
        if (m < 14 * 24 * 60) return (m / 1440) + " days ago";
        return DAY.format(Instant.ofEpochMilli(t));
    }

    static String empty(String message) {
        return "<html><head><style>" + css() + "</style></head><body><p class='meta'>" + esc(message) + "</p></body></html>";
    }

    static String story(StoryCluster c, boolean canExplain) { return story(c, canExplain, null, false); }

    static String story(StoryCluster c, boolean canExplain, SearchQuery q) { return story(c, canExplain, q, false); }

    /**
     * Story detail; when {@code q} is given, the matched words are shown in bold. With {@code corrections},
     * the page offers Right/Wrong on links, "Not this story" on articles and "Move to desk".
     */
    /** What the page shows besides the story itself: your star, and how its coverage spreads over regions. */
    record Extras(boolean starred, com.linkeddesk.analysis.Regions.Spread spread, boolean newSinceRead,
                  java.util.function.BiFunction<StoryCluster, StoryCluster, com.linkeddesk.ai.LinkChecks.Check> checks,
                  java.util.function.Predicate<Article> hasFullText) {
        Extras(boolean starred, com.linkeddesk.analysis.Regions.Spread spread, boolean newSinceRead) { this(starred, spread, newSinceRead, null, null); }
        Extras(boolean starred, com.linkeddesk.analysis.Regions.Spread spread, boolean newSinceRead,
               java.util.function.BiFunction<StoryCluster, StoryCluster, com.linkeddesk.ai.LinkChecks.Check> checks) { this(starred, spread, newSinceRead, checks, null); }
    }

    static String story(StoryCluster c, boolean canExplain, SearchQuery q, boolean corrections) { return story(c, canExplain, q, corrections, null); }

    static String story(StoryCluster c, boolean canExplain, SearchQuery q, boolean corrections, Extras x) {
        java.util.function.UnaryOperator<String> hl = t -> q == null ? esc(t) : q.highlight(esc(t));
        StringBuilder sb = new StringBuilder("<html><head><style>").append(css()).append("</style></head><body>");
        sb.append("<span class='tag' style='color:").append(Theme.deskHex(c.category)).append("'>")
          .append(esc(c.category.label().toUpperCase())).append("</span> <span class='meta'>· ")
          .append(c.sourceCount()).append(c.sourceCount() == 1 ? " source" : " sources").append(" · ")
          .append(c.articles.size()).append(c.articles.size() == 1 ? " article" : " articles")
          .append(" · first seen ").append(WHEN.format(Instant.ofEpochMilli(c.firstSeen))).append("</span>");
        if (c.also != null) sb.append(" <span class='meta'>· also in </span><span class='tag' style='color:").append(Theme.deskHex(c.also)).append("'>")
                .append(esc(c.also.label().toUpperCase())).append("</span>");
        if (c.deskSetByYou) sb.append(" <span class='act'>(desk set by you)</span>");
        if (corrections) sb.append(" <span class='act'>· <a href='desk:'>Move to another desk…</a></span>");
        if (x != null) {
            sb.append(" <span class='act'>· ").append(x.starred() ? "<span class='star'>★ Starred</span> <a href='unstar:'>unstar</a>" : "<a href='star:'>☆ Star</a>")
              .append(" · <a href='unread:'>Mark unread</a> · <a href='watch:'>Watch this subject…</a> · <a href='copy:'>Copy</a> · <a href='export:'>Export…</a>")
              .append(c.sourceCount() >= 3 ? " · <a href='framing:'>How outlets framed it</a>" : "").append("</span>");
            if (x.newSinceRead()) sb.append(" <span class='new'>new coverage since you read it</span>");
        }
        // The headline opens the article, and a plain "Read it at …" link sits right under it.
        Article lead = com.linkeddesk.alerts.EmailDigest.lead(c);
        boolean web = lead.url.startsWith("http://") || lead.url.startsWith("https://");
        sb.append("<h1>").append(web ? "<a class='hl' href='" + esc(lead.url) + "'>" + hl.apply(c.headline) + "</a>" : hl.apply(c.headline)).append("</h1>");
        String summary = c.summary;
        if (summary.isEmpty()) for (Article a : c.articlesNewestFirst()) if (!a.summary.isEmpty()) { summary = a.summary; break; }
        if (!summary.isEmpty()) sb.append("<p class='sum'>").append(hl.apply(summary)).append("</p>");
        if (web) {
            sb.append("<p class='read'><a href='").append(esc(lead.url)).append("'>Read it at ").append(esc(lead.source))
              .append(lead.kindLabel().isEmpty() ? "" : " (" + lead.kindLabel().toLowerCase() + ")").append(" &#8599;</a>");
            if (c.sourceCount() > 1) sb.append(" <span class='meta'>· or one of the ").append(c.sourceCount() - 1).append(" other outlets under Coverage below</span>");
            sb.append("</p>");
        }
        if (summary.isEmpty())
            sb.append("<p class='meta'>").append(esc(lead.source)).append("'s feed gives only the headline, not a summary. Open the article to read it.</p>");
        if (x != null && x.spread() != null) sb.append(spread(c, x.spread()));
        if (!c.keyTerms.isEmpty()) {
            sb.append("<p class='terms'>Key terms: ").append(esc(String.join(", ", c.keyTerms.subList(0, Math.min(8, c.keyTerms.size()))))).append("</p>");
        }

        List<StoryCluster> line = c.storyline();
        if (line.size() > 1) {
            Storyline sl = new Storyline(line);
            sb.append("<h2>How this story developed (").append(line.size()).append(" stories over ").append(sl.days())
              .append(sl.days() == 1 ? " day" : " days").append(")</h2><table>");
            for (StoryCluster s : line) {
                boolean here = s == c;
                sb.append("<tr").append(here ? " class='here'" : "").append("><td width='70'><span class='meta'>")
                  .append(DAY.format(Instant.ofEpochMilli(s.firstSeen))).append("</span></td><td>");
                if (here) sb.append("<b>").append(esc(s.headline)).append("</b> <span class='meta'>(this story)</span>");
                else sb.append("<a href='story:").append(s.id).append("'>").append(esc(s.headline)).append("</a>");
                sb.append("<br><span class='meta'>").append(s.sourceCount()).append(s.sourceCount() == 1 ? " source" : " sources")
                  .append(" · ").append(s.articles.size()).append(s.articles.size() == 1 ? " article" : " articles").append("</span></td></tr>");
            }
            sb.append("</table>");
        }

        sb.append("<h2>Linked stories (").append(c.links.size()).append(")</h2>");
        if (c.links.isEmpty()) {
            sb.append("<p class='meta'>No other story in this time window shares enough distinctive names or topics.</p>");
        } else {
            sb.append("<table>");
            for (StoryLink l : c.links) {
                StoryCluster o = l.to();
                sb.append("<tr><td width='8' bgcolor='").append(Theme.deskHex(o.category)).append("'></td><td>")
                  .append("<a href='story:").append(o.id).append("'>").append(esc(o.headline)).append("</a><br>")
                  .append("<span class='meta'>").append(esc(o.category.label())).append(l.crossDesk() ? " (other desk)" : "")
                  .append(" · ").append(l.strength()).append(" link · ").append(ago(o.lastSeen)).append("</span><br>")
                  .append("<span class='terms'>Shared: ").append(l.sharedTerms().isEmpty() ? "no key words (you linked these)" : esc(String.join(", ", l.sharedTerms()))).append("</span>");
                if (canExplain) sb.append(" · <a href='explain:").append(o.id).append("'>Ask Claude why</a>");
                var chk = x == null || x.checks() == null ? null : x.checks().apply(c, o);
                if (chk != null) sb.append("<br>").append(checkBadge(chk));
                if (corrections) {
                    if (l.confirmed()) sb.append("<br><span class='ok'>✓ You marked this link right</span> <span class='act'><a href='undo:").append(o.id).append("'>undo</a></span>");
                    else sb.append("<br><span class='act'>Is this link right? <a href='good:").append(o.id).append("'>✓ Right</a> · <a href='bad:")
                            .append(o.id).append("'>✗ Wrong</a></span>");
                }
                sb.append("</td></tr>");
            }
            sb.append("</table>");
        }
        if (c.hiddenLinks > 0) {
            sb.append("<p class='act'>").append(c.hiddenLinks).append(c.hiddenLinks == 1 ? " link you marked wrong is" : " links you marked wrong are")
              .append(" hidden. Tools &gt; Your corrections lists them.</p>");
        }

        sb.append("<h2>Coverage</h2><table>");
        for (Article a : c.articlesNewestFirst()) {
            sb.append("<tr><td><a href='").append(esc(a.url)).append("'>").append(hl.apply(a.title)).append("</a><br>")
              .append("<span class='meta'>").append(esc(a.source)).append(a.kindLabel().isEmpty() ? "" : " · " + a.kindLabel())
              .append(com.linkeddesk.analysis.Coverage.opinion(a) ? " · <b>Opinion / analysis</b>" : "")
              .append(copyOf(c, a))
              .append(" · ").append(WHEN.format(Instant.ofEpochMilli(a.published))).append("</span>");
            if (!a.originalTitle.isEmpty())
                sb.append("<br><span class='terms'>Translated from ").append(esc(com.linkeddesk.analysis.Language.name(a.lang))).append(" · original: ")
                  .append(esc(a.originalTitle)).append("</span>");
            if (x != null && x.hasFullText() != null && x.hasFullText().test(a)) sb.append(" <span class='act'>· <a href='full:").append(esc(a.id)).append("'>Full text</a></span>");
            if (corrections && c.articles.size() > 1) sb.append(" <span class='act'>· <a href='split:").append(esc(a.id)).append("'>Not this story</a></span>");
            if (corrections) sb.append(" <span class='act'>· <a href='mute:").append(java.net.URLEncoder.encode(a.source, java.nio.charset.StandardCharsets.UTF_8))
                    .append("'>Mute this outlet</a></span>");
            if (!a.summary.isEmpty() && !a.summary.equals(c.summary)) {
                String s = a.summary.length() > 280 ? a.summary.substring(0, 280) + "…" : a.summary;
                sb.append("<br><span class='terms'>").append(hl.apply(s)).append("</span>");
            }
            sb.append("</td></tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEEE, MMMM d").withZone(ZoneId.systemDefault());

    /** The daily briefing: top stories per desk, new links between desks, storylines that moved on. */
    static String briefing(com.linkeddesk.analysis.Briefing b, com.linkeddesk.store.ReadState reads, com.linkeddesk.analysis.Regions regions) {
        return briefing(b, reads, regions, "");
    }

    static String briefing(com.linkeddesk.analysis.Briefing b, com.linkeddesk.store.ReadState reads, com.linkeddesk.analysis.Regions regions, String digestHtml) {
        StringBuilder sb = new StringBuilder("<html><head><style>").append(css()).append("</style></head><body>");
        sb.append("<h1>Your briefing · ").append(esc(DATE.format(Instant.ofEpochMilli(b.now())))).append("</h1>");
        if (b.isEmpty()) {
            return sb.append("<p class='meta'>Nothing downloaded in the last 24 hours yet. Click \"Download news now\".</p></body></html>").toString();
        }
        sb.append("<p class='meta'>The last 24 hours: ").append(String.format("%,d", b.articles())).append(" articles from ")
          .append(b.outlets()).append(" outlets in ").append(String.format("%,d", b.stories())).append(" stories. ")
          .append("Top ").append(com.linkeddesk.analysis.Briefing.PER_DESK).append(" per desk by how many outlets cover them and how fast coverage is growing. Click a story to read it.</p>");
        sb.append(digestHtml);
        if (!b.newLinks().isEmpty()) {
            sb.append("<h2>Newly connected across desks</h2><table>");
            for (StoryLink l : b.newLinks()) {
                sb.append("<tr><td>").append(storyRef(l.from(), reads, false)).append(" <span class='meta'>(").append(esc(l.from().category.label()))
                  .append(")</span><br>↔ ").append(storyRef(l.to(), reads, false)).append(" <span class='meta'>(").append(esc(l.to().category.label()))
                  .append(")</span><br><span class='terms'>Shared: ").append(esc(String.join(", ", l.sharedTerms()))).append(" · ").append(l.strength())
                  .append(" link").append(l.confirmed() ? " · ✓ confirmed by you" : "").append("</span></td></tr>");
            }
            sb.append("</table>");
        }
        for (var e : b.desks().entrySet()) {
            if (e.getValue().isEmpty()) continue;
            sb.append("<h2><span style='color:").append(Theme.deskHex(e.getKey())).append("'>").append(esc(e.getKey().label())).append("</span></h2><table>");
            int i = 0;
            for (var rk : e.getValue()) {
                StoryCluster c = rk.story();
                var sp = regions.spread(c);
                sb.append("<tr><td width='18' class='meta'>").append(++i).append(".</td><td>").append(storyRef(c, reads, true))
                  .append("<br><span class='meta'>").append(c.sourceCount()).append(c.sourceCount() == 1 ? " source" : " sources")
                  .append(" · ").append(rk.last24h()).append(" new in 24 h").append(rk.last6h() > 0 ? " (" + rk.last6h() + " in the last 6 h)" : "")
                  .append(" · ").append(sp.singleSource() ? "<span class='warn'>single source</span>" : esc(sp.label()))
                  .append(c.links.isEmpty() ? "" : " · ↔ " + c.links.size()).append("</span>");
                if (!c.summary.isEmpty()) {
                    String sum = c.summary.length() > 200 ? c.summary.substring(0, 200) + "…" : c.summary;
                    sb.append("<br><span class='terms'>").append(esc(sum)).append("</span>");
                }
                sb.append("</td></tr>");
            }
            sb.append("</table>");
        }
        if (!b.storylines().isEmpty()) {
            sb.append("<h2>Storylines that moved on today</h2><table>");
            for (Storyline l : b.storylines()) {
                sb.append("<tr><td>").append(storyRef(l.latest(), reads, false)).append("<br><span class='meta'>day ").append(l.days())
                  .append(" · started with: ").append(esc(l.first().headline)).append("</span></td></tr>");
            }
            sb.append("</table>");
        }
        return sb.append("</body></html>").toString();
    }

    /** Claude's verdict on a link, as a short coloured line. */
    static String checkBadge(com.linkeddesk.ai.LinkChecks.Check c) {
        return switch (c.verdict()) {
            case REAL -> "<span class='ok'>✓ Checked by Claude: real</span> <span class='act'>— " + esc(c.reason()) + "</span>";
            case COINCIDENCE -> "<span class='warn'>⚠ Likely coincidence (Claude)</span> <span class='act'>— " + esc(c.reason()) + "</span>";
            case UNSURE -> "<span class='act'><b>? Claude unsure</b> — " + esc(c.reason()) + "</span>";
        };
    }

    /** Text with [S3] citations turned into links to the stories (or greyed when the story can't be found). */
    static String cited(String text, java.util.function.Function<String, StoryCluster> story) {
        java.util.regex.Matcher m = com.linkeddesk.ai.Cite.TAG.matcher(esc(text));
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String id = "S" + m.group(1);
            StoryCluster c = story.apply(id);
            String rep = c == null ? "<span class='act'>[" + id + "]</span>"
                    : "<a href='story:" + c.id + "' title='" + esc(c.headline) + "'>[" + id + "]</a>";
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString().replace("\n", "<br>");
    }

    /** Claude's daily digest for the top of the briefing. */
    static String digest(com.linkeddesk.ai.Digest d, java.util.function.Function<String, StoryCluster> story, boolean canWrite, String budget) {
        StringBuilder sb = new StringBuilder();
        if (d == null || d.isEmpty()) {
            sb.append("<h2>How today connects</h2><p class='meta'>");
            if (canWrite) sb.append("Claude hasn't written today's digest yet. <a href='digest:'>Write it now</a> · ")
                    .append("<a href='check:'>Check today's top links with Claude</a> · ").append(esc(budget));
            else sb.append("Add an Anthropic API key in Tools &gt; Settings and Claude writes a short digest here after each daily download.");
            return sb.append("</p>").toString();
        }
        sb.append("<h2>How today connects <span class='act'>· written by Claude, ").append(esc(WHEN.format(Instant.ofEpochMilli(d.made())))).append("</span></h2>");
        if (!d.reported().isEmpty()) {
            sb.append("<p class='meta'><b>What the reports say</b></p><ul>");
            for (String l : d.reported()) sb.append("<li>").append(cited(l, story)).append("</li>");
            sb.append("</ul>");
        }
        if (!d.inference().isEmpty()) {
            sb.append("<p class='meta'><b>Claude's reading</b> (inference, not reported fact)</p><ul>");
            for (String l : d.inference()) sb.append("<li><i>").append(cited(l, story)).append("</i></li>");
            sb.append("</ul>");
        }
        if (!d.watch().isEmpty()) {
            sb.append("<p class='meta'><b>What to watch</b></p><ul>");
            for (String l : d.watch()) sb.append("<li>").append(cited(l, story)).append("</li>");
            sb.append("</ul>");
        }
        sb.append("<p class='act'>");
        if (!d.problems().isEmpty()) sb.append(esc(String.join(" ", d.problems()))).append(" · ");
        if (canWrite) sb.append("<a href='digest-again:'>Write again</a> · <a href='check:'>Check today's top links with Claude</a> · ").append(esc(budget));
        return sb.append("</p>").toString();
    }

    /** An answer from the archive, with its citations as links and the stories it was given. */
    /** The framing comparison (AI-F07): each point with links to the outlets it cites. */
    static String framing(com.linkeddesk.ai.Framing.Result r, String budget) {
        StringBuilder sb = new StringBuilder("<html><head><style>").append(css()).append("</style></head><body>");
        sb.append("<h1>How outlets framed it</h1><p class='sum'>").append(esc(r.story().headline)).append("</p>");
        sb.append("<p class='meta'>Written by Claude from the headlines and summaries of ").append(r.refs().size())
          .append(" outlets (full text where a feed gives it). It describes the coverage; it doesn't say which outlet is right. ")
          .append(esc(budget)).append("</p>");
        java.util.function.Function<String, String> cite = line -> {
            java.util.regex.Matcher m = com.linkeddesk.ai.Framing.TAG.matcher(esc(line));
            StringBuilder out = new StringBuilder();
            while (m.find()) {
                Article a = r.refs().get("O" + m.group(1));
                m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(a == null ? "" : " <a href='" + esc(a.url) + "'>[" + esc(a.source) + "]</a>"));
            }
            m.appendTail(out);
            return out.toString();
        };
        if (r.isEmpty()) sb.append("<p>Claude found nothing to compare in this coverage.</p>");
        for (String s : com.linkeddesk.ai.Framing.SECTIONS) {
            var lines = r.sections().get(s);
            if (lines.isEmpty()) continue;
            sb.append("<h2>").append(esc(com.linkeddesk.ai.Framing.TITLES.get(s))).append("</h2><ul>");
            for (String l : lines) sb.append("<li>").append(cite.apply(l)).append("</li>");
            sb.append("</ul>");
        }
        if (!r.problems().isEmpty()) sb.append("<p class='act'>").append(esc(String.join(" ", r.problems()))).append("</p>");
        sb.append("<h2>Outlets Claude was given</h2><table>");
        for (var e : r.refs().entrySet()) {
            Article a = e.getValue();
            sb.append("<tr><td width='34' class='meta'>[").append(e.getKey()).append("]</td><td><a href='").append(esc(a.url)).append("'>").append(esc(a.title))
              .append("</a><br><span class='meta'>").append(esc(a.source)).append(r.cited().contains(e.getKey()) ? " · cited" : " · not cited").append("</span></td></tr>");
        }
        return sb.append("</table></body></html>").toString();
    }

    static String answer(com.linkeddesk.ai.ArchiveQA.Answer a, String budget) {
        StringBuilder sb = new StringBuilder("<html><head><style>").append(css()).append("</style></head><body>");
        sb.append("<h1>").append(esc(a.question())).append("</h1>");
        if (a.nothingFound()) {
            return sb.append("<p>Nothing in your saved news matches this question, so Claude wasn't asked (no cost). Try other words, ")
                    .append("or use Search &gt; Search live news to fetch fresh articles first.</p></body></html>").toString();
        }
        sb.append("<p class='meta'>Answered by Claude from ").append(a.refs().size()).append(" of your saved stories (")
          .append(String.format("%,d", a.articlesSearched())).append(" matching articles searched). Click a citation to read the story. ")
          .append(esc(budget)).append("</p>");
        sb.append("<p>").append(cited(a.text(), a.refs()::get)).append("</p>");
        if (!a.problems().isEmpty()) sb.append("<p class='act'>").append(esc(String.join(" ", a.problems()))).append("</p>");
        sb.append("<h2>Stories Claude was given</h2><table>");
        for (var e : a.refs().entrySet()) {
            StoryCluster c = e.getValue();
            boolean used = a.cited().contains(e.getKey());
            sb.append("<tr><td width='34' class='meta'>[").append(e.getKey()).append("]</td><td>")
              .append("<a href='story:").append(c.id).append("'>").append(esc(c.headline)).append("</a>")
              .append("<br><span class='meta'>").append(DAY.format(Instant.ofEpochMilli(c.firstSeen))).append(" · ").append(esc(c.category.label()))
              .append(" · ").append(c.sourceCount()).append(c.sourceCount() == 1 ? " source" : " sources").append(used ? " · cited" : " · not cited")
              .append("</span></td></tr>");
        }
        return sb.append("</table></body></html>").toString();
    }

    /** A story link, bold while unread. */
    private static String storyRef(StoryCluster c, com.linkeddesk.store.ReadState reads, boolean star) {
        boolean unread = reads.isUnread(c);
        return (star && reads.isStarred(c) ? "<span class='star'>★</span> " : "") + "<a href='story:" + c.id + "'>"
                + (unread ? "<b>" + esc(c.headline) + "</b>" : esc(c.headline)) + "</a>";
    }

    /** "Covered by 5 outlets in 3 regions: Europe 2 · North America 2 · Middle East 1", or a warning. */
    /** " · same AP report" on an article that is a wire copy. */
    static String copyOf(StoryCluster c, Article a) {
        for (var cp : c.copies) if (cp.articles().contains(a)) return " · " + (cp.agency().isEmpty() ? "same text as " + (cp.outlets() - 1) + " other" + (cp.outlets() == 2 ? "" : "s") : "same " + esc(cp.agency()) + " report");
        return "";
    }

    static String spread(StoryCluster c, com.linkeddesk.analysis.Regions.Spread sp) {
        StringBuilder sb = new StringBuilder("<p class='terms'>");
        StringBuilder parts = new StringBuilder();
        for (var e : sp.regions().entrySet()) {
            if (parts.length() > 0) parts.append(" · ");
            parts.append(esc(e.getKey())).append(' ').append(e.getValue());
        }
        if (sp.singleSource()) {
            sb.append("<span class='warn'>⚠ Single source:</span> only ").append(esc(c.articles.get(0).source))
              .append(" (").append(esc(sp.regions().keySet().iterator().next())).append(") reports this so far.");
        } else if (sp.oneRegion()) {
            sb.append("<span class='warn'>⚠ One region:</span> ").append(sp.outlets()).append(" outlets, all ").append(parts);
        } else {
            sb.append("Covered by ").append(sp.outlets()).append(" outlets in ").append(sp.regionCount()).append(" regions: ").append(parts);
        }
        // ANL-F12/F13: how much of that is independent reporting.
        int opinions = 0;
        for (Article a : c.articles) if (com.linkeddesk.analysis.Coverage.opinion(a)) opinions++;
        if (!c.copies.isEmpty() || opinions > 0) {
            sb.append("<br>");
            List<String> why = new java.util.ArrayList<>();
            for (var cp : c.copies) why.add(cp.outlets() + " of them carry " + (cp.agency().isEmpty() ? "the same text" : "the same " + esc(cp.agency()) + " report"));
            if (opinions > 0) why.add(opinions + (opinions == 1 ? " is opinion or analysis" : " are opinion or analysis"));
            sb.append(String.join("; ", why)).append(", so <b>").append(c.independentCount()).append(c.independentCount() == 1 ? " outlet reports" : " outlets report")
              .append(" it independently</b>.");
        }
        return sb.append("</p>").toString();
    }

    static String storyline(Storyline line) {
        StringBuilder sb = new StringBuilder("<html><head><style>").append(css()).append("</style></head><body>");
        sb.append("<h1>").append(esc(line.latest().headline)).append("</h1><p class='meta'>One event followed across ")
          .append(line.days()).append(line.days() == 1 ? " day" : " days").append(": ").append(line.stories().size())
          .append(" stories, ").append(line.articleCount()).append(" articles, oldest first. Click a story to open it.</p><table>");
        for (StoryCluster c : line.stories()) {
            sb.append("<tr><td width='8' bgcolor='").append(Theme.deskHex(c.category)).append("'></td><td>")
              .append("<span class='meta'>").append(WHEN.format(Instant.ofEpochMilli(c.firstSeen))).append(" · ")
              .append(c.sourceCount()).append(c.sourceCount() == 1 ? " source" : " sources").append("</span><br>")
              .append("<a href='story:").append(c.id).append("'>").append(esc(c.headline)).append("</a>");
            if (!c.summary.isEmpty()) {
                String s = c.summary.length() > 220 ? c.summary.substring(0, 220) + "…" : c.summary;
                sb.append("<br><span class='terms'>").append(esc(s)).append("</span>");
            }
            sb.append("</td></tr>");
        }
        return sb.append("</table></body></html>").toString();
    }

    static String thread(StoryThread t) {
        StringBuilder sb = new StringBuilder("<html><head><style>").append(css()).append("</style></head><body>");
        sb.append("<h1>Thread: ").append(esc(t.topic())).append("</h1><p class='meta'>")
          .append(t.stories().size()).append(" linked stories across ").append(t.desks().size())
          .append(t.desks().size() == 1 ? " desk" : " desks").append(", oldest first. Click a story to open it.</p><table>");
        List<StoryCluster> stories = t.stories();
        for (StoryCluster c : stories) {
            sb.append("<tr><td width='8' bgcolor='").append(Theme.deskHex(c.category)).append("'></td><td>")
              .append("<span class='meta'>").append(DAY.format(Instant.ofEpochMilli(c.lastSeen))).append(" · ")
              .append(esc(c.category.label())).append(" · ").append(c.sourceCount()).append(c.sourceCount() == 1 ? " source" : " sources").append("</span><br>")
              .append("<a href='story:").append(c.id).append("'>").append(esc(c.headline)).append("</a>");
            if (!c.summary.isEmpty()) {
                String s = c.summary.length() > 220 ? c.summary.substring(0, 220) + "…" : c.summary;
                sb.append("<br><span class='terms'>").append(esc(s)).append("</span>");
            }
            sb.append("</td></tr>");
        }
        return sb.append("</table></body></html>").toString();
    }
}
