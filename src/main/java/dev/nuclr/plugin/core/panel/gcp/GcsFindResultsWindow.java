package dev.nuclr.plugin.core.panel.gcp;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Non-modal results window for a GCP find. Implements {@link GcsFindService.Listener} so matches
 * stream in live (every callback already runs on the EDT). Shows progress, lets the user stop
 * (cancel) the search, activate a hit (open its Console page / go to it in the panel), and send the
 * whole result set to a temporary panel via the "Panel" button.
 */
@Slf4j
final class GcsFindResultsWindow extends JDialog implements GcsFindService.Listener {

    private static final long serialVersionUID = 1L;

    private final transient DefaultListModel<NuclrResource> model = new DefaultListModel<>();
    private final JList<NuclrResource> list = new JList<>(model);
    private final JLabel status = new JLabel("Searching…");
    private final JButton stopButton = new JButton("Stop");
    private final JButton panelButton = new JButton("Panel");
    private final JButton closeButton = new JButton("Close");

    private final transient Consumer<NuclrResource> onActivate;
    private final transient Consumer<List<NuclrResource>> onSendToPanel;

    private transient GcsFindService.SearchHandle handle;
    private volatile boolean finished;

    GcsFindResultsWindow(Window owner, GcsFindRequest request, Consumer<NuclrResource> onActivate,
            Consumer<List<NuclrResource>> onSendToPanel) {
        super(owner, "Find results — " + request.namePattern(), ModalityType.MODELESS);
        this.onActivate = onActivate;
        this.onSendToPanel = onSendToPanel;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, list.getFont().getSize()));
        list.setCellRenderer(pathRenderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    activateSelection();
                }
            }
        });
        list.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find.activate");
        list.getActionMap().put("find.activate", action(this::activateSelection));

        stopButton.addActionListener(e -> stopSearch());
        panelButton.setToolTipText("Open these results in a temporary panel");
        panelButton.setEnabled(false);
        panelButton.addActionListener(e -> sendResultsToPanel());
        closeButton.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(stopButton);
        buttons.add(panelButton);
        buttons.add(closeButton);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        content.add(status, BorderLayout.NORTH);
        content.add(new JScrollPane(list), BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find.results.close");
        getRootPane().getActionMap().put("find.results.close", action(this::dispose));

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                stopSearch();
            }
        });

        setPreferredSize(new Dimension(900, 520));
        setMinimumSize(new Dimension(640, 360));
        pack();
        setLocationRelativeTo(null);
    }

    /** Bind the running search so the window can stop it. */
    void bind(GcsFindService.SearchHandle handle) {
        this.handle = handle;
    }

    @Override
    public void onMatch(GcsFindService.NuclrResourceMatch match) {
        model.addElement(match.resource());
        panelButton.setEnabled(onSendToPanel != null);
        refreshStatus(-1);
    }

    @Override
    public void onProgress(long scanned, long matched) {
        if (!finished) {
            refreshStatus(scanned);
        }
    }

    @Override
    public void onComplete(long scanned, long matched, boolean cancelled) {
        finished = true;
        status.setText("Search " + (cancelled ? "stopped" : "complete") + " — "
                + model.getSize() + " match(es), " + scanned + " scanned");
        stopButton.setEnabled(false);
    }

    private void refreshStatus(long scanned) {
        StringBuilder sb = new StringBuilder();
        sb.append(model.getSize()).append(" match(es)");
        if (scanned >= 0) {
            sb.append(" — ").append(scanned).append(" scanned");
        }
        sb.append(" — searching…");
        status.setText(sb.toString());
    }

    private void stopSearch() {
        if (handle != null) {
            handle.cancel();
        }
    }

    private void activateSelection() {
        NuclrResource selected = list.getSelectedValue();
        if (selected != null && onActivate != null) {
            onActivate.accept(selected);
        }
    }

    /** Hand the full result set to a temporary panel and close this window. */
    private void sendResultsToPanel() {
        if (onSendToPanel == null || model.isEmpty()) {
            return;
        }
        List<NuclrResource> snapshot = new ArrayList<>(model.getSize());
        for (int i = 0; i < model.getSize(); i++) {
            snapshot.add(model.getElementAt(i));
        }
        onSendToPanel.accept(snapshot);
        dispose();
    }

    private static DefaultListCellRenderer pathRenderer() {
        return new DefaultListCellRenderer() {
            private static final long serialVersionUID = 1L;

            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                    boolean focus) {
                String text = value instanceof NuclrResource r
                        ? (r.getFullPath() != null ? r.getFullPath() : r.getName())
                        : String.valueOf(value);
                return super.getListCellRendererComponent(list, text, index, selected, focus);
            }
        };
    }

    private static AbstractAction action(Runnable r) {
        return new AbstractAction() {
            private static final long serialVersionUID = 1L;

            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        };
    }
}
