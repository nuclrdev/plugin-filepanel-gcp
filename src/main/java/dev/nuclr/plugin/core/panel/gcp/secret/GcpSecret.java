package dev.nuclr.plugin.core.panel.gcp.secret;

import dev.nuclr.plugin.core.panel.gcp.*;

/**
 * A single Secret Manager secret, with the fields shown as panel columns. Values are already
 * display-formatted by {@link GcpSecretParser} (short name, human-readable created time, and a
 * replication/location summary).
 */
public record GcpSecret(String name, String created, String locations) {
}
