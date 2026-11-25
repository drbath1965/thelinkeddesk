package com.linkeddesk.ui;

import com.linkeddesk.export.CaseFileDoc;
import com.linkeddesk.export.Exporter;
import com.linkeddesk.model.StoryCluster;
import com.linkeddesk.store.CaseFiles;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** News > Case files (UI-F19): your folders of stories, with notes, exported as one briefing pack. */
final class CaseFilesDialog extends JDialog {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy").withZone(ZoneId.systemDefault());
    private final Host host;
    private final DefaultListModel<CaseFiles.CaseFile> cases = new DefaultListModel<>();
    private final JList<CaseFiles.CaseFile> caseList = new JList<>(cases);
    private final DefaultListModel<CaseFiles.Item> items = new DefaultListModel<>();
    private final JList<CaseFiles.Item> itemList = new JList<>(items);
    private final JTextArea caseNote = new JTextArea(4, 40), itemNote = new JTextArea(4, 40);
    private final JLabel itemTitle = new JLabel(" ");
    private boolean filling;

    CaseFilesDialog(Window owner, Host host, String select) {
        super(owner, "Case files", ModalityType.MODELESS);
        this.host = host;
        caseList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                var c = (CaseFiles.CaseFile) v;
                setText("<html><b>" + Html.esc(c.name()) + "</b><br><span style='color:" + (s ? "" : Theme.muted()) + "'>" + c.items().size()
                        + (c.items().size() == 1 ? " story" : " stories") + "</span></html>");
                setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                return this;
            }
        });
        itemList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                var it = (CaseFiles.Item) v;
                setText("<html><div style='width:" + Html.cssPx(420) + "px'>" + Html.esc(it.headline()) + "<br><span style='color:" + (s ? "" : Theme.muted()) + "'>"
                        + (it.published() > 0 ? DAY.format(Instant.ofEpochMilli(it.published())) : "") + (it.note().isBlank() ? "" : " · has a note") + "</span></div></html>");
                setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
                return this;
            }
        });
        caseList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showCase(); });
        itemList.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showItem(); });
        itemList.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) open(); }
        });

        JButton add = new JButton("New…"), rename = new JButton("Rename…"), del = new JButton("Delete…");
        add.addActionListener(e -> newCase());
        rename.addActionListener(e -> {
            var c = caseList.getSelectedValue();
            if (c == null) return;
            String n = JOptionPane.showInputDialog(this, "New name:", c.name());
            if (n != null && !n.isBlank()) act(() -> cf().rename(c.id(), n), c.id());
        });
        del.addActionListener(e -> {
            var c = caseList.getSelectedValue();
            if (c != null && JOptionPane.showConfirmDialog(this, "Delete the case file \"" + c.name() + "\" and its notes? The stories stay in your news.", "Delete",
                    JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) act(() -> cf().delete(c.id()), null);
        });
        JPanel left = new JPanel(new BorderLayout(0, 4));
        JPanel lb = new JPanel(new GridLayout(1, 3, 4, 0));
        lb.add(add); lb.add(rename); lb.add(del);
        left.add(new JScrollPane(caseList), BorderLayout.CENTER);
        left.add(lb, BorderLayout.SOUTH);

        caseNote.setLineWrap(true); caseNote.setWrapStyleWord(true);
        itemNote.setLineWrap(true); itemNote.setWrapStyleWord(true);
        JButton saveCaseNote = new JButton("Save note"), saveItemNote = new JButton("Save note"), open = new JButton("Open"), remove = new JButton("Remove from case file"),
                word = new JButton("Export to Word…"), html = new JButton("Export to HTML…");
        saveCaseNote.addActionListener(e -> { var c = caseList.getSelectedValue(); if (c != null) act(() -> cf().setNote(c.id(), caseNote.getText()), c.id()); });
        saveItemNote.addActionListener(e -> {
            var c = caseList.getSelectedValue(); var it = itemList.getSelectedValue();
            if (c != null && it != null) act(() -> cf().setItemNote(c.id(), it, itemNote.getText()), c.id());
        });
        open.addActionListener(e -> open());
        remove.addActionListener(e -> {
            var c = caseList.getSelectedValue(); var it = itemList.getSelectedValue();
            if (c != null && it != null) act(() -> cf().remove(c.id(), it), c.id());
        });
        word.addActionListener(e -> export(Exporter.Format.WORD));
        html.addActionListener(e -> export(Exporter.Format.HTML));

        JPanel cn = new JPanel(new BorderLayout(0, 2));
        cn.add(new JLabel("Your note on this case file"), BorderLayout.NORTH);
        cn.add(new JScrollPane(caseNote), BorderLayout.CENTER);
        JPanel cnb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0)); cnb.add(word); cnb.add(html); cnb.add(saveCaseNote);
        cn.add(cnb, BorderLayout.SOUTH);
        JPanel in = new JPanel(new BorderLayout(0, 2));
        in.add(itemTitle, BorderLayout.NORTH);
        in.add(new JScrollPane(itemNote), BorderLayout.CENTER);
        JPanel inb = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0)); inb.add(open); inb.add(remove); inb.add(saveItemNote);
        in.add(inb, BorderLayout.SOUTH);
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.add(cn, BorderLayout.NORTH);
        right.add(new JScrollPane(itemList), BorderLayout.CENTER);
        right.add(in, BorderLayout.SOUTH);
        right.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(Math.round(290 * Theme.scale));
        split.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
        getContentPane().add(split);
        setSize(Math.round(900 * Theme.scale), Math.round(620 * Theme.scale));
        setLocationRelativeTo(owner);
        reload(select);
    }

    private CaseFiles cf() { return host.service().caseFiles(); }

    private interface Action { void run() throws Exception; }

    private void act(Action a, String select) {
        try { a.run(); reload(select); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "Case files", JOptionPane.WARNING_MESSAGE); }
    }

    void reload(String select) {
        filling = true;
        var keepItem = itemList.getSelectedValue();
        cases.clear();
        for (var c : cf().all()) cases.addElement(c);
        filling = false;
        int pick = 0;
        for (int i = 0; i < cases.size(); i++) if (cases.get(i).id().equals(select)) pick = i;
        if (!cases.isEmpty()) caseList.setSelectedIndex(pick); else showCase();
        if (keepItem != null) for (int i = 0; i < items.size(); i++) if (items.get(i).ids().equals(keepItem.ids())) itemList.setSelectedIndex(i);
    }

    private void showCase() {
        if (filling) return;
        var c = caseList.getSelectedValue();
        items.clear();
        caseNote.setText(c == null ? "" : c.note());
        caseNote.setEnabled(c != null);
        if (c != null) for (var it : c.items()) items.addElement(it);
        if (c == null) itemTitle.setText(cases.isEmpty() ? "No case files yet: click New…, or use \"Add to case file\" on a story." : " ");
        showItem();
    }

    private void showItem() {
        var it = itemList.getSelectedValue();
        itemTitle.setText(it == null ? (items.isEmpty() ? "No stories in this case file yet: use \"Add to case file\" on a story." : "Pick a story to add a note.")
                : "<html>Your note on: <b>" + Html.esc(it.headline()) + "</b></html>");
        itemNote.setText(it == null ? "" : it.note());
        itemNote.setEnabled(it != null);
    }

    private void open() {
        var it = itemList.getSelectedValue();
        if (it == null) return;
        StoryCluster s = host.storyWithArticles(it.ids());
        if (s != null) host.openStoryById(s.id);
        else try { Desktop.getDesktop().browse(URI.create(it.url())); } catch (Exception ex) { com.linkeddesk.app.Logs.error("Opening " + it.url(), ex); }
    }

    private void newCase() {
        String n = JOptionPane.showInputDialog(this, "Name of the new case file (e.g. Golden Dome contracts):", "New case file", JOptionPane.QUESTION_MESSAGE);
        if (n == null || n.isBlank()) return;
        try { var c = cf().create(n); reload(c.id()); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, ex.getMessage(), "Case files", JOptionPane.WARNING_MESSAGE); }
    }

    private void export(Exporter.Format f) {
        var c = caseList.getSelectedValue();
        if (c == null) return;
        ExportUi.save(this, host, CaseFileDoc.build(c, host.service()::caseArticles), List.of(), f);
    }

    /** "Add to case file…" on a story: pick a case file (or make a new one) and add the story. */
    static void addStory(Component parent, Host host, StoryCluster s) {
        CaseFiles cf = host.service().caseFiles();
        List<CaseFiles.CaseFile> all = cf.all();
        Object[] choices = new Object[all.size() + 1];
        for (int i = 0; i < all.size(); i++) choices[i] = all.get(i).name();
        choices[all.size()] = "New case file…";
        Object pick = JOptionPane.showInputDialog(parent, "Add \"" + s.headline + "\" to:", "Add to case file", JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
        if (pick == null) return;
        try {
            String id;
            if (pick.equals("New case file…")) {
                String n = JOptionPane.showInputDialog(parent, "Name of the new case file:", "New case file", JOptionPane.QUESTION_MESSAGE);
                if (n == null || n.isBlank()) return;
                id = cf.create(n).id();
            } else id = all.get(java.util.Arrays.asList(choices).indexOf(pick)).id();
            boolean added = cf.add(id, s);
            host.status(added ? "Added to the case file \"" + cf.get(id).name() + "\"." : "It's already in that case file.");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent, ex.getMessage(), "Add to case file", JOptionPane.WARNING_MESSAGE);
        }
    }
}
