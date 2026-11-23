package dev.nuclr.plugin.core.panel.gcp.firestore;

import dev.nuclr.plugin.core.panel.gcp.GcpError;

/** A failed Firestore or Datastore call, carrying the error to show and the HTTP status (0 when there was none). */
public class FirestoreException extends Exception {

	private static final long serialVersionUID = 1L;

	private final transient GcpError error;
	private final int status;

	public FirestoreException(GcpError error, int status) {
		super(error.toString());
		this.error = error;
		this.status = status;
	}

	public GcpError error() {
		return error;
	}

	public int status() {
		return status;
	}
}
