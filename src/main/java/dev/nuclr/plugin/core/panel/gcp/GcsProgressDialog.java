package dev.nuclr.plugin.core.panel.gcp;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.KeyboardFocusManager;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import lombok.extern.slf4j.Slf4j;

/**
 * Modal progress dialog with a Cancel button for the GCP panel's long-running operations (copy,
 * delete). Mirrors the local file-system plugin's dialog (the two live in isolated classloaders, so
 * the UI is intentionally duplicated). The supplied {@code work} runs on a background virtual thread
 * and receives a {@link NuclrPluginCallback} wired to this dialog; the modal dialog keeps the EDT
 * pumping (so prompts shown from {@code work} stay responsive) and blocks the caller until the work
 * finishes or the user cancels.
 *
 * <p>{@link #run} marshals itself onto the EDT, so it may be called from any thread.
 */
@Slf4j
final class GcsProgressDialog {

    private GcsProgressDialog() {
    }

    /** Run {@code work} under a progress dialog titled {@code title}, blocking until it completes. */
    static void run(String title, Consumer<NuclrPluginCallback> work) {
        runOnEdtAndWait(() -> show(title, work));
    }

    private static void show(String title, Consumer<NuclrPluginCallback> work) {

        Window owner = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();

        JLabel itemLabel = new JLabel("Preparing…");
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setStringPainted(true);
        JButton cancelButton = new JButton("Cancel");

        JDialog dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

        JPanel north = new JPanel(new BorderLayout(0, 6));
        north.add(itemLabel, BorderLayout.NORTH);
        north.add(bar, BorderLayout.CENTER);

        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        south.add(cancelButton);

        JPanel content = new JPanel(new BorderLayout(0, 10));
        content.setBorder(BorderFactory.createEmptyBorder(14, 18, 12, 18));
        content.add(north, BorderLayout.CENTER);
        content.add(south, BorderLayout.SOUTH);

        dialog.setContentPane(content);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(420, dialog.getHeight()));
        dialog.setLocationRelativeTo(owner);

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(false);

        cancelButton.addActionListener(e -> {
            cancelled.set(true);
            cancelButton.setEnabled(false);
            itemLabel.setText("Cancelling…");
        });

        NuclrPluginCallback callback = new NuclrPluginCallback() {
            @Override
            public void onStart(String description) {
                SwingUtilities.invokeLater(() -> itemLabel.setText((description == null ? "" : description)));
            }

            @Override
            public void onProgress(long current, long total) {
                SwingUtilities.invokeLater(() -> {
                    if (total > 0) {
                        bar.setIndeterminate(false);
                        bar.setValue((int) Math.min(100, current * 100 / total));
                    } else {
                        bar.setIndeterminate(true);
                    }
                });
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(String description, Exception e) {
                log.warn("{} error for [{}]: {}", title, description, e == null ? "?" : e.getMessage());
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };

        Thread.ofVirtual().name("gcs-" + title.toLowerCase()).start(() -> {
            try {
                work.accept(callback);
            } catch (Throwable t) {
                log.error("{} work failed: {}", title, t.getMessage(), t);
            } finally {
                SwingUtilities.invokeLater(() -> {
                    finished.set(true);
                    dialog.dispose();
                });
            }
        });

        // Modal: blocks here while pumping the EDT until the work thread disposes the dialog.
        dialog.setVisible(true);

        if (!finished.get()) {
            // Defensive: if the dialog was closed by other means, ensure the work sees cancellation.
            cancelled.set(true);
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
            log.warn("Failed to run progress dialog on EDT: {}", e.getMessage(), e);
        }
    }
}
