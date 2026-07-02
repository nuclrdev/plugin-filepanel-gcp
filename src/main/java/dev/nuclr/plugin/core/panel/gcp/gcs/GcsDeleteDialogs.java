package dev.nuclr.plugin.core.panel.gcp.gcs;

import dev.nuclr.plugin.core.panel.gcp.*;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Modal confirmation and error popups for deleting Cloud Storage objects, matching the local
 * file-system plugin's {@code DeleteDialogs} (the two live in isolated classloaders, so the UI is
 * intentionally duplicated). Buttons are traversable by Tab and Left/Right arrows; ESC dismisses
 * with the "safe" choice (Cancel for confirmation, Skip for errors). Methods may be called from any
 * thread — they marshal to the EDT internally and block for the user's answer.
 */
@Slf4j
final class GcsDeleteDialogs {

    private static final String TITLE = "Delete";

    private GcsDeleteDialogs() {
    }

    /** @return true if the user confirmed (OK), false on Cancel/ESC. */
    static boolean confirmDelete(List<NuclrResource> sources) {

        boolean single = sources.size() <= 1;
        String header = single ? "Do you wish to delete the object?" : "Do you wish to delete the objects?";

        StringBuilder sb = new StringBuilder(header).append('\n');
        for (NuclrResource r : sources) {
            sb.append(fullPath(r)).append('\n');
        }

        Component message = buildText(sb.toString().stripTrailing(), !single);
        return choose(TITLE, message, "OK", "Cancel");
    }

    private static String fullPath(NuclrResource r) {
        if (r.getFullPath() != null) {
            return r.getFullPath();
        }
        return r.getName();
    }

    /** @return true to Skip (continue with the next item), false to Abort the operation. */
    static boolean error(String name, GcpError error) {
        String detail = error == null ? "Could not delete the object." : String.valueOf(error);
        Component message = buildText("Failed to delete:\n" + name + "\n\n" + detail, false);
        return choose(TITLE, message, "Skip", "Abort");
    }

    private static Component buildText(String text, boolean scroll) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setBorder(null);
        if (!scroll) {
            return area;
        }
        JScrollPane sp = new JScrollPane(area);
        sp.setPreferredSize(new Dimension(520, 260));
        return sp;
    }

    /**
     * Show a modal two-button dialog and return true if the {@code proceed} button was chosen.
     * The {@code safe} button (e.g. Cancel / Abort) is always the default and initially focused,
     * and ESC / closing the window is equivalent to pressing it.
     */
    private static boolean choose(String title, Component message, String proceedText, String safeText) {

        final boolean[] proceed = { false };

        Runnable show = () -> {
            Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            JDialog dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
            dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

            JButton proceedButton = new JButton(proceedText);
            JButton safeButton = new JButton(safeText);

            proceedButton.addActionListener(e -> {
                proceed[0] = true;
                dialog.dispose();
            });
            safeButton.addActionListener(e -> {
                proceed[0] = false;
                dialog.dispose();
            });

            installArrowTraversal(proceedButton, proceedButton, safeButton);
            installArrowTraversal(safeButton, proceedButton, safeButton);

            // ESC closes as the safe choice.
            dialog.getRootPane().registerKeyboardAction(e -> {
                proceed[0] = false;
                dialog.dispose();
            }, KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);

            JPanel content = new JPanel(new BorderLayout(0, 12));
            content.setBorder(BorderFactory.createEmptyBorder(16, 18, 12, 18));
            content.add(message, BorderLayout.CENTER);

            JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            buttons.add(proceedButton);
            buttons.add(safeButton);
            content.add(buttons, BorderLayout.SOUTH);

            dialog.setContentPane(content);
            // The safe button is the default (responds to Enter) and gets initial focus.
            dialog.getRootPane().setDefaultButton(safeButton);
            dialog.pack();
            dialog.setLocationRelativeTo(owner);
            SwingUtilities.invokeLater(safeButton::requestFocusInWindow);
            dialog.setVisible(true); // blocks (modal) until disposed
        };

        runOnEdtAndWait(show);
        return proceed[0];
    }

    /** Left arrow focuses the left button, Right arrow the right button (in addition to Tab). */
    private static void installArrowTraversal(JButton button, JButton left, JButton right) {
        button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("LEFT"), "focusLeft");
        button.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("RIGHT"), "focusRight");
        button.getActionMap().put("focusLeft", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                left.requestFocusInWindow();
            }
        });
        button.getActionMap().put("focusRight", new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(ActionEvent e) {
                right.requestFocusInWindow();
            }
        });
    }

    private static void runOnEdtAndWait(Runnable runnable) {
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(runnable);
        } catch (Exception e) {
            log.warn("Failed to run delete dialog on EDT: {}", e.getMessage(), e);
        }
    }
}
