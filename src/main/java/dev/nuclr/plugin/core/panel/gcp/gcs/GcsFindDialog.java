package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;

import javax.swing.Box;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
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
 * The Alt+F7 "Find files" dialog for the GCP panel. Searches by file name only (a shell-style
 * wildcard with {@code *} and {@code ?}) starting from the bucket/folder currently open. Content
 * search is intentionally not offered — Cloud Storage listing is name-only. Returns a
 * {@link GcsFindRequest} on Search, or {@code null} on Cancel / ESC.
 */
@Slf4j
public final class GcsFindDialog {

    private static final String TITLE = "Find files";

    private GcsFindDialog() {
    }

    /** Show the dialog for {@code gs://bucket/prefix}; blocks (modal) and returns the request or null. */
    public static GcsFindRequest show(String projectId, String bucket, String prefix) {
        final GcsFindRequest[] result = new GcsFindRequest[1];
        runOnEdtAndWait(() -> result[0] = build(projectId, bucket, prefix));
        return result[0];
    }

    private static GcsFindRequest build(String projectId, String bucket, String prefix) {

        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
        JDialog dialog = new JDialog(owner, TITLE, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

        JTextField patternField = new JTextField("*", 30);
        JPanel patternPanel = new JPanel(new BorderLayout(0, 4));
        patternPanel.add(new JLabel("File name (wildcards * and ?):"), BorderLayout.NORTH);
        patternPanel.add(patternField, BorderLayout.CENTER);

        JTextField locationField = new JTextField("gs://" + bucket + "/" + prefix, 30);
        locationField.setEditable(false);
        JPanel locationPanel = new JPanel(new BorderLayout(0, 4));
        locationPanel.add(new JLabel("Search in:"), BorderLayout.NORTH);
        locationPanel.add(locationField, BorderLayout.CENTER);

        JCheckBox recursive = new JCheckBox("Search sub-folders (recursive)", true);
        JCheckBox caseSensitive = new JCheckBox("Case sensitive", false);

        JButton searchButton = new JButton("Search");
        JButton cancelButton = new JButton("Cancel");
        final GcsFindRequest[] chosen = new GcsFindRequest[1];

        searchButton.addActionListener(e -> {
            String pattern = patternField.getText() == null ? "" : patternField.getText().trim();
            if (pattern.isEmpty()) {
                pattern = "*";
            }
            chosen[0] = new GcsFindRequest(projectId, bucket, prefix, pattern,
                    recursive.isSelected(), caseSensitive.isSelected());
            dialog.dispose();
        });
        cancelButton.addActionListener(e -> dialog.dispose());

        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
        buttons.add(searchButton);
        buttons.add(cancelButton);

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        for (JComponent part : new JComponent[] { patternPanel, locationPanel, recursive, caseSensitive }) {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(part);
            body.add(Box.createVerticalStrut(8));
        }

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.add(body, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(searchButton);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(460, dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);
        SwingUtilities.invokeLater(patternField::requestFocusInWindow);
        dialog.setVisible(true);

        return chosen[0];
    }

    private static void runOnEdtAndWait(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (Exception e) {
            log.warn("Failed to run find dialog on EDT: {}", e.getMessage(), e);
        }
    }
}
