package dev.nuclr.plugin.core.panel.gcp.pubsub;

import dev.nuclr.plugin.core.panel.gcp.*;

/**
 * A single Pub/Sub topic, with the fields shown as panel columns. Values are already
 * display-formatted by {@link GcpPubsubRepository} (short topic id and message-retention summary).
 */
public record GcpPubsubTopic(String name, String retention) {
}
