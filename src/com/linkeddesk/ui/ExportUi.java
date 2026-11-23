package com.linkeddesk.ui;

import com.linkeddesk.export.Doc;
import com.linkeddesk.export.Exporter;
import com.linkeddesk.model.StoryCluster;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.*;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/** Saving exports and copying to the clipboard (EXP-F01). */
final class ExportUi {
    private ExportUi() {}

    private static File lastDir;

    /** Asks where to save, in which format, then writes the file and offers to open it. */
    static void save(Component parent, Host host, Doc doc, List<StoryCluster> rows, Exporter.Format... formats) {
        if (formats.length == 0) formats = Exporter.Format.values();
        JFileChooser fc = new JFileChooser(lastDir != null ? lastDir : new File(System.getProperty("user.home"), "Documents"));
        fc.setDialogTitle("Export: " + doc.title);
        fc.setAcceptAllFileFilterUsed(false);
        FileNameExtensionFilter first = null;
        java.util.Map<javax.swing.filechooser.FileFilter, Exporter.Format> byFilter = new java.util.HashMap<>();
        for (Exporter.Format f : formats) {
            FileNameExtensionFilter ff = new FileNameExtensionFilter(f.label, f.ext);
            fc.addChoosableFileFilter(ff);
            byFilter.put(ff, f);
            if (first == null) first = ff;
        }
        fc.setFileFilter(first);
        fc.setSelectedFile(new File(fc.getCurrentDirectory(), Exporter.fileName(doc.title, formats[0])));
        fc.addPropertyChangeListener(JFileChooser.FILE_FILTER_CHANGED_PROPERTY, e -> {
            Exporter.Format f = byFilter.get(fc.getFileFilter());
            File cur = fc.getSelectedFile();
            String base = cur == null ? Exporter.fileName(doc.title, f) : cur.getName().replaceAll("\\.[A-Za-z]{2,4}$", "") + "." + f.ext;
            SwingUtilities.invokeLater(() -> fc.setSelectedFile(new File(fc.getCurrentDirectory(), base)));
        });
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        Exporter.Format f = byFilter.getOrDefault(fc.getFileFilter(), formats[0]);
        File file = fc.getSelectedFile();
        if (!file.getName().toLowerCase().endsWith("." + f.ext)) file = new File(file.getParentFile(), file.getName() + "." + f.ext);
        lastDir = file.getParentFile();
        if (file.exists() && JOptionPane.showConfirmDialog(parent, file.getName() + " already exists. Replace it?", "Export", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        Path out = file.toPath();
        try {
            Exporter.write(doc, rows, f, out);
            host.status("Saved " + out.getFileName() + ".");
            Object[] opts = {"Open it", "Show the folder", "Close"};
            int c = JOptionPane.showOptionDialog(parent, "Saved:\n" + out, "Export", JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[0]);
            if (c == 0) Desktop.getDesktop().open(out.toFile());
            else if (c == 1) Desktop.getDesktop().open(out.getParent().toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, "Could not save the file: " + ex.getMessage(), "Export", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Puts a document on the clipboard as formatted text (Word, Outlook, Gmail keep the links) and as plain text. */
    static void copy(Doc doc) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection(doc), null);
    }

    static HtmlSelection selection(Doc doc) { return new HtmlSelection("<html><body>" + doc.htmlBody() + "</body></html>", doc.toText()); }

    static final class HtmlSelection implements Transferable {
        private static final DataFlavor HTML_STRING, HTML_READER;
        static {
            try {
                HTML_STRING = new DataFlavor("text/html;class=java.lang.String");
                HTML_READER = new DataFlavor("text/html;class=java.io.Reader");
            } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }
        }
        private final String html, text;
        HtmlSelection(String html, String text) { this.html = html; this.text = text; }
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.allHtmlFlavor, HTML_STRING, HTML_READER, DataFlavor.stringFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor f) { for (DataFlavor x : getTransferDataFlavors()) if (x.equals(f)) return true; return false; }
        @Override public Object getTransferData(DataFlavor f) throws UnsupportedFlavorException, IOException {
            if (f.equals(DataFlavor.stringFlavor)) return text;
            if (f.equals(HTML_READER)) return new StringReader(html);
            if (f.isMimeTypeEqual("text/html") && f.getRepresentationClass() == String.class) return html;
            if (f.isMimeTypeEqual("text/html") && f.getRepresentationClass() == java.io.InputStream.class) return new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
            throw new UnsupportedFlavorException(f);
        }
    }
}
