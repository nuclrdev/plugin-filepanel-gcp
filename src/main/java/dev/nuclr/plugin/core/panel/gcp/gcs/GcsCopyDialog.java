package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.nuclr.plugin.core.panel.gcp.gcs.GcsCopyConflictDialog.Action;
import lombok.extern.slf4j.Slf4j;

/**
 * Modal F5 "Copy" setup dialog for the GCP panel, mirroring the local file-system plugin's dialog
 * (the two live in isolated classloaders, so the UI is intentionally duplicated) with only the
 * options that apply to Cloud Storage objects: the destination folder and how already-existing
 * files are handled. Returns the chosen {@link Options} on Copy, or {@code null} on Cancel / ESC.
 * May be called from any thread — it marshals to the EDT and blocks for the answer.
 */
@Slf4j
final class GcsCopyDialog {

    private static final String TITLE = "Copy";

    /** How an already-existing target is resolved: {@code null} action means "Ask" (prompt per clash). */
    record Options(Path destination, Action existing) {}

    /** Upload (accept-copy) choice: the conflict mode ({@code null} = Ask). */
    record Upload(Action existing) {}

    private GcsCopyDialog() {
    }

    /**
     * Setup dialog for accepting a copy <em>into</em> a bucket (upload). The destination is fixed
     * (shown read-only as a {@code gs://} URL); only the already-existing-files behaviour is chosen.
     *
     * @param header          what is being copied (e.g. {@code "1.jpg"} or {@code "3 items"})
     * @param destinationLabel the {@code gs://bucket/prefix} the files will be uploaded into
     * @return the chosen options, or {@code null} if the user cancelled
     */
    static Upload showUpload(String header, String destinationLabel) {
        final Upload[] result = new Upload[1];
        runOnEdtAndWait(() -> result[0] = buildUpload(header, destinationLabel));
        return result[0];
    }

    private static Upload buildUpload(String header, String destinationLabel) {

        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        JDialog dialog = new JDialog(owner, TITLE, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JTextField destField = new JTextField(destinationLabel, 40);
        destField.setEditable(false);
        JPanel destPanel = new JPanel(new BorderLayout(0, 4));
        destPanel.add(new JLabel("Copy " + header + " to:"), BorderLayout.NORTH);
        destPanel.add(destField, BorderLayout.CENTER);

        JComboBox<String> existing = new JComboBox<>(new String[] {
                "Ask", "Overwrite", "Skip", "Rename", "Append" });
        JPanel existingRow = new JPanel(new BorderLayout(8, 0));
        existingRow.add(new JLabel("Already existing files:"), BorderLayout.WEST);
        existingRow.add(existing, BorderLayout.CENTER);

        JButton copyButton = new JButton("Copy");
        JButton cancelButton = new JButton("Cancel");
        final Upload[] chosen = new Upload[1];

        copyButton.addActionListener(e -> {
            chosen[0] = new Upload(existingAction(existing.getSelectedIndex()));
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.add(copyButton);
        buttons.add(cancelButton);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        destPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        existingRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(destPanel);
        body.add(Box.createVerticalStrut(10));
        body.add(existingRow);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.add(body, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(copyButton);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(520, dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(copyButton::requestFocusInWindow);
        dialog.setVisible(true);

        return chosen[0];
    }

    /**
     * @param header        what is being copied (e.g. {@code "1.jpg"} or {@code "3 items"})
     * @param defaultTarget pre-filled destination directory (the other panel's folder)
     */
    static Options show(String header, Path defaultTarget) {
        final Options[] result = new Options[1];
        runOnEdtAndWait(() -> result[0] = build(header, defaultTarget));
        return result[0];
    }

    private static Options build(String header, Path defaultTarget) {

        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        JDialog dialog = new JDialog(owner, TITLE, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JTextField destField = new JTextField(defaultTarget != null ? defaultTarget.toString() : "", 40);
        JPanel destPanel = new JPanel(new BorderLayout(0, 4));
        destPanel.add(new JLabel("Copy " + header + " to:"), BorderLayout.NORTH);
        destPanel.add(destField, BorderLayout.CENTER);

        JComboBox<String> existing = new JComboBox<>(new String[] {
                "Ask", "Overwrite", "Skip", "Rename", "Append" });
        JPanel existingRow = new JPanel(new BorderLayout(8, 0));
        existingRow.add(new JLabel("Already existing files:"), BorderLayout.WEST);
        existingRow.add(existing, BorderLayout.CENTER);

        JButton copyButton = new JButton("Copy");
        JButton cancelButton = new JButton("Cancel");

        final Options[] chosen = new Options[1];

        copyButton.addActionListener(e -> {
            Path destination = parseDestination(destField, defaultTarget);
            if (destination == null) {
                return; // invalid/empty destination; keep the dialog open
            }
            chosen[0] = new Options(destination, existingAction(existing.getSelectedIndex()));
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.add(copyButton);
        buttons.add(cancelButton);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        destPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
        existingRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(destPanel);
        body.add(Box.createVerticalStrut(10));
        body.add(existingRow);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.add(body, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(copyButton);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(520, dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(destField::requestFocusInWindow);
        dialog.setVisible(true); // blocks (modal) until disposed

        return chosen[0];
    }

    private static Path parseDestination(JTextField destField, Path baseDir) {
        String text = destField.getText() == null ? "" : destField.getText().trim();
        if (text.isEmpty()) {
            return null;
        }
        try {
            Path destination = Path.of(text);
            // A relative path is meant relative to the destination panel's folder, not the JVM's cwd.
            if (!destination.isAbsolute() && baseDir != null) {
                destination = baseDir.resolve(destination).normalize();
            }
            return destination;
        } catch (InvalidPathException e) {
            log.debug("Invalid copy destination [{}]: {}", text, e.getMessage());
            return null;
        }
    }

    /** Index 0 ("Ask") maps to {@code null}; the rest map to the matching auto-resolution action. */
    private static Action existingAction(int index) {
        return switch (index) {
            case 1 -> Action.OVERWRITE;
            case 2 -> Action.SKIP;
            case 3 -> Action.RENAME;
            case 4 -> Action.APPEND;
            default -> null; // Ask
        };
    }

    private static void runOnEdtAndWait(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (Exception e) {
            log.warn("Failed to run copy dialog on EDT: {}", e.getMessage(), e);
        }
    }
}
