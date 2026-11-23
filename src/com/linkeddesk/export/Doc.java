package com.linkeddesk.export;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A simple document (headings, paragraphs with links, bullet points) that can be written as HTML, Markdown or a
 * Word file (.docx), so a story, a thread, the briefing and the weekly report look the same everywhere.
 * The Word file is written directly (Office Open XML), with no extra libraries.
 */
public final class Doc {
    public record Run(String text, String link, boolean bold, boolean italic, boolean muted) {}

    public sealed interface Block permits Heading, Para, Rule {}
    public record Heading(int level, String text, String color) implements Block {}
    public record Para(List<Run> runs, boolean bullet, boolean small) implements Block {}
    public record Rule() implements Block {}

    public final String title;
    public final List<Block> blocks = new ArrayList<>();

    public Doc(String title) { this.title = title; }

    public Doc h1(String t) { blocks.add(new Heading(1, t, null)); return this; }
    public Doc h2(String t) { blocks.add(new Heading(2, t, null)); return this; }
    public Doc h2(String t, String color) { blocks.add(new Heading(2, t, color)); return this; }
    public Doc h3(String t) { blocks.add(new Heading(3, t, null)); return this; }
    public Doc rule() { blocks.add(new Rule()); return this; }

    /** A paragraph (or bullet point) built up from pieces. */
    public final class P {
        private final List<Run> runs = new ArrayList<>();
        private final boolean bullet, small;
        P(boolean bullet, boolean small) { this.bullet = bullet; this.small = small; blocks.add(new Para(runs, bullet, small)); }
        public P text(String t) { if (t != null && !t.isEmpty()) runs.add(new Run(t, null, false, false, false)); return this; }
        public P bold(String t) { runs.add(new Run(t, null, true, false, false)); return this; }
        public P italic(String t) { runs.add(new Run(t, null, false, true, false)); return this; }
        public P muted(String t) { runs.add(new Run(t, null, false, false, true)); return this; }
        public P link(String t, String url) { runs.add(new Run(t, web(url) ? url : null, false, false, false)); return this; }
        public P boldLink(String t, String url) { runs.add(new Run(t, web(url) ? url : null, true, false, false)); return this; }
        public P br() { runs.add(new Run("\n", null, false, false, false)); return this; }
    }

    public P p() { return new P(false, false); }
    public P small() { return new P(false, true); }
    public P bullet() { return new P(true, false); }

    static boolean web(String u) { return u != null && (u.startsWith("https://") || u.startsWith("http://")); }

    // ------------------------------------------------------------------ HTML

