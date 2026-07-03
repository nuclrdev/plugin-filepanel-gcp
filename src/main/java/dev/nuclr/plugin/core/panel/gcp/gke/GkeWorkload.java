package dev.nuclr.plugin.core.panel.gcp.gke;

import dev.nuclr.plugin.core.panel.gcp.*;

/**
 * A single GKE workload (currently a Deployment), with the fields shown as panel columns. Values are
 * already display-formatted by {@link GkeWorkloadRepository}. {@code location}, {@code cluster},
 * {@code namespace} and {@code name} feed the workload's Cloud Console overview URL.
 */
public record GkeWorkload(
        String name, String type, String namespace, String cluster, String location, String ready) {
}
