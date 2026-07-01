package dev.nuclr.plugin.core.panel.gcp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;

/**
 * The "File already exists" warning shown when an F5 copy from the GCP panel would overwrite a
 * file that is already present in the destination folder. Mirrors the local file-system plugin's
 * conflict dialog (the two plugins load in isolated classloaders, so the UI is intentionally
 * duplicated) — Overwrite / Skip / Rename / Append / Cancel, with a "Remember choice" checkbox
 * that applies the same answer to later clashes in the same copy run.
 *
 * <p>The "New" row describes the source GCS object (its display size and update time, since the
 * exact byte count is only known after download); the "Existing" row reads the target file's
 * attributes. Methods marshal to the EDT and block for the answer, so the resolver is safe to
 * call from the background copy thread.
 */
@Slf4j
final class GcsCopyConflictDialog {

    /** What to do about a single name clash. */
    enum Action { OVERWRITE, SKIP, RENAME, APPEND, CANCEL }

    /** A chosen {@link Action} plus, for {@link Action#RENAME}, the new target ({@code null} = auto). */
    record Resolution(Action action, Path renameTarget) {
        static Resolution of(Action action) {
            return new Resolution(action, null);
        }
    }

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

    /** Sticky answer once "Remember choice" was ticked; {@code null} until then. */
    private Resolution remembered;

    /**
     * Resolve a clash for {@code target}, describing the incoming object with {@code newSizeText}
     * and {@code newWhenText} (already display-formatted by the listing).
     */
    Resolution resolve(String newSizeText, String newWhenText, Path target) {
        if (remembered != null) {
            return remembered;
        }
        final Resolution[] result = new Resolution[1];
        runOnEdtAndWait(() -> result[0] = ask(newSizeText, newWhenText, target));
        return result[0];
    }