    public String toHtml() {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"utf-8\"><title>").append(esc(title)).append("</title>\n<style>\n"
                + "body{font-family:'Segoe UI',Arial,sans-serif;font-size:15px;line-height:1.45;color:#1b1b1b;max-width:820px;margin:24px auto;padding:0 16px}\n"
                + "h1{font-family:Georgia,serif;font-size:26px;margin:0 0 6px}h2{font-family:Georgia,serif;font-size:19px;margin:22px 0 6px;border-bottom:1px solid #ddd;padding-bottom:3px}\n"
                + "h3{font-size:15px;margin:14px 0 4px}p{margin:6px 0}ul{margin:4px 0 8px 20px;padding:0}li{margin:3px 0}a{color:#1f5fa8;text-decoration:none}a:hover{text-decoration:underline}\n"
                + ".m{color:#666}.s{font-size:13px}hr{border:0;border-top:1px solid #ddd;margin:16px 0}\n</style></head><body>\n");
        sb.append(htmlBody()).append("</body></html>\n");
        return sb.toString();
    }

    /** The document without the page around it (for pasting into Word or an email). */
    public String htmlBody() {
        StringBuilder sb = new StringBuilder();
        boolean inList = false;
        for (Block b : blocks) {
            boolean bullet = b instanceof Para p && p.bullet();
            if (inList && !bullet) { sb.append("</ul>\n"); inList = false; }
            if (b instanceof Heading h) {
                sb.append("<h").append(h.level()).append(h.color() == null ? "" : " style=\"color:" + h.color() + "\"").append('>')
                  .append(esc(h.text())).append("</h").append(h.level()).append(">\n");
            } else if (b instanceof Rule) {
                sb.append("<hr>\n");
            } else if (b instanceof Para p) {
                if (bullet && !inList) { sb.append("<ul>\n"); inList = true; }
                sb.append(bullet ? "<li" : "<p").append(p.small() ? " class=\"s\"" : "").append('>');
                for (Run r : p.runs()) sb.append(html(r));
                sb.append(bullet ? "</li>\n" : "</p>\n");
            }
        }
        if (inList) sb.append("</ul>\n");
        return sb.toString();
    }

    private static String html(Run r) {
        if (r.text().equals("\n")) return "<br>";
        String t = esc(r.text());
        if (r.link() != null) t = "<a href=\"" + esc(r.link()) + "\">" + t + "</a>";
        if (r.bold()) t = "<b>" + t + "</b>";
        if (r.italic()) t = "<i>" + t + "</i>";
        if (r.muted()) t = "<span class=\"m\">" + t + "</span>";
        return t;
    }

    static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ------------------------------------------------------------------ Markdown

    public String toMarkdown() {
        StringBuilder sb = new StringBuilder();
        Block prev = null;
        for (Block b : blocks) {
            if (b instanceof Heading h) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("#".repeat(h.level())).append(' ').append(md(h.text())).append("\n\n");
            } else if (b instanceof Rule) {
                sb.append("\n---\n\n");
            } else if (b instanceof Para p) {
                if (!p.bullet() && prev instanceof Para pp && pp.bullet()) sb.append('\n');
                sb.append(p.bullet() ? "- " : "");
                for (Run r : p.runs()) sb.append(md(r, p.bullet()));
                sb.append(p.bullet() ? "\n" : "\n\n");
            }
            prev = b;
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n").strip() + "\n";
    }

    private static String md(Run r, boolean bullet) {
        if (r.text().equals("\n")) return bullet ? "  \n  " : "  \n";
        String t = md(r.text());
        if (r.link() != null) t = "[" + t + "](" + r.link().replace(")", "%29").replace(" ", "%20") + ")";
        if (r.bold()) t = "**" + t + "**";
        if (r.italic()) t = "*" + t + "*";
        return t;
    }

    static String md(String s) {
        return s.replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_").replace("[", "\\[").replace("]", "\\]").replace("#", "\\#").replace("`", "\\`");
    }

    /** Plain text, with web addresses after the linked words (for mail programs without HTML, and the clipboard). */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        for (Block b : blocks) {
            if (b instanceof Heading h) sb.append(sb.length() > 0 ? "\n" : "").append(h.level() == 1 ? h.text().toUpperCase() : h.text()).append('\n');
            else if (b instanceof Rule) sb.append('\n');
            else if (b instanceof Para p) {
                sb.append(p.bullet() ? "- " : "");
                for (Run r : p.runs()) {
                    if (r.text().equals("\n")) { sb.append(p.bullet() ? "\n  " : "\n"); continue; }
                    sb.append(r.text());
                    if (r.link() != null) sb.append(" <").append(r.link()).append('>');
                }
                sb.append('\n');
                if (!p.bullet()) sb.append('\n');
            }
        }
        return sb.toString().replaceAll("\n{3,}", "\n\n").strip() + "\n";
    }

    // ------------------------------------------------------------------ Word (.docx)

    public byte[] toDocx() throws IOException {
        List<String> links = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        for (Block b : blocks) {
            if (b instanceof Heading h) {
                String style = h.level() == 1 ? "Title" : h.level() == 2 ? "Heading1" : "Heading2";
                body.append("<w:p><w:pPr><w:pStyle w:val=\"").append(style).append("\"/></w:pPr>")
                    .append(wrun(h.text(), false, false, false, false, h.color() == null ? null : h.color().replace("#", ""), 0)).append("</w:p>");
            } else if (b instanceof Rule) {
                body.append("<w:p><w:pPr><w:pBdr><w:bottom w:val=\"single\" w:sz=\"6\" w:space=\"1\" w:color=\"CCCCCC\"/></w:pBdr></w:pPr></w:p>");
            } else if (b instanceof Para p) {
                body.append("<w:p><w:pPr>");
                if (p.bullet()) body.append("<w:pStyle w:val=\"ListBullet\"/><w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"1\"/></w:numPr>");
                body.append("<w:spacing w:after=\"").append(p.bullet() ? 60 : 120).append("\"/></w:pPr>");
                int size = p.small() ? 18 : 0;
                for (Run r : p.runs()) {
                    if (r.text().equals("\n")) { body.append("<w:r><w:br/></w:r>"); continue; }
                    if (r.link() != null) {
                        links.add(r.link());
                        body.append("<w:hyperlink r:id=\"rIdL").append(links.size()).append("\" w:history=\"1\">")
                            .append(wrun(r.text(), r.bold(), r.italic(), r.muted(), true, null, size)).append("</w:hyperlink>");
                    } else body.append(wrun(r.text(), r.bold(), r.italic(), r.muted(), false, null, size));
                }
                body.append("</w:p>");
            }
        }
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><w:body>" + body
                + "<w:sectPr><w:pgSz w:w=\"12240\" w:h=\"15840\"/><w:pgMar w:top=\"1080\" w:right=\"1080\" w:bottom=\"1080\" w:left=\"1080\" "
                + "w:header=\"708\" w:footer=\"708\" w:gutter=\"0\"/></w:sectPr></w:body></w:document>";
        StringBuilder rels = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rIdS\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "<Relationship Id=\"rIdN\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering\" Target=\"numbering.xml\"/>");
        for (int i = 0; i < links.size(); i++)
            rels.append("<Relationship Id=\"rIdL").append(i + 1).append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/hyperlink\" Target=\"")
                .append(xml(links.get(i))).append("\" TargetMode=\"External\"/>");
        rels.append("</Relationships>");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            put(z, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
                    + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
                    + "<Override PartName=\"/word/numbering.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml\"/>"
                    + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
                    + "</Types>");
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
                    + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
                    + "</Relationships>");
            put(z, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" "
                    + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>" + xml(title) + "</dc:title><dc:creator>The Linked Desk</dc:creator>"
                    + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + Instant.now().toString().replaceAll("\\.\\d+Z$", "Z") + "</dcterms:created></cp:coreProperties>");
            put(z, "word/_rels/document.xml.rels", rels.toString());
            put(z, "word/document.xml", document);
            put(z, "word/styles.xml", STYLES);
            put(z, "word/numbering.xml", NUMBERING);
        }
        return out.toByteArray();
    }

    private static String wrun(String text, boolean bold, boolean italic, boolean muted, boolean link, String color, int size) {
        StringBuilder sb = new StringBuilder("<w:r><w:rPr>");
        if (bold) sb.append("<w:b/>");
        if (italic) sb.append("<w:i/>");
        if (link) sb.append("<w:color w:val=\"1F5FA8\"/><w:u w:val=\"single\"/>");
        else if (muted) sb.append("<w:color w:val=\"666666\"/>");
        else if (color != null) sb.append("<w:color w:val=\"").append(color).append("\"/>");
        if (size > 0) sb.append("<w:sz w:val=\"").append(size).append("\"/>");
        sb.append("</w:rPr><w:t xml:space=\"preserve\">").append(xml(text)).append("</w:t></w:r>");
        return sb.toString();
    }

    /** Text safe in XML (also drops characters XML 1.0 can't hold). */
    static String xml(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') continue;
            if (c == 0xFFFE || c == 0xFFFF) continue;
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static final String STYLES = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>"
            + "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault>"
            + "<w:pPrDefault><w:pPr><w:spacing w:after=\"120\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault></w:docDefaults>"
            + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
            + "<w:pPr><w:spacing w:after=\"120\"/></w:pPr><w:rPr><w:rFonts w:ascii=\"Georgia\" w:hAnsi=\"Georgia\"/><w:b/><w:sz w:val=\"44\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Heading1\"><w:name w:val=\"heading 1\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
            + "<w:pPr><w:keepNext/><w:spacing w:before=\"320\" w:after=\"100\"/><w:pBdr><w:bottom w:val=\"single\" w:sz=\"4\" w:space=\"2\" w:color=\"DDDDDD\"/></w:pBdr><w:outlineLvl w:val=\"0\"/></w:pPr>"
            + "<w:rPr><w:rFonts w:ascii=\"Georgia\" w:hAnsi=\"Georgia\"/><w:b/><w:sz w:val=\"30\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"Heading2\"><w:name w:val=\"heading 2\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>"
            + "<w:pPr><w:keepNext/><w:spacing w:before=\"200\" w:after=\"60\"/><w:outlineLvl w:val=\"1\"/></w:pPr><w:rPr><w:b/><w:sz w:val=\"24\"/></w:rPr></w:style>"
            + "<w:style w:type=\"paragraph\" w:styleId=\"ListBullet\"><w:name w:val=\"List Bullet\"/><w:basedOn w:val=\"Normal\"/>"
            + "<w:pPr><w:ind w:left=\"360\" w:hanging=\"360\"/></w:pPr></w:style>"
            + "<w:style w:type=\"character\" w:styleId=\"Hyperlink\"><w:name w:val=\"Hyperlink\"/><w:rPr><w:color w:val=\"1F5FA8\"/><w:u w:val=\"single\"/></w:rPr></w:style>"
            + "</w:styles>";

    private static final String NUMBERING = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<w:numbering xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
            + "<w:abstractNum w:abstractNumId=\"0\"><w:multiLevelType w:val=\"singleLevel\"/><w:lvl w:ilvl=\"0\"><w:start w:val=\"1\"/>"
            + "<w:numFmt w:val=\"bullet\"/><w:lvlText w:val=\"•\"/><w:lvlJc w:val=\"left\"/><w:pPr><w:ind w:left=\"360\" w:hanging=\"360\"/></w:pPr>"
            + "<w:rPr><w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\"/></w:rPr></w:lvl></w:abstractNum>"
            + "<w:num w:numId=\"1\"><w:abstractNumId w:val=\"0\"/></w:num></w:numbering>";
}
