package com.linkeddesk.export;

import com.linkeddesk.model.Article;
import com.linkeddesk.store.CaseFiles;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;

/** A case file (UI-F19) as one briefing pack: your note, then each story with your note and every source linked. */
public final class CaseFileDoc {
    private CaseFileDoc() {}
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault());

    /** {@code articlesOf}: the saved articles of a story (empty if they're no longer on this PC). */
    public static Doc build(CaseFiles.CaseFile c, Function<CaseFiles.Item, List<Article>> articlesOf) {
        Doc d = new Doc(c.name());
        d.h1(c.name());
        d.small().muted("Case file · " + c.items().size() + (c.items().size() == 1 ? " story" : " stories") + " · exported " + DAY.format(Instant.now()) + " from The Linked Desk");
        if (!c.note().isBlank()) for (String para : c.note().split("\\R")) if (!para.isBlank()) d.p().text(para.strip());
        int n = 0;
        for (CaseFiles.Item it : c.items()) {
            d.h2(++n + ". " + it.headline());
            d.small().muted((it.published() > 0 ? DAY.format(Instant.ofEpochMilli(it.published())) + " · " : "") + "added " + DAY.format(Instant.ofEpochMilli(it.added())));
            if (!it.note().isBlank()) for (String para : it.note().split("\\R")) if (!para.isBlank()) d.p().bold("Note: ").text(para.strip());
            List<Article> arts = articlesOf.apply(it);
            if (arts.isEmpty()) d.bullet().link(it.headline(), it.url());
            else for (Article a : arts) d.bullet().link(a.title, a.url).muted(" · " + a.source + " · " + DAY.format(Instant.ofEpochMilli(a.published)));
        }
        return d;
    }
}