    private Resolution ask(String newSizeText, String newWhenText, Path target) {

        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        JDialog dialog = new JDialog(owner, "Warning", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JLabel title = new JLabel("File already exists", JLabel.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD));

        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.add(title, BorderLayout.NORTH);
        header.add(new JLabel(target.toString()), BorderLayout.CENTER);

        JPanel info = new JPanel(new GridLayout(2, 1, 0, 2));
        info.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        info.add(detailRow("New", blank(newSizeText) + "   " + blank(newWhenText)));
        info.add(detailRow("Existing", existingDetail(target)));

        JCheckBox remember = new JCheckBox("Remember choice");

        final Resolution[] picked = new Resolution[1];

        JButton overwrite = new JButton("Overwrite");
        JButton skip = new JButton("Skip");
        JButton rename = new JButton("Rename");
        JButton append = new JButton("Append");
        JButton cancel = new JButton("Cancel");

        overwrite.addActionListener(e -> {
            picked[0] = Resolution.of(Action.OVERWRITE);
            dialog.dispose();
        });
        skip.addActionListener(e -> {
            picked[0] = Resolution.of(Action.SKIP);
            dialog.dispose();
        });
        append.addActionListener(e -> {
            picked[0] = Resolution.of(Action.APPEND);
            dialog.dispose();
        });
        rename.addActionListener(e -> {
            // When remembering, store the auto-rename policy (null target → each clash auto-named).
            if (remember.isSelected()) {
                picked[0] = Resolution.of(Action.RENAME);
                dialog.dispose();
                return;
            }
            Path newTarget = promptRename(owner, target);
            if (newTarget == null) {
                return; // back to the warning dialog
            }
            picked[0] = new Resolution(Action.RENAME, newTarget);
            dialog.dispose();
        });
        cancel.addActionListener(e -> {
            picked[0] = Resolution.of(Action.CANCEL);
            dialog.dispose();
        });

        dialog.getRootPane().registerKeyboardAction(e -> {
            picked[0] = Resolution.of(Action.CANCEL);
            dialog.dispose();
        }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Left/Right arrows move between the buttons (wrapping around), in addition to Tab.
        installArrowTraversal(overwrite, skip, rename, append, cancel);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.add(overwrite);
        buttons.add(skip);
        buttons.add(rename);
        buttons.add(append);
        buttons.add(cancel);

        JPanel rememberRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        rememberRow.add(remember);

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.add(header, BorderLayout.NORTH);
        center.add(info, BorderLayout.CENTER);
        center.add(rememberRow, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        content.add(center, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(overwrite);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(overwrite::requestFocusInWindow);
        dialog.setVisible(true);

        Resolution resolution = picked[0] != null ? picked[0] : Resolution.of(Action.CANCEL);
        if (remember.isSelected() && resolution.action() != Action.CANCEL) {
            remembered = resolution;
        }
        return resolution;
    }

    /** @return the chosen new target path, or {@code null} if the rename was cancelled. */
    private Path promptRename(Window owner, Path target) {

        JDialog dialog = new JDialog(owner, "Rename", JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JTextField field = new JTextField(autoRename(target).toString(), 40);

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.add(new JLabel("New name:"), BorderLayout.NORTH);
        top.add(field, BorderLayout.CENTER);

        final Path[] result = new Path[1];

        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        ok.addActionListener(e -> {
            String text = field.getText() == null ? "" : field.getText().trim();
            if (text.isEmpty()) {
                return;
            }
            try {
                result[0] = Path.of(text);
                dialog.dispose();
            } catch (RuntimeException ex) {
                log.debug("Invalid rename target [{}]: {}", text, ex.getMessage());
            }
        });
        cancel.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.add(ok);
        buttons.add(cancel);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        content.add(top, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(480, dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(field::requestFocusInWindow);
        dialog.setVisible(true);

        return result[0];
    }

    /** {@code README.md} → {@code README (1).md}, incrementing until a free name is found. */
    static Path autoRename(Path target) {
        Path dir = target.getParent();
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot <= 0 ? name : name.substring(0, dot);
        String ext = dot <= 0 ? "" : name.substring(dot);
        for (int i = 1; ; i++) {
            String candidate = base + " (" + i + ")" + ext;
            Path path = dir == null ? Path.of(candidate) : dir.resolve(candidate);
            if (!Files.exists(path)) {
                return path;
            }
        }
    }

    private static String existingDetail(Path path) {
        try {
            BasicFileAttributes a = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.size() + "   " + STAMP.format(a.lastModifiedTime().toInstant().atZone(ZoneId.systemDefault()));
        } catch (IOException e) {
            return "?";
        }
    }

    private static JComponent detailRow(String label, String detail) {
        JPanel row = new JPanel(new BorderLayout(12, 0));
        row.add(new JLabel(label), BorderLayout.WEST);
        row.add(new JLabel(detail, JLabel.RIGHT), BorderLayout.EAST);
        return row;
    }

    private static String blank(String value) {
        return value == null ? "" : value;
    }

    /**
     * Wire Left/Right arrow keys to move focus across a row of buttons (wrapping at the ends),
     * so the warning dialog is fully keyboard-navigable without reaching for Tab.
     */
    private static void installArrowTraversal(JButton... buttons) {
        for (int i = 0; i < buttons.length; i++) {
            JButton self = buttons[i];
            JButton left = buttons[(i - 1 + buttons.length) % buttons.length];
            JButton right = buttons[(i + 1) % buttons.length];

            self.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("LEFT"), "focusLeft");
            self.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("RIGHT"), "focusRight");
            self.getActionMap().put("focusLeft", new AbstractAction() {
                private static final long serialVersionUID = 1L;

                @Override
                public void actionPerformed(ActionEvent e) {
                    left.requestFocusInWindow();
                }
            });
            self.getActionMap().put("focusRight", new AbstractAction() {
                private static final long serialVersionUID = 1L;

                @Override
                public void actionPerformed(ActionEvent e) {
                    right.requestFocusInWindow();
                }
            });
        }
    }

    private static void runOnEdtAndWait(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (Exception e) {
            log.warn("Failed to run copy conflict dialog on EDT: {}", e.getMessage(), e);
        }
    }
}
