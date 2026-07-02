package dev.nuclr.plugin.core.panel.gcp;

/**
 * A single Pub/Sub subscription, with the fields shown as panel columns. Values are already
 * display-formatted by {@link GcpPubsubRepository} (short subscription id, the short id of the
 * topic it is attached to, delivery type, and ack deadline).
 */
public record GcpPubsubSubscription(String name, String topic, String type, String ackDeadline) {
}
