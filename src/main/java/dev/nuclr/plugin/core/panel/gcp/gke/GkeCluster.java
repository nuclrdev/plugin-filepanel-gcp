package dev.nuclr.plugin.core.panel.gcp.gke;

import dev.nuclr.plugin.core.panel.gcp.*;

/**
 * A single GKE cluster, with the fields shown as panel columns. Values are already
 * display-formatted by {@link GkeClusterRepository}. {@code location} and {@code name} also
 * feed the cluster's Cloud Console overview URL.
 */
public record GkeCluster(String name, String location, String status, String version, String nodes) {
}
