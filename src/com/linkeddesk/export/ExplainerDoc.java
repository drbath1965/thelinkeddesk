package com.linkeddesk.export;

import com.linkeddesk.ai.Explainer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

/** An explainer as a document for Word, HTML, Markdown or the clipboard: each citation links to its article. */
public final class ExplainerDoc {
    private ExplainerDoc() {}
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a").withZone(ZoneId.systemDefault());

    public static Doc build(Explainer.Result r) {
        Doc d = new Doc("Explainer: " + r.subject());
        d.h1(r.subject());
        d.small().muted(r.level().label + " explainer by Claude, " + WHEN.format(Instant.ofEpochMilli(r.created())) + ", from " + r.refs().size()
                + " articles by " + r.outlets() + " outlets. Points marked (Claude's reading) are its interpretation, not reported fact.");
        for (String s : Explainer.SECTIONS) {
            List<String> lines = r.sections().get(s);
            if (lines == null || lines.isEmpty()) continue;
            d.h2(Explainer.TITLES.get(s));
            for (String l : lines) cited(d.bullet(), l, r.refs());
        }
        for (Explainer.FollowUp f : r.followUps()) {
            d.h2(f.label());
            for (String para : f.text().split("\\R")) {
                String p = para.strip();
                if (p.isEmpty()) continue;
                boolean bullet = p.matches("^[-•*]\\s.*");
                cited(bullet ? d.bullet() : d.p(), bullet ? p.substring(1).strip() : p, r.refs());
            }
        }
        if (!r.problems().isEmpty()) d.small().muted(String.join(" ", r.problems()));
        d.h2("Articles Claude read");
        for (var e : r.refs().entrySet()) {
            Explainer.Source s = e.getValue();
            d.bullet().muted("[" + e.getKey() + "] ").link(s.title(), s.url()).muted(" · " + s.source() + " · " + s.day() + (s.opinion() ? " · opinion" : ""));
        }
        return d;
    }

    private static void cited(Doc.P p, String line, Map<String, Explainer.Source> refs) {
        Matcher m = Explainer.TAG.matcher(line);
        int last = 0;
        while (m.find()) {
            p.text(line.substring(last, m.start()));
            Explainer.Source s = refs.get("S" + m.group(1));
            if (s != null) p.link("[" + s.source() + ", " + Explainer.shortDay(s) + "]", s.url());
            last = m.end();
        }
        p.text(line.substring(last));
    }
}
